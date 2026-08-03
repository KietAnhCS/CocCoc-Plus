import { app, BrowserWindow } from 'electron'
import { electronApp, optimizer } from '@electron-toolkit/utils'
import { TabManager } from './tabManager'
import { registerIpcHandlers } from './ipcHandlers'
import { registerWindowControls } from './windowControls'

/**
 * Cửa sổ chính (1280x800), chạy frameless để thanh tab nằm ngang hàng với
 * ba nút điều khiển cửa sổ như một trình duyệt thật — phần kéo/thu/phóng
 * do windowControls.ts đảm nhiệm. Nội dung thực sự (vỏ trình duyệt + các
 * tab) do TabManager quản lý qua WebContentsView, không load trực tiếp vào
 * mainWindow.webContents (TabManager tự gọi window.show() khi vỏ trình
 * duyệt tải xong lần đầu — xem comment trong tabManager.ts).
 */
function createWindow(): void {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    // Dưới ~900px thanh tab và ô địa chỉ bắt đầu chen nhau, nên chặn luôn.
    minWidth: 900,
    minHeight: 560,
    show: false,
    frame: false,
    autoHideMenuBar: true,
    // Cùng tông với nền thanh tab ở chế độ tối (--c-chrome) để không loé
    // trắng lúc mở — vỏ mặc định chạy nền tối, xem store/themeStore.ts.
    backgroundColor: '#111318'
  })

  const tabManager = new TabManager(mainWindow)
  registerIpcHandlers(tabManager)
  registerWindowControls(mainWindow)
}

app.whenReady().then(() => {
  electronApp.setAppUserModelId('com.vnsearch.browser')

  app.on('browser-window-created', (_, window) => {
    optimizer.watchWindowShortcuts(window)
  })

  createWindow()

  app.on('activate', function () {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
