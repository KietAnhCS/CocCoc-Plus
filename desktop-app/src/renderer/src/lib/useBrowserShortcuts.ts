import { useEffect } from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useBookmarkStore } from '../store/bookmarkStore'
import { useSearchViewStore } from '../store/searchViewStore'

export type ShortcutName =
  'newTab' | 'closeTab' | 'focusOmnibox' | 'reload' | 'back' | 'forward' | 'bookmark' | 'home'

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
