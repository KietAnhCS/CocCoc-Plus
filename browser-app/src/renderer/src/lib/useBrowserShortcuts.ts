import { useEffect } from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useBookmarkStore } from '../store/bookmarkStore'
import { useSearchViewStore } from '../store/searchViewStore'

/** Các lệnh mà một phím tắt có thể kích hoạt. Trùng tên với phía main process. */
export type ShortcutName =
  | 'newTab'
  | 'closeTab'
  | 'focusOmnibox'
  | 'reload'
  | 'back'
  | 'forward'
  | 'bookmark'
  | 'home'

/** Đọc một sự kiện bàn phím thành tên lệnh, hoặc null nếu không phải phím tắt. */
export function shortcutFromEvent(e: {
  key: string
  ctrlKey: boolean
  altKey: boolean
  shiftKey: boolean
}): ShortcutName | null {
  const key = e.key.toLowerCase()

  if (e.ctrlKey && !e.altKey && !e.shiftKey) {
    if (key === 't') return 'newTab'
    if (key === 'w') return 'closeTab'
    if (key === 'l') return 'focusOmnibox'
    if (key === 'd') return 'bookmark'
    if (key === 'r') return 'reload'
  }
  if (e.altKey && !e.ctrlKey) {
    if (key === 'd') return 'focusOmnibox'
    if (key === 'arrowleft') return 'back'
    if (key === 'arrowright') return 'forward'
    if (key === 'home') return 'home'
  }
  if (key === 'f5' && !e.ctrlKey && !e.altKey) {
    return 'reload'
  }
  return null
}

/**
 * Phím tắt trình duyệt. Sự kiện đến từ hai nguồn và được xử lý chung một
 * chỗ để không có hai đường code lệch nhau:
 *
 *  1. Bàn phím gõ ngay trong vỏ trình duyệt (trang chủ / trang kết quả).
 *  2. Bàn phím gõ khi con trỏ đang ở một trang ngoài — lúc đó phím đi thẳng
 *     vào WebContentsView của tab, nên main process bắt lấy bằng
 *     `before-input-event` rồi chuyển tiếp về đây (xem main/tabManager.ts).
 *
 * Back/forward cố tình đi qua tabStore chứ không gọi thẳng Electron, vì
 * lịch sử của ứng dụng do historyStore (Stack tự cài) nắm giữ — để main
 * process tự xử lý sẽ khiến hai bên lệch nhau.
 */
export function useBrowserShortcuts(): void {
  useEffect(() => {
    function run(name: ShortcutName): void {
      const tabStore = useTabStore.getState()
      const activeTab = tabStore.tabs.find((t) => t.id === tabStore.activeTabId)

      switch (name) {
        case 'newTab':
          tabStore.newTab()
          break
        case 'closeTab':
          if (tabStore.activeTabId) {
            tabStore.closeTab(tabStore.activeTabId)
          }
          break
        case 'focusOmnibox': {
          const omnibox = document.getElementById('omnibox')
          if (omnibox instanceof HTMLInputElement) {
            omnibox.focus()
            omnibox.select()
          }
          break
        }
        case 'reload':
          tabStore.reload()
          break
        case 'back':
          tabStore.goBack()
          break
        case 'forward':
          tabStore.goForward()
          break
        case 'home':
          useSearchViewStore.getState().setQuery(null)
          tabStore.navigate(HOME_URL)
          break
        case 'bookmark':
          if (activeTab && activeTab.url !== HOME_URL) {
            useBookmarkStore.getState().toggleBookmark(activeTab.title, activeTab.url)
          }
          break
      }
    }

    function onKeyDown(e: KeyboardEvent): void {
      const name = shortcutFromEvent(e)
      if (name) {
        e.preventDefault()
        run(name)
      }
    }

    window.addEventListener('keydown', onKeyDown)
    window.browser.onShortcut((name) => run(name as ShortcutName))
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])
}
