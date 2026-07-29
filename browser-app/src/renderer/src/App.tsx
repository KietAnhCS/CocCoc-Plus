import { useEffect } from 'react'
import TabBar from './components/TabBar'
import NavigationButtons from './components/NavigationButtons'
import AddressBar from './components/AddressBar'
import SearchHomePage from './components/SearchHomePage'
import SearchResultList from './components/SearchResultList'
import { useTabStore, HOME_URL } from './store/tabStore'
import { useSearchViewStore } from './store/searchViewStore'

/**
 * PHASE 8: chrome view thuc su (TabBar/AddressBar/NavigationButtons noi
 * voi tabStore <-> main process qua IPC). Vung noi dung chinh:
 *   - Tab dang o HOME_URL + co query -> SearchResultList (PHASE 9).
 *   - Tab dang o HOME_URL, chua co query -> SearchHomePage.
 *   - Tab dang o mot URL that -> de trong, vi TabManager da chong mot
 *     WebContentsView RIENG len phia tren de hien thi trang do (xem
 *     main/tabManager.ts) — chrome view khong ve gi them o vung nay.
 */
function App(): JSX.Element {
  const init = useTabStore((s) => s.init)
  const tabs = useTabStore((s) => s.tabs)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const query = useSearchViewStore((s) => s.query)

  useEffect(() => {
    init()
  }, [init])

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const showInternalContent = !activeTab || activeTab.url === HOME_URL

  return (
    <div className="flex h-screen w-screen flex-col bg-gray-100 text-gray-900">
      <TabBar />
      <div className="flex items-center gap-2 border-b border-gray-200 bg-white px-2 py-1">
        <NavigationButtons />
        <AddressBar />
      </div>
      <main className="flex-1 overflow-auto">
        {showInternalContent && (query ? <SearchResultList /> : <SearchHomePage />)}
      </main>
    </div>
  )
}

export default App
