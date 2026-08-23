import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * Kho token phiên.
 *
 * Module này giữ một bản sao trong bộ nhớ, nên mỗi bài phải nạp lại module —
 * `vi.resetModules()` + `import()` động. Dùng `import` tĩnh ở đầu tệp thì bài
 * thứ hai sẽ thấy bản sao mà bài thứ nhất để lại, và cả tệp trở nên phụ thuộc
 * thứ tự chạy.
 */

interface FakeStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
  data: Record<string, string>
}

function fakeStorage(initial: Record<string, string> = {}): FakeStorage {
  const data = { ...initial }
  return {
    data,
    getItem: (key) => data[key] ?? null,
    setItem: (key, value) => {
      data[key] = value
    },
    removeItem: (key) => {
      delete data[key]
    }
  }
}

async function loadModule(storage: FakeStorage | null): Promise<typeof import('./authToken')> {
  vi.resetModules()
  if (storage) {
    // Môi trường test chạy trên Node nên không có `window`. Dựng vừa đủ phần
    // mà module thật sự chạm tới.
    ;(globalThis as { window?: unknown }).window = { localStorage: storage }
  } else {
    delete (globalThis as { window?: unknown }).window
  }
  return import('./authToken')
}

describe('authToken', () => {
  beforeEach(() => {
    delete (globalThis as { window?: unknown }).window
  })

  it('chưa có token thì header rỗng, không phải header hỏng', async () => {
    const { getAuthToken, authHeader } = await loadModule(fakeStorage())

    expect(getAuthToken()).toBeNull()
    expect(authHeader()).toEqual({})
  })

  it('đọc lại token đã lưu từ lần chạy trước', async () => {
    const { getAuthToken } = await loadModule(fakeStorage({ 'vnsearch-session-token': 'token-cu' }))

    expect(getAuthToken()).toBe('token-cu')
  })

  it('ghi token thì cả bộ nhớ lẫn đĩa cùng đổi', async () => {
    const storage = fakeStorage()
    const { setAuthToken, getAuthToken } = await loadModule(storage)

    setAuthToken('token-moi')

    expect(getAuthToken()).toBe('token-moi')
    expect(storage.data['vnsearch-session-token']).toBe('token-moi')
  })

  it('đặt null thì xoá khỏi đĩa — không để lại chuỗi "null"', async () => {
    const storage = fakeStorage({ 'vnsearch-session-token': 'token-cu' })
    const { setAuthToken, getAuthToken } = await loadModule(storage)

    setAuthToken(null)

    expect(getAuthToken()).toBeNull()
    expect('vnsearch-session-token' in storage.data).toBe(false)
  })

  it('sinh đúng header Bearer', async () => {
    const { setAuthToken, authHeader } = await loadModule(fakeStorage())

    setAuthToken('abc123')

    expect(authHeader()).toEqual({ Authorization: 'Bearer abc123' })
  })

  /**
   * Không có `localStorage` (môi trường bị hạn chế) thì phải chạy tiếp, không
   * được ném: mất khả năng nhớ phiên là phiền, còn làm sập giao diện là hỏng.
   */
  it('thiếu localStorage cũng không ném', async () => {
    const { getAuthToken, setAuthToken, authHeader } = await loadModule(null)

    expect(() => setAuthToken('abc')).not.toThrow()
    expect(getAuthToken()).toBe('abc') // vẫn giữ trong bộ nhớ của phiên này
    expect(authHeader()).toEqual({ Authorization: 'Bearer abc' })
  })
})
