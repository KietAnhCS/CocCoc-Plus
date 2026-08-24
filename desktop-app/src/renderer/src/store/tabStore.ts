import { create } from 'zustand'
import { useHistoryStore } from './historyStore'
import { useSearchViewStore } from './searchViewStore'
import { useBrowsingStore } from './browsingStore'
import { useSettingsStore } from './settingsStore'
import { useFindStore } from './findStore'

export const HOME_URL = 'vnsearch://home'

export interface TabInfo {
  id: string
  url: string
  title: string
  loading: boolean
  /** Thẻ ẩn danh: session riêng, KHÔNG ghi lịch sử. */
  incognito: boolean
}

interface TabsSnapshot {
  tabs: TabInfo[]
  activeTabId: string | null
}

interface TabStoreState {
  tabs: TabInfo[]
  activeTabId: string | null
  initialized: boolean
  init: () => void
  newTab: (url?: string, incognito?: boolean) => Promise<void>
  closeTab: (id: string) => Promise<void>
  switchTab: (id: string) => Promise<void>
  navigate: (url: string) => Promise<void>
  goBack: () => Promise<void>
  goForward: () => Promise<void>
  reload: () => void
  canGoBack: () => boolean
  canGoForward: () => boolean
}

export const useTabStore = create<TabStoreState>((set, get) => ({
  tabs: [],
  activeTabId: null,
  initialized: false,

  init: () => {
    if (get().initialized) {
      return
    }
    set({ initialized: true })

    // Địa chỉ ĐÃ GHI vào nhật ký của từng thẻ.
    //
    // Phải là một bảng riêng chứ không so với ảnh chụp trước đó, và đây là một
    // lỗi đã có thật: nhật ký trống trơn dù người dùng đã đăng nhập và không
    // dùng ẩn danh.
    //
    // Một lượt điều hướng sinh ra ÍT NHẤT hai sự kiện:
    //
    //   1. { url: mới, loading: true  }   -> bị chặn vì trang chưa tải xong,
    //                                        nhưng `set({ tabs })` ở cuối hàm
    //                                        đã kịp lưu địa chỉ MỚI vào store
    //   2. { url: mới, loading: false }   -> lúc này ảnh chụp "trước" cũng đã
    //                                        mang địa chỉ mới, nên phép so
    //                                        "địa chỉ có đổi không" trả về SAI
    //
    // Tức là điều kiện cũ không bao giờ đúng cùng lúc với `!loading`, và không
    // một lượt ghé nào được ghi. Nó im lặng tuyệt đối: `recordVisit` gọi qua
    // `sendAndForget`, vốn nuốt mọi lỗi, mà ở đây thì đến một lượt gọi cũng không có.
    //
    // Bộ kiểm thử không bắt được vì tests/api gọi thẳng POST /api/history/visits,
    // không đi qua tầng này.
    const daGhi = new Map<string, string>()

    const applySnapshot = ({ tabs, activeTabId }: TabsSnapshot): void => {
      const history = useHistoryStore.getState()
      const liveIds = new Set(tabs.map((tab) => tab.id))

      for (const tab of tabs) {
        history.ensureTab(tab.id, tab.url)
        history.recordNavigation(tab.id, tab.url)

        // Ghi lên history-service khi trang đã tải xong và địa chỉ này chưa
        // từng được ghi cho thẻ đó. Ghi lúc `loading` còn true thì tiêu đề vẫn
        // là địa chỉ thô — và nhật ký đầy những dòng không đọc được.
        //
        // Ba điều kiện chặn, theo thứ tự rẻ tới đắt:
        //   1. thẻ ẩn danh                -> không bao giờ gửi
        //   2. người dùng tắt lưu lịch sử -> tôn trọng lựa chọn đó
        //   3. địa chỉ đã ghi rồi         -> không có gì mới để ghi
        if (!tab.loading && daGhi.get(tab.id) !== tab.url) {
          daGhi.set(tab.id, tab.url)
          const luuLichSu = useSettingsStore.getState().tuyChon.luuLichSu
          useBrowsingStore.getState().ghi(tab.url, tab.title, tab.incognito || !luuLichSu)
        }
      }
      for (const id of history.trackedTabIds()) {
        if (!liveIds.has(id)) {
          history.removeTab(id)
        }
      }
      for (const id of daGhi.keys()) {
        if (!liveIds.has(id)) {
          daGhi.delete(id)
        }
      }

      set({ tabs, activeTabId })
    }

    window.browser.onTabsChanged(applySnapshot)
    window.browser.listTabs().then(applySnapshot)
  },

  newTab: async (url, incognito = false) => {
    useSearchViewStore.getState().clear()
    await window.browser.newTab(url ?? HOME_URL, incognito)
  },

  closeTab: async (id) => {
    await window.browser.closeTab(id)
  },

  switchTab: async (id) => {
    // Đóng ô tìm khi đổi thẻ: kết quả tìm thuộc về thẻ CŨ, và để nguyên ô tìm
    // với bộ đếm "3/17" trên một thẻ khác là nói sai.
    useFindStore.getState().dongO(get().activeTabId)
    await window.browser.switchTab(id)
  },

  navigate: async (url) => {
    const { activeTabId } = get()
    if (activeTabId) {
      await window.browser.navigate(activeTabId, url)
    }
  },

  goBack: async () => {
    const { activeTabId } = get()
    if (!activeTabId) {
      return
    }
    const previousUrl = useHistoryStore.getState().goBack(activeTabId)
    if (previousUrl !== undefined) {
      await window.browser.navigate(activeTabId, previousUrl)
    }
  },

  goForward: async () => {
    const { activeTabId } = get()
    if (!activeTabId) {
      return
    }
    const nextUrl = useHistoryStore.getState().goForward(activeTabId)
    if (nextUrl !== undefined) {
      await window.browser.navigate(activeTabId, nextUrl)
    }
  },

  reload: () => {
    const { activeTabId } = get()
    if (activeTabId) {
      window.browser.reload(activeTabId)
    }
  },

  canGoBack: () => {
    const { activeTabId } = get()
    return !!activeTabId && useHistoryStore.getState().canGoBack(activeTabId)
  },

  canGoForward: () => {
    const { activeTabId } = get()
    return !!activeTabId && useHistoryStore.getState().canGoForward(activeTabId)
  }
}))
