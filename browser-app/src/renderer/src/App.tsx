import { useEffect } from 'react'
import TabBar from './components/TabBar'
import NavigationButtons from './components/NavigationButtons'
import AddressBar from './components/AddressBar'
import SearchHomePage from './components/SearchHomePage'
import SearchResultList from './components/SearchResultList'
import { useTabStore, HOME_URL } from './store/tabStore'
import { useSearchViewStore } from './store/searchViewStore'
import { useThemeStore } from './store/themeStore'
import { useBrowserShortcuts } from './lib/useBrowserShortcuts'
import { MoonIcon, SunIcon } from './components/icons'

/**
 * Vỏ trình duyệt (chrome view): TabBar/AddressBar/NavigationButtons nối với
 * tabStore <-> main process qua IPC. Vùng nội dung chính:
 *   - Tab đang ở HOME_URL + có query -> SearchResultList.
 *   - Tab đang ở HOME_URL, chưa có query -> SearchHomePage.
 *   - Tab đang ở một URL thật -> để trống, vì TabManager đã chồng một
 *     WebContentsView RIÊNG lên phía trên để hiển thị trang đó (xem
 *     main/tabManager.ts) — vỏ trình duyệt không vẽ gì thêm ở vùng này.
 *
 * Chiều cao vỏ (40px thanh tab + 48px thanh công cụ = 88px) phải khớp với
 * hằng CHROME_HEIGHT trong main/tabManager.ts, nếu không WebContentsView sẽ
 * che mất thanh địa chỉ hoặc chừa ra một khe hở.
 */
function App(): JSX.Element {
  const init = useTabStore((s) => s.init)
  const tabs = useTabStore((s) => s.tabs)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const query = useSearchViewStore((s) => s.query)
  const theme = useThemeStore((s) => s.theme)
  const toggleTheme = useThemeStore((s) => s.toggleTheme)

  useBrowserShortcuts()

  useEffect(() => {
    init()
  }, [init])

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const showInternalContent = !activeTab || activeTab.url === HOME_URL

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-chrome text-ink">
      <TabBar />

      {/* border-b nằm TRONG chiều cao 48px (box-sizing: border-box của preflight),
          nên vỏ vẫn đúng 88px như CHROME_HEIGHT bên main process. */}
      <div className="flex h-12 shrink-0 items-center gap-1 border-b border-line bg-surface px-2.5">
        <NavigationButtons />
        <div className="mx-1 h-5 w-px shrink-0 bg-line" />
        <AddressBar />
        <button
          onClick={toggleTheme}
          className="icon-btn"
          aria-label="Đổi giao diện sáng/tối"
          title={theme === 'dark' ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối'}
        >
          {theme === 'dark' ? (
            <SunIcon className="h-[18px] w-[18px]" />
          ) : (
            <MoonIcon className="h-[18px] w-[18px]" />
          )}
        </button>
      </div>

      <main className="min-h-0 flex-1 bg-surface">
        {showInternalContent && (query ? <SearchResultList /> : <SearchHomePage />)}
      </main>
    </div>
  )
}

export default App
