import { create } from 'zustand'
import { Stack } from '../lib/Stack'

interface TabHistory {
  backStack: Stack<string>
  forwardStack: Stack<string>
  currentUrl: string
  suppressNextRecord: boolean
}

interface HistoryState {
  histories: Record<string, TabHistory>
  ensureTab: (tabId: string, initialUrl: string) => void
  recordNavigation: (tabId: string, newUrl: string) => void
  goBack: (tabId: string) => string | undefined
  goForward: (tabId: string) => string | undefined
  canGoBack: (tabId: string) => boolean
  canGoForward: (tabId: string) => boolean
  removeTab: (tabId: string) => void
  trackedTabIds: () => string[]
  recentUrls: (limit: number) => string[]
  clearAll: () => void
}

function newTabHistory(initialUrl: string): TabHistory {
  return {
    backStack: new Stack<string>(),
    forwardStack: new Stack<string>(),
    currentUrl: initialUrl,
    suppressNextRecord: false
  }
}

export const useHistoryStore = create<HistoryState>((set, get) => ({
  histories: {},

  ensureTab: (tabId, initialUrl) => {
    if (get().histories[tabId]) {
      return
    }
    set((state) => ({
      histories: { ...state.histories, [tabId]: newTabHistory(initialUrl) }
    }))
  },

  recordNavigation: (tabId, newUrl) => {
    const history = get().histories[tabId]
    if (!history || newUrl === history.currentUrl) {
      return
    }

    if (history.suppressNextRecord) {
      set((state) => ({
        histories: {
          ...state.histories,
          [tabId]: { ...history, currentUrl: newUrl, suppressNextRecord: false }
        }
      }))
      return
    }

    history.backStack.push(history.currentUrl)
    history.forwardStack.clear()
    set((state) => ({
      histories: { ...state.histories, [tabId]: { ...history, currentUrl: newUrl } }
    }))
  },

  goBack: (tabId) => {
    const history = get().histories[tabId]
    if (!history || history.backStack.isEmpty()) {
      return undefined
    }
    const previousUrl = history.backStack.pop() as string
    history.forwardStack.push(history.currentUrl)
    set((state) => ({
      histories: { ...state.histories, [tabId]: { ...history, suppressNextRecord: true } }
    }))
    return previousUrl
  },

  goForward: (tabId) => {
    const history = get().histories[tabId]
    if (!history || history.forwardStack.isEmpty()) {
      return undefined
    }
    const nextUrl = history.forwardStack.pop() as string
    history.backStack.push(history.currentUrl)
    set((state) => ({
      histories: { ...state.histories, [tabId]: { ...history, suppressNextRecord: true } }
    }))
    return nextUrl
  },

  canGoBack: (tabId) => {
    const history = get().histories[tabId]
    return !!history && !history.backStack.isEmpty()
  },

  canGoForward: (tabId) => {
    const history = get().histories[tabId]
    return !!history && !history.forwardStack.isEmpty()
  },

  removeTab: (tabId) => {
    set((state) => {
      const remaining = { ...state.histories }
      delete remaining[tabId]
      return { histories: remaining }
    })
  },

  trackedTabIds: () => Object.keys(get().histories),

  recentUrls: (limit) => {
    const seen = new Set<string>()
    const urls: string[] = []

    for (const history of Object.values(get().histories)) {
      for (const url of [history.currentUrl, ...history.backStack.toArray().reverse()]) {
        if (!seen.has(url)) {
          seen.add(url)
          urls.push(url)
        }
      }
    }
    return urls.slice(0, limit)
  },

  clearAll: () => {
    set((state) => {
      const cleared: Record<string, TabHistory> = {}
      for (const [tabId, history] of Object.entries(state.histories)) {
        cleared[tabId] = newTabHistory(history.currentUrl)
      }
      return { histories: cleared }
    })
  }
}))
