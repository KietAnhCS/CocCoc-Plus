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

    const applySnapshot = ({ tabs, activeTabId }: TabsSnapshot): void => {
      const history = useHistoryStore.getState()
      const liveIds = new Set(tabs.map((tab) => tab.id))

      const truoc = new Map(get().tabs.map((tab) => [tab.id, tab.url]))

      for (const tab of tabs) {
        history.ensureTab(tab.id, tab.url)
        history.recordNavigation(tab.id, tab.url)

        // Ghi lên history-service khi địa chỉ THẬT SỰ đổi và trang đã tải
        // xong. Ghi lúc `loading` còn true thì tiêu đề vẫn là địa chỉ thô —
        // và nhật ký đầy những dòng không đọc được.
        //
        // Ba điều kiện chặn, theo thứ tự rẻ tới đắt:
        //   1. thẻ ẩn danh                -> không bao giờ gửi
        //   2. người dùng tắt lưu lịch sử -> tôn trọng lựa chọn đó
        //   3. địa chỉ chưa đổi           -> không có gì mới để ghi
        if (!tab.loading && truoc.get(tab.id) !== tab.url) {
          const luuLichSu = useSettingsStore.getState().tuyChon.luuLichSu
          useBrowsingStore.getState().ghi(tab.url, tab.title, tab.incognito || !luuLichSu)
        }
      }
      for (const id of history.trackedTabIds()) {
        if (!liveIds.has(id)) {
          history.removeTab(id)
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
