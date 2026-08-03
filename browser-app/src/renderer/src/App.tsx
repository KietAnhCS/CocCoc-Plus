import { useEffect } from 'react'
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

/**
 * Vỏ trình duyệt (chrome view). Ba thanh xếp chồng ở trên (thanh tab, thanh
 * công cụ, thanh dấu trang), vùng nội dung ở giữa, cột biểu tượng và bảng
 * bên nằm sát mép phải.
 *
 * Vùng nội dung được vẽ khi tab đang ở trang chủ nội bộ:
 *   - HOME_URL + có truy vấn -> SearchResultList.
 *   - HOME_URL, chưa có truy vấn -> NewTabPage.
 *   - Một URL thật -> để trống, vì TabManager đã chồng một WebContentsView
 *     RIÊNG lên phía trên để hiển thị trang đó (xem main/tabManager.ts).
 *
 * Chiều cao vỏ (40 thanh tab + 48 thanh công cụ + 34 thanh dấu trang = 122px)
 * phải khớp với hằng CHROME_HEIGHT trong main/tabManager.ts, nếu không
 * WebContentsView sẽ che mất thanh dấu trang hoặc chừa ra một khe hở.
 */
function App(): JSX.Element {
  const init = useTabStore((s) => s.init)
  const tabs = useTabStore((s) => s.tabs)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const query = useSearchViewStore((s) => s.query)
  const overlayCount = useOverlayStore((s) => s.count)
  const panelOpen = useSidePanelStore((s) => s.open)

  useBrowserShortcuts()

  useEffect(() => {
    init()
  }, [init])

  // Bảng bên NEO cạnh trang chứ không phủ lên: báo bề ngang xuống main
  // process để WebContentsView của trang ngoài co lại đúng chừng ấy.
  useEffect(() => {
    window.browser.setPanelWidth(panelOpen ? PANEL_WIDTH : 0)
  }, [panelOpen])

  // Menu/popover đổ dài quá vùng thanh công cụ, mà trang ngoài luôn nằm TRÊN
  // vỏ — nên phải tạm gỡ trang xuống trong lúc còn lớp phủ nào đang mở.
  useEffect(() => {
    window.browser.setOverlay(overlayCount > 0)
  }, [overlayCount])

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const showInternalContent = !activeTab || activeTab.url === HOME_URL

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-chrome text-ink">
      <TabBar />
      <Toolbar />
      <BookmarksBar />

      <div className="flex min-h-0 flex-1">
        <main className="min-w-0 flex-1 bg-surface">
          {showInternalContent && (query ? <SearchResultList /> : <NewTabPage />)}
        </main>
        <SidePanel />
        <SideRail />
      </div>
    </div>
  )
}

export default App
