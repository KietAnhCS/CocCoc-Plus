import { ipcMain } from 'electron'
import { TabManager } from './tabManager'

/**
 * Dang ky cac ipcMain.handle tuong ung voi API expose o preload/index.ts,
 * moi handler goi xuong TabManager. Ten channel PHAI khop chinh xac voi
 * cac loi goi ipcRenderer.invoke o preload.
 */
export function registerIpcHandlers(tabManager: TabManager): void {
  // Pull-based: renderer goi ngay sau khi mount de lay danh sach tab HIEN
  // TAI, vi tab dau tien duoc TabManager tao trong constructor - truoc khi
  // renderer kip dang ky nghe browser:tabUpdate - nen su kien push dau
  // tien do bi mat (ipcRenderer.send khong hang doi, chi gui-va-quen).
  ipcMain.handle('browser:listTabs', () => tabManager.listTabs())
  ipcMain.handle('browser:newTab', (_event, url?: string) => tabManager.createTab(url))
  ipcMain.handle('browser:closeTab', (_event, id: string) => tabManager.closeTab(id))
  ipcMain.handle('browser:switchTab', (_event, id: string) => tabManager.switchTab(id))
  ipcMain.handle('browser:navigate', (_event, id: string, url: string) => tabManager.navigate(id, url))
  ipcMain.handle('browser:goBack', (_event, id: string) => tabManager.goBack(id))
  ipcMain.handle('browser:goForward', (_event, id: string) => tabManager.goForward(id))
  ipcMain.handle('browser:reload', (_event, id: string) => tabManager.reload(id))
}
