import { ipcMain } from 'electron'
import { TabManager } from './tabManager'

const INVOKE_CHANNELS = [
  'browser:listTabs',
  'browser:newTab',
  'browser:closeTab',
  'browser:switchTab',
  'browser:navigate',
  'browser:reload',
  'browser:print',
  'browser:setZoom'
]

const SEND_CHANNELS = ['browser:setPanelWidth', 'browser:setOverlay']

export function registerIpcHandlers(tabManager: TabManager): void {
  for (const channel of INVOKE_CHANNELS) {
    ipcMain.removeHandler(channel)
  }
  for (const channel of SEND_CHANNELS) {
    ipcMain.removeAllListeners(channel)
  }

  ipcMain.handle('browser:listTabs', () => tabManager.snapshot())
  ipcMain.handle('browser:newTab', (_e, url?: string) => tabManager.createTab(url))
  ipcMain.handle('browser:closeTab', (_e, id: string) => tabManager.closeTab(id))
  ipcMain.handle('browser:switchTab', (_e, id: string) => tabManager.switchTab(id))
  ipcMain.handle('browser:navigate', (_e, id: string, url: string) => tabManager.navigate(id, url))
  ipcMain.handle('browser:reload', (_e, id: string) => tabManager.reload(id))
  ipcMain.handle('browser:print', (_e, id: string) => tabManager.print(id))
  ipcMain.handle('browser:setZoom', (_e, id: string, factor: number) =>
    tabManager.setZoom(id, factor)
  )

  ipcMain.on('browser:setPanelWidth', (_e, px: number) => tabManager.setPanelWidth(px))
  ipcMain.on('browser:setOverlay', (_e, active: boolean) => tabManager.setOverlay(active))
}
