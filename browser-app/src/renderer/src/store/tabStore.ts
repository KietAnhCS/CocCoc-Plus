import { create } from 'zustand'
import { useHistoryStore } from './historyStore'
import { useSearchViewStore } from './searchViewStore'

export const HOME_URL = 'vnsearch://home'

export interface TabInfo {
  id: string
  url: string
  title: string
  loading: boolean
}

/** Khop voi TabState gui tu main process (main/tabManager.ts) qua browser:tabUpdate. */
interface TabUpdatePayload {
  id: string
  url: string
  title: string
  loading: boolean
  canGoBack: boolean
  canGoForward: boolean
}

interface TabStoreState {
  tabs: TabInfo[]
  activeTabId: string | null
  initialized: boolean
  init: () => void
  newTab: (url?: string) => Promise<void>
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

    const applyTabUpdate = (tab: TabUpdatePayload): void => {
      const history = useHistoryStore.getState()
      history.ensureTab(tab.id, tab.url)
      history.recordNavigation(tab.id, tab.url)

      set((state) => {
        const idx = state.tabs.findIndex((t) => t.id === tab.id)
        const info: TabInfo = { id: tab.id, url: tab.url, title: tab.title, loading: tab.loading }
        const tabs =
          idx >= 0 ? state.tabs.map((t, i) => (i === idx ? info : t)) : [...state.tabs, info]
        return { tabs, activeTabId: state.activeTabId ?? tab.id }
      })
    }

    window.browser.onTabUpdate((payload) => applyTabUpdate(payload as TabUpdatePayload))

    // Tab dau tien (trang home) duoc TabManager tao TRONG constructor, tuc
    // la truoc khi dong ipcRenderer.on o tren kip dang ky - nen goi push
    // dau tien qua browser:tabUpdate bi mat (send khong hang doi). Vi vay
    // PHAI chu dong keo (pull) danh sach tab hien tai ngay sau khi mount.
    window.browser.listTabs().then((tabs) => {
      for (const tab of tabs as TabUpdatePayload[]) {
        applyTabUpdate(tab)
      }
    })
  },

  newTab: async (url) => {
    useSearchViewStore.getState().setQuery(null)
    const id = await window.browser.newTab(url ?? HOME_URL)
    set({ activeTabId: id })
  },

  closeTab: async (id) => {
    await window.browser.closeTab(id)
    useHistoryStore.getState().removeTab(id)
    set((state) => ({ tabs: state.tabs.filter((t) => t.id !== id) }))
  },

  switchTab: async (id) => {
    await window.browser.switchTab(id)
    set({ activeTabId: id })
  },

  navigate: async (url) => {
    const { activeTabId } = get()
    if (!activeTabId) {
      return
    }
    await window.browser.navigate(activeTabId, url)
  },

  goBack: async () => {
    const { activeTabId } = get()
    if (!activeTabId) {
      return
    }
    const prevUrl = useHistoryStore.getState().goBack(activeTabId)
    if (prevUrl !== undefined) {
      await window.browser.navigate(activeTabId, prevUrl)
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
