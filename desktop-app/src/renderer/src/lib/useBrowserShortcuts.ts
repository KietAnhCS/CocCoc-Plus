import { useEffect } from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useBookmarkStore } from '../store/bookmarkStore'
import { useSearchViewStore } from '../store/searchViewStore'
import { useFindStore } from '../store/findStore'
import { usePagesStore } from '../store/pagesStore'
import { useSidePanelStore } from '../store/sidePanelStore'

export type ShortcutName =
  | 'newTab'
  | 'newIncognitoTab'
  | 'newWindow'
  | 'closeTab'
  | 'focusOmnibox'
  | 'reload'
  | 'back'
  | 'forward'
  | 'bookmark'
  | 'home'
  | 'findInPage'
  | 'downloads'
  | 'history'
  | 'clearBrowsingData'
  | 'escape'

export function shortcutFromEvent(event: {
  key: string
  ctrlKey: boolean
  altKey: boolean
  shiftKey: boolean
}): ShortcutName | null {
  const key = event.key.toLowerCase()

  if (event.ctrlKey && !event.altKey && !event.shiftKey) {
    if (key === 't') return 'newTab'
    if (key === 'w') return 'closeTab'
    if (key === 'l') return 'focusOmnibox'
    if (key === 'd') return 'bookmark'
    if (key === 'r') return 'reload'
    if (key === 'f') return 'findInPage'
    if (key === 'j') return 'downloads'
    if (key === 'h') return 'history'
    if (key === 'n') return 'newWindow'
  }
  // Ctrl+Shift+N và Ctrl+Shift+Del. Phải khớp CHÍNH XÁC với bảng phím trong
  // `tabManager.shortcutName` — hai bảng vì phím bấm khi tiêu điểm ở vỏ giao
  // diện đi đường này, còn khi tiêu điểm ở nội dung trang thì đi qua
  // `before-input-event` của tiến trình chính. Lệch nhau nghĩa là một phím tắt
  // chỉ chạy ở nửa số trường hợp.
  if (event.ctrlKey && event.shiftKey && !event.altKey) {
    if (key === 'n') return 'newIncognitoTab'
    if (key === 'delete') return 'clearBrowsingData'
  }
  if (key === 'escape' && !event.ctrlKey && !event.altKey) {
    return 'escape'
  }
  if (event.altKey && !event.ctrlKey) {
    if (key === 'd') return 'focusOmnibox'
    if (key === 'arrowleft') return 'back'
    if (key === 'arrowright') return 'forward'
    if (key === 'home') return 'home'
  }
  if (key === 'f5' && !event.ctrlKey && !event.altKey) {
    return 'reload'
  }
  return null
}

function runShortcut(name: ShortcutName): void {
  const tabStore = useTabStore.getState()
  const activeTab = tabStore.tabs.find((tab) => tab.id === tabStore.activeTabId)

  switch (name) {
    case 'newTab':
      tabStore.newTab()
      break
    case 'newIncognitoTab':
      tabStore.newTab(undefined, true)
      break
    case 'newWindow':
      window.win.newWindow(false)
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
      useSearchViewStore.getState().clear()
      tabStore.navigate(HOME_URL)
      break
    case 'bookmark':
      if (activeTab && activeTab.url !== HOME_URL) {
        useBookmarkStore.getState().toggleBookmark(activeTab.url, activeTab.title)
      }
      break
    case 'findInPage':
      // Chỉ mở khi đang xem một TRANG WEB. Trang chủ và trang kết quả tìm kiếm
      // do React vẽ, và findInPage của Electron không tìm được trong chúng —
      // mở ô tìm ở đó là hứa một chức năng không hoạt động.
      if (activeTab && activeTab.url !== HOME_URL) {
        useFindStore.getState().moO()
      }
      break
    case 'downloads':
      usePagesStore.getState().dong()
      useSidePanelStore.getState().openPanel('downloads')
      break
    case 'history':
      useSidePanelStore.getState().closePanel()
      usePagesStore.getState().mo('history')
      break
    case 'clearBrowsingData':
      // Mở trang Nhật ký, nơi có nút xoá kèm hộp xác nhận. KHÔNG xoá thẳng:
      // một phím tắt xoá vĩnh viễn dữ liệu mà không hỏi là một phím tắt sẽ có
      // người bấm nhầm.
      useSidePanelStore.getState().closePanel()
      usePagesStore.getState().mo('history')
      break
    case 'escape': {
      // Thứ tự đóng: ô tìm trước, rồi tới trang nội bộ. Người dùng mong Escape
      // đóng đúng MỘT lớp — lớp trong cùng — chứ không đóng sạch mọi thứ.
      const findStore = useFindStore.getState()
      if (findStore.open) {
        findStore.dongO(tabStore.activeTabId)
      } else if (usePagesStore.getState().page) {
        usePagesStore.getState().dong()
      }
      break
    }
  }
}

export function useBrowserShortcuts(): void {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent): void {
      const name = shortcutFromEvent(event)
      if (name) {
        event.preventDefault()
        runShortcut(name)
      }
    }

    window.addEventListener('keydown', onKeyDown)
    const unsubscribe = window.browser.onShortcut((name) => runShortcut(name as ShortcutName))

    return () => {
      window.removeEventListener('keydown', onKeyDown)
      unsubscribe()
    }
  }, [])
}
