import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * Ghi lượt ghé lên history-service.
 *
 * Bài này tồn tại vì một lỗi thật: nhật ký trống trơn dù người dùng đã đăng
 * nhập và không dùng ẩn danh. Nguyên nhân là phép so "địa chỉ có đổi không"
 * đối chiếu với ảnh chụp thẻ TRƯỚC ĐÓ, trong khi ảnh chụp ấy đã kịp mang địa
 * chỉ mới ngay từ sự kiện `loading: true`. Đến sự kiện `loading: false` thì
 * hai bên bằng nhau và không lượt nào được ghi.
 *
 * Lỗi im lặng tuyệt đối: `recordVisit` đi qua `sendAndForget`, vốn nuốt mọi lỗi — mà ở
 * đây thì đến một lượt gọi cũng không hề phát ra. Và tests/api gọi thẳng
 * POST /api/history/visits nên không đi qua tầng này.
 */

const browsing = vi.hoisted(() => ({ ghi: vi.fn() }))
const settings = vi.hoisted(() => ({ luuLichSu: true }))

vi.mock('./browsingStore', () => ({
  useBrowsingStore: { getState: () => ({ ghi: browsing.ghi }) }
}))
vi.mock('./settingsStore', () => ({
  useSettingsStore: { getState: () => ({ tuyChon: { luuLichSu: settings.luuLichSu } }) }
}))
vi.mock('./historyStore', () => ({
  useHistoryStore: {
    getState: () => ({
      ensureTab: vi.fn(),
      recordNavigation: vi.fn(),
      trackedTabIds: () => [],
      removeTab: vi.fn()
    })
  }
}))

interface TabLike {
  id: string
  url: string
  title: string
  loading: boolean
  incognito: boolean
}

/** Nhận hàm mà tabStore đăng ký với tiến trình chính. */
let phatSuKien: (snapshot: { tabs: TabLike[]; activeTabId: string | null }) => void

function the(url: string, loading: boolean, extra: Partial<TabLike> = {}): TabLike {
  return { id: 't1', url, title: 'Tiêu đề', loading, incognito: false, ...extra }
}

beforeEach(async () => {
  vi.resetModules()
  browsing.ghi.mockClear()
  settings.luuLichSu = true

  Object.defineProperty(globalThis, 'window', {
    value: {
      browser: {
        onTabsChanged: (fn: typeof phatSuKien) => {
          phatSuKien = fn
        },
        listTabs: () => Promise.resolve({ tabs: [], activeTabId: null })
      }
    },
    writable: true,
    configurable: true
  })

  const { useTabStore } = await import('./tabStore')
  useTabStore.getState().init()
})

describe('ghi lượt ghé khi điều hướng', () => {
  it('ghi đúng một lần sau khi trang tải xong', () => {
    phatSuKien({ tabs: [the('https://vnexpress.net/bong-da', true)], activeTabId: 't1' })
    expect(browsing.ghi).not.toHaveBeenCalled()

    phatSuKien({ tabs: [the('https://vnexpress.net/bong-da', false)], activeTabId: 't1' })

    expect(browsing.ghi).toHaveBeenCalledTimes(1)
    expect(browsing.ghi).toHaveBeenCalledWith(
      'https://vnexpress.net/bong-da',
      'Tiêu đề',
      false
    )
  })

  it('không ghi lại cùng một địa chỉ ở những sự kiện sau', () => {
    phatSuKien({ tabs: [the('https://vnexpress.net/a', true)], activeTabId: 't1' })
    phatSuKien({ tabs: [the('https://vnexpress.net/a', false)], activeTabId: 't1' })
    phatSuKien({ tabs: [the('https://vnexpress.net/a', false)], activeTabId: 't1' })

    expect(browsing.ghi).toHaveBeenCalledTimes(1)
  })

  it('ghi tiếp khi thẻ đi sang địa chỉ khác', () => {
    phatSuKien({ tabs: [the('https://vnexpress.net/a', true)], activeTabId: 't1' })
    phatSuKien({ tabs: [the('https://vnexpress.net/a', false)], activeTabId: 't1' })
    phatSuKien({ tabs: [the('https://vnexpress.net/b', true)], activeTabId: 't1' })
    phatSuKien({ tabs: [the('https://vnexpress.net/b', false)], activeTabId: 't1' })

    expect(browsing.ghi).toHaveBeenCalledTimes(2)
    expect(browsing.ghi.mock.calls[1][0]).toBe('https://vnexpress.net/b')
  })

  it('thẻ ẩn danh vẫn gọi nhưng mang cờ ẩn danh để browsingStore chặn', () => {
    phatSuKien({
      tabs: [the('https://vnexpress.net/a', false, { incognito: true })],
      activeTabId: 't1'
    })

    expect(browsing.ghi).toHaveBeenCalledWith('https://vnexpress.net/a', 'Tiêu đề', true)
  })

  it('tắt lưu lịch sử cũng cho ra cờ chặn', () => {
    settings.luuLichSu = false

    phatSuKien({ tabs: [the('https://vnexpress.net/a', false)], activeTabId: 't1' })

    expect(browsing.ghi).toHaveBeenCalledWith('https://vnexpress.net/a', 'Tiêu đề', true)
  })
})
