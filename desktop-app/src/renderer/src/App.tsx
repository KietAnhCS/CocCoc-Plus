import { useEffect, type JSX } from 'react'
import TabBar from './components/TabBar'
import Toolbar from './components/Toolbar'
import BookmarksBar from './components/BookmarksBar'
import SideRail from './components/SideRail'
import SidePanel from './components/SidePanel'
import NewTabPage from './components/NewTabPage'
import SearchResultList from './components/SearchResultList'
import FootballApp from './components/FootballApp'
import AdminPanel from './components/admin/AdminPanel'
import AuthScreen from './components/auth/AuthScreen'
import HistoryPage from './components/HistoryPage'
import SettingsPage from './components/SettingsPage'
import FindBar from './components/FindBar'
import { track } from './lib/telemetry'
import { useTabStore, HOME_URL } from './store/tabStore'
import { useSearchViewStore } from './store/searchViewStore'
import { useOverlayStore } from './store/overlayStore'
import { useSidePanelStore, PANEL_WIDTH } from './store/sidePanelStore'
import { useSessionStore } from './store/sessionStore'
import { useSettingsStore } from './store/settingsStore'
import { useFindStore } from './store/findStore'
import { useDownloadStore } from './store/downloadStore'
import { useBrowserShortcuts } from './lib/useBrowserShortcuts'

function App(): JSX.Element {
  const init = useTabStore((state) => state.init)
  const tabs = useTabStore((state) => state.tabs)
  const activeTabId = useTabStore((state) => state.activeTabId)
  const query = useSearchViewStore((state) => state.query)
  const overlayCount = useOverlayStore((state) => state.count)
  const panelOpen = useSidePanelStore((state) => state.open)
  const restoreSession = useSessionStore((state) => state.restore)
  const napTuyChon = useSettingsStore((state) => state.nap)
  const nhanKetQuaTim = useFindStore((state) => state.nhanKetQua)
  const initDownloads = useDownloadStore((state) => state.init)

  useBrowserShortcuts()

  // Theo dõi tải xuống NGAY từ lúc khởi động, không đợi người dùng mở bảng
  // Tải xuống. Đợi thì một tệp bắt đầu tải trước khi họ mở bảng sẽ không được
  // ghi nhận, và huy hiệu số lượt đang tải không bao giờ hiện.
  useEffect(() => {
    initDownloads()
  }, [initDownloads])

  // Kết quả tìm trong trang do tiến trình chính bắn về. Đăng ký một lần ở
  // đây thay vì trong FindBar: FindBar chỉ tồn tại khi ô tìm đang mở, và một
  // kết quả về sau khi ô vừa đóng sẽ không có ai nhận.
  useEffect(
    () =>
      window.browser.onFoundInPage((result) => {
        nhanKetQuaTim(result.activeMatchOrdinal, result.matches)
      }),
    [nhanKetQuaTim]
  )

  useEffect(() => {
    init()
    // Khôi phục phiên đăng nhập TRƯỚC khi gửi sự kiện, để sự kiện đầu tiên
    // cũng mang được danh tính. Máy chủ là nguồn sự thật: `restore` hỏi
    // /api/auth/me chứ không tin token trong localStorage.
    void restoreSession().then(() => {
      // Nạp tuỳ chọn SAU khi khôi phục phiên: settings-service cần token.
      // Trước đó giao diện đã vẽ bằng bản lưu trong localStorage, nên không
      // có khoảnh khắc nhấp nháy nào.
      void napTuyChon()
      // Một phiên bắt đầu. Gửi ngay ở đây chứ không đợi lượt tìm kiếm đầu
      // tiên: người mở ứng dụng rồi chỉ duyệt web vẫn là một người truy cập,
      // và không đếm họ sẽ làm mọi tỉ lệ "lượt tìm trên mỗi người" bị thổi phồng.
      track({ type: 'visit' })
    })
  }, [init, restoreSession, napTuyChon])

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
        {/* `relative` để trang bóng đá phủ ĐÚNG khung nội dung này, giữ nguyên
            hàng tab, thanh địa chỉ và thanh bên — nó là một trang trong trình
            duyệt, không phải một hộp thoại nuốt cả cửa sổ. */}
        <main className="relative min-w-0 flex-1 bg-surface">
          {showInternalContent && (query ? <SearchResultList key={query} /> : <NewTabPage />)}
          <FootballApp />

          {/* Ba lớp nội bộ, tự ẩn khi chưa mở. Nằm TRONG <main> để chúng phủ
              đúng khung nội dung, giữ nguyên hàng thẻ và thanh địa chỉ — chúng
              là trang trong trình duyệt, không phải hộp thoại nuốt cả cửa sổ. */}
          <HistoryPage />
          <SettingsPage />
          <FindBar />
        </main>
        <SidePanel />
        <SideRail />
      </div>

      {/* Hai lớp phủ toàn màn hình, tự ẩn khi chưa mở. Đặt CUỐI cây để chúng
          nằm trên mọi thứ mà không cần đẩy z-index của các phần khác lên. */}
      <AuthScreen />
      <AdminPanel />
    </div>
  )
}

export default App
