import { app, BrowserWindow } from 'electron'
import { electronApp, optimizer } from '@electron-toolkit/utils'
import { TabManager } from './tabManager'
import { registerIpcHandlers } from './ipcHandler'
import { registerWindowControls } from './windowControls'

function createWindow(): void {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 560,
    show: false,
    frame: false,
    autoHideMenuBar: true,
    backgroundColor: '#111318'
  })

  const tabManager = new TabManager(mainWindow)
  registerIpcHandlers(tabManager)
  registerWindowControls(mainWindow, tabManager.chromeContents)

  tabManager.onChromeReady(() => mainWindow.show())
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
