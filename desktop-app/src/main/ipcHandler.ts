import { ipcMain } from 'electron'
import { TabManager } from './tabManager'
import { DownloadManager } from './downloadManager'

/**
 * Danh sách kênh IPC, khai TƯỜNG MINH.
 *
 * <p>Hai mảng này không phải tài liệu — chúng được dùng để GỠ đăng ký cũ
 * trước khi đăng ký mới. Không gỡ thì mở cửa sổ thứ hai sẽ ném
 * "Attempted to register a second handler for ...", và ứng dụng chết ngay lúc
 * người dùng bấm "Cửa sổ mới".
 */
const INVOKE_CHANNELS = [
  'browser:listTabs',
  'browser:newTab',
  'browser:closeTab',
  'browser:switchTab',
  'browser:navigate',
  'browser:reload',
  'browser:print',
  'browser:setZoom',
  'browser:findInPage',
  'browser:stopFindInPage',

  'downloads:list',
  'downloads:pause',
  'downloads:resume',
  'downloads:cancel',
  'downloads:open',
  'downloads:showInFolder',
  'downloads:remove',
  'downloads:clearFinished',
  'downloads:defaultDirectory'
]

const SEND_CHANNELS = ['browser:setPanelWidth', 'browser:setOverlay']

export function registerIpcHandlers(
  tabManager: TabManager,
  downloadManager: DownloadManager
): void {
  for (const channel of INVOKE_CHANNELS) {
    ipcMain.removeHandler(channel)
  }
  for (const channel of SEND_CHANNELS) {
    ipcMain.removeAllListeners(channel)
  }

  // ------------------------------------------------------------- thẻ

  ipcMain.handle('browser:listTabs', () => tabManager.snapshot())
  ipcMain.handle('browser:newTab', (_e, url?: string, incognito?: boolean) =>
    tabManager.createTab(url, incognito === true)
  )
  ipcMain.handle('browser:closeTab', (_e, id: string) => tabManager.closeTab(id))
  ipcMain.handle('browser:switchTab', (_e, id: string) => tabManager.switchTab(id))
  ipcMain.handle('browser:navigate', (_e, id: string, url: string) => tabManager.navigate(id, url))
  ipcMain.handle('browser:reload', (_e, id: string) => tabManager.reload(id))
  ipcMain.handle('browser:print', (_e, id: string) => tabManager.print(id))
  ipcMain.handle('browser:setZoom', (_e, id: string, factor: number) =>
    tabManager.setZoom(id, factor)
  )

  // -------------------------------------------------- tìm trong trang

  ipcMain.handle(
    'browser:findInPage',
    (_e, id: string, text: string, forward: boolean, findNext: boolean) =>
      tabManager.findInPage(id, text, { forward, findNext })
  )
  ipcMain.handle('browser:stopFindInPage', (_e, id: string) => tabManager.stopFindInPage(id))

  ipcMain.on('browser:setPanelWidth', (_e, px: number) => tabManager.setPanelWidth(px))
  ipcMain.on('browser:setOverlay', (_e, active: boolean) => tabManager.setOverlay(active))

  // ------------------------------------------------------- tải xuống

  ipcMain.handle('downloads:list', () => downloadManager.list())
  ipcMain.handle('downloads:pause', (_e, id: string) => downloadManager.pause(id))
  ipcMain.handle('downloads:resume', (_e, id: string) => downloadManager.resume(id))
  ipcMain.handle('downloads:cancel', (_e, id: string) => downloadManager.cancel(id))
  // Trả về chuỗi lỗi của hệ điều hành (rỗng = thành công) để giao diện hiện
  // đúng lý do: tệp đã bị xoá, hoặc không có ứng dụng nào mở được kiểu tệp đó.
  ipcMain.handle('downloads:open', (_e, id: string) => downloadManager.openFile(id))
  ipcMain.handle('downloads:showInFolder', (_e, id: string) => downloadManager.showInFolder(id))
  ipcMain.handle('downloads:remove', (_e, id: string) => downloadManager.remove(id))
  ipcMain.handle('downloads:clearFinished', () => downloadManager.clearFinished())
  ipcMain.handle('downloads:defaultDirectory', () => downloadManager.defaultDirectory())
}
