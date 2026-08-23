import { app, BrowserWindow } from 'electron'
import { electronApp, optimizer } from '@electron-toolkit/utils'
import { TabManager } from './tabManager'
import { DownloadManager } from './downloadManager'
import { registerIpcHandlers } from './ipcHandler'
import { registerWindowControls } from './windowControls'

/**
 * Mọi cửa sổ đang mở.
 *
 * <p>Cần một danh sách vì IPC là TOÀN CỤC: `ipcMain.handle` chỉ đăng ký được
 * MỘT handler cho mỗi kênh trong cả tiến trình. Với nhiều cửa sổ, handler phải
 * biết request đến từ cửa sổ nào — xem `registerIpcHandlers` được gọi lại mỗi
 * lần cửa sổ mới giành quyền.
 *
 * <p>Đây là cái giá của việc thêm "Cửa sổ mới" vào một ứng dụng vốn được viết
 * cho đúng một cửa sổ. Cách sạch hơn là định tuyến IPC theo `event.sender`,
 * nhưng nó đòi viết lại toàn bộ tầng IPC; cách này đủ đúng cho một ứng dụng
 * mà người dùng thao tác trên đúng một cửa sổ tại một thời điểm.
 */
const windows = new Set<BrowserWindow>()

function createWindow(incognito = false): BrowserWindow {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 560,
    show: false,
    frame: false,
    autoHideMenuBar: true,
    // Nền tối hơn cho cửa sổ ẩn danh, thấy ngay từ khung trống lúc khởi động.
    // Đây là tín hiệu thị giác đầu tiên và rẻ nhất; giao diện bên trong còn
    // đổi màu thanh công cụ và hiện huy hiệu.
    backgroundColor: incognito ? '#1b1526' : '#111318'
  })

  const tabManager = new TabManager(mainWindow)
  const downloadManager = new DownloadManager(tabManager.chromeContents)

  // PHẢI gọi trước khi thẻ đầu tiên tải xong: `onSession` gọi callback ngay
  // cho session mặc định, rồi gọi lại cho mỗi session mới (ẩn danh). Đăng ký
  // muộn thì những lượt tải đầu tiên không được ghi nhận.
  tabManager.onSession((session, isIncognito) => downloadManager.watch(session, isIncognito))

  registerIpcHandlers(tabManager, downloadManager)
  registerWindowControls(mainWindow, tabManager.chromeContents)

  if (incognito) {
    // Cửa sổ ẩn danh mở thẳng một thẻ ẩn danh thay vì thẻ thường. TabManager
    // đã tạo một thẻ thường trong hàm dựng, nên đóng nó lại — đóng thẻ cuối
    // cùng sẽ tự tạo thẻ mới, nên phải tạo thẻ ẩn danh TRƯỚC rồi mới đóng.
    const snapshot = tabManager.snapshot()
    tabManager.createTab(undefined, true)
    for (const tab of snapshot.tabs) {
      tabManager.closeTab(tab.id)
    }
  }

  windows.add(mainWindow)
  mainWindow.on('closed', () => windows.delete(mainWindow))

  // Cửa sổ giành lại quyền điều khiển IPC khi được đưa lên trước. Không có
  // dòng này thì mọi thao tác ở cửa sổ thứ hai lại tác động lên cửa sổ thứ
  // nhất — thẻ mở ra ở đúng cái cửa sổ người dùng không nhìn.
  mainWindow.on('focus', () => registerIpcHandlers(tabManager, downloadManager))

  tabManager.onChromeReady(() => mainWindow.show())
  return mainWindow
}

app.whenReady().then(() => {
  electronApp.setAppUserModelId('com.vnsearch.browser')

  app.on('browser-window-created', (_, window) => {
    optimizer.watchWindowShortcuts(window)
  })

  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

/** Mở thêm một cửa sổ. Gọi từ IPC — xem `windowControls`. */
export function openWindow(incognito: boolean): void {
  createWindow(incognito)
}
