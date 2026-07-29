import { create } from 'zustand'
import { Stack } from '../lib/Stack'

/**
 * Zustand store cho back/forward, dung 2 Stack TU CAI DAT (khong dung
 * array.push/pop truc tiep, xem lib/Stack.ts) rieng cho MOI tab.
 *
 * Quy tac (mo hinh Stack kinh dien cho dieu huong trinh duyet):
 *   - recordNavigation(url moi): push URL HIEN TAI vao backStack, CLEAR forwardStack,
 *     roi cap nhat currentUrl = url moi. Goi moi khi tab thuc su chuyen sang
 *     mot URL khac (nguoi dung go dia chi, bam ket qua tim kiem, hoac bam
 *     link ben trong trang) — TRU khi do la ket qua cua chinh goBack/goForward.
 *   - goBack(): pop backStack -> push URL hien tai vao forwardStack -> tra ve
 *     URL vua pop de goi dieu huong that su qua IPC.
 *   - goForward(): doi xung voi goBack().
 *
 * Day KHONG dung lai lich su native cua Electron WebContents
 * (wc.goBack()/wc.canGoBack()) — no la mot cau truc Stack doc lap, tu quan
 * ly hoan toan o phia renderer, dung de minh hoa DSA cho bao cao.
 */

interface TabHistory {
  backStack: Stack<string>
  forwardStack: Stack<string>
  currentUrl: string
  /** Danh dau lan cap nhat URL tiep theo la do chinh goBack/goForward gay ra,
   *  de recordNavigation khong push lai vao stack mot cach sai lech. */
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
    const h = get().histories[tabId]
    if (!h || newUrl === h.currentUrl) {
      return
    }
    if (h.suppressNextRecord) {
      // URL nay la ket qua cua goBack/goForward do chinh ta goi -> chi cap
      // nhat currentUrl, KHONG push them vao backStack (da xu ly trong goBack/goForward roi).
      set((state) => ({
        histories: {
          ...state.histories,
          [tabId]: { ...h, currentUrl: newUrl, suppressNextRecord: false }
        }
      }))
      return
    }
    h.backStack.push(h.currentUrl)
    h.forwardStack.clear()
    set((state) => ({
      histories: { ...state.histories, [tabId]: { ...h, currentUrl: newUrl } }
    }))
  },

  goBack: (tabId) => {
    const h = get().histories[tabId]
    if (!h || h.backStack.isEmpty()) {
      return undefined
    }
    const prevUrl = h.backStack.pop() as string
    h.forwardStack.push(h.currentUrl)
    set((state) => ({
      histories: { ...state.histories, [tabId]: { ...h, suppressNextRecord: true } }
    }))
    return prevUrl
  },

  goForward: (tabId) => {
    const h = get().histories[tabId]
    if (!h || h.forwardStack.isEmpty()) {
      return undefined
    }
    const nextUrl = h.forwardStack.pop() as string
    h.backStack.push(h.currentUrl)
    set((state) => ({
      histories: { ...state.histories, [tabId]: { ...h, suppressNextRecord: true } }
    }))
    return nextUrl
  },

  canGoBack: (tabId) => {
    const h = get().histories[tabId]
    return !!h && !h.backStack.isEmpty()
  },

  canGoForward: (tabId) => {
    const h = get().histories[tabId]
    return !!h && !h.forwardStack.isEmpty()
  },

  removeTab: (tabId) => {
    set((state) => {
      const rest = { ...state.histories }
      delete rest[tabId]
      return { histories: rest }
    })
  }
}))
