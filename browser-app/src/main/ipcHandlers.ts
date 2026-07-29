import { ipcMain } from 'electron'
import { TabManager } from './tabManager'

/**
 * Dang ky cac ipcMain.handle tuong ung voi API expose o preload/index.ts,
 * moi handler goi xuong TabManager. Ten channel PHAI khop chinh xac voi
 * cac loi goi ipcRenderer.invoke o preload.
 */
export function registerIpcHandlers(tabManager: TabManager): void {
  ipcMain.handle('browser:newTab', (_event, url?: string) => tabManager.createTab(url))
  ipcMain.handle('browser:closeTab', (_event, id: string) => tabManager.closeTab(id))
  ipcMain.handle('browser:switchTab', (_event, id: string) => tabManager.switchTab(id))
  ipcMain.handle('browser:navigate', (_event, id: string, url: string) => tabManager.navigate(id, url))
  ipcMain.handle('browser:goBack', (_event, id: string) => tabManager.goBack(id))
  ipcMain.handle('browser:goForward', (_event, id: string) => tabManager.goForward(id))
  ipcMain.handle('browser:reload', (_event, id: string) => tabManager.reload(id))
}
