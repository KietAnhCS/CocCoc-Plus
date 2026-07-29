import { app, BrowserWindow } from 'electron'
import { electronApp, optimizer } from '@electron-toolkit/utils'
import { TabManager } from './tabManager'
import { registerIpcHandlers } from './ipcHandlers'

/**
 * Cua so chinh (1280x800). Noi dung thuc su (chrome UI + tab) do
 * TabManager quan ly qua WebContentsView, khong load truc tiep vao
 * mainWindow.webContents nua (TabManager tu goi window.show() khi chrome
 * view tai xong lan dau - xem comment trong tabManager.ts).
 */
function createWindow(): void {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    show: false,
    autoHideMenuBar: true
  })

  const tabManager = new TabManager(mainWindow)
  registerIpcHandlers(tabManager)
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
