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

interface TabsSnapshot {
  tabs: TabInfo[]
  activeTabId: string | null
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

    const applySnapshot = ({ tabs, activeTabId }: TabsSnapshot): void => {
      const history = useHistoryStore.getState()
      const liveIds = new Set(tabs.map((tab) => tab.id))

      for (const tab of tabs) {
        history.ensureTab(tab.id, tab.url)
        history.recordNavigation(tab.id, tab.url)
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

  newTab: async (url) => {
    useSearchViewStore.getState().clear()
    await window.browser.newTab(url ?? HOME_URL)
  },

  closeTab: async (id) => {
    await window.browser.closeTab(id)
  },

  switchTab: async (id) => {
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
