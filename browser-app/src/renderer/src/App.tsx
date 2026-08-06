import { useEffect, type JSX } from 'react'
import TabBar from './components/TabBar'
import Toolbar from './components/Toolbar'
import BookmarksBar from './components/BookmarksBar'
import SideRail from './components/SideRail'
import SidePanel from './components/SidePanel'
import NewTabPage from './components/NewTabPage'
import SearchResultList from './components/SearchResultList'
import { useTabStore, HOME_URL } from './store/tabStore'
import { useSearchViewStore } from './store/searchViewStore'
import { useOverlayStore } from './store/overlayStore'
import { useSidePanelStore, PANEL_WIDTH } from './store/sidePanelStore'
import { useBrowserShortcuts } from './lib/useBrowserShortcuts'

function App(): JSX.Element {
  const init = useTabStore((state) => state.init)
  const tabs = useTabStore((state) => state.tabs)
  const activeTabId = useTabStore((state) => state.activeTabId)
  const query = useSearchViewStore((state) => state.query)
  const overlayCount = useOverlayStore((state) => state.count)
  const panelOpen = useSidePanelStore((state) => state.open)

  useBrowserShortcuts()

  useEffect(() => {
    init()
  }, [init])

  useEffect(() => {
    window.browser.setPanelWidth(panelOpen ? PANEL_WIDTH : 0)
  }, [panelOpen])

  useEffect(() => {
    window.browser.setOverlay(overlayCount > 0)
  }, [overlayCount])

  const activeTab = tabs.find((tab) => tab.id === activeTabId)
  const showInternalContent = !activeTab || activeTab.url === HOME_URL

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-chrome text-ink">
      <TabBar />
      <Toolbar />
      <BookmarksBar />

      <div className="flex min-h-0 flex-1">
        <main className="min-w-0 flex-1 bg-surface">
          {showInternalContent && (query ? <SearchResultList key={query} /> : <NewTabPage />)}
        </main>
        <SidePanel />
        <SideRail />
      </div>
    </div>
  )
}

export default App
