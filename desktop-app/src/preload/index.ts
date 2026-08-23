import { contextBridge, ipcRenderer } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'
import type { TabsSnapshot } from '../main/tabManager'
import type { DownloadInfo } from '../main/downloadManager'

/** Kết quả một lần tìm trong trang, do sự kiện `found-in-page` bắn ra. */
export interface FindResult {
  tabId: string
  activeMatchOrdinal: number
  matches: number
}

const browserApi = {
  listTabs: (): Promise<TabsSnapshot> => ipcRenderer.invoke('browser:listTabs'),
  /** @param incognito mở thẻ dùng session ẩn danh (không ghi gì xuống đĩa) */
  newTab: (url?: string, incognito?: boolean): Promise<string> =>
    ipcRenderer.invoke('browser:newTab', url, incognito),
  closeTab: (id: string): Promise<void> => ipcRenderer.invoke('browser:closeTab', id),
  switchTab: (id: string): Promise<void> => ipcRenderer.invoke('browser:switchTab', id),
  navigate: (id: string, url: string): Promise<void> =>
    ipcRenderer.invoke('browser:navigate', id, url),
  reload: (id: string): Promise<void> => ipcRenderer.invoke('browser:reload', id),
  print: (id: string): Promise<void> => ipcRenderer.invoke('browser:print', id),
  setZoom: (id: string, factor: number): Promise<void> =>
    ipcRenderer.invoke('browser:setZoom', id, factor),

  /**
   * @param findNext `false` cho lần tìm đầu của một từ khoá, `true` cho những
   *                 lần "tiếp theo". Luôn gửi `true` sẽ khiến con trỏ nhảy
   *                 loạn theo từng ký tự người dùng gõ.
   */
  findInPage: (id: string, text: string, forward: boolean, findNext: boolean): Promise<void> =>
    ipcRenderer.invoke('browser:findInPage', id, text, forward, findNext),
  stopFindInPage: (id: string): Promise<void> => ipcRenderer.invoke('browser:stopFindInPage', id),

  setPanelWidth: (px: number): void => ipcRenderer.send('browser:setPanelWidth', px),
  setOverlay: (active: boolean): void => ipcRenderer.send('browser:setOverlay', active),

  onTabsChanged: (callback: (snapshot: TabsSnapshot) => void): (() => void) => {
    const listener = (_e: unknown, snapshot: TabsSnapshot): void => callback(snapshot)
    ipcRenderer.on('browser:tabs', listener)
    return () => ipcRenderer.removeListener('browser:tabs', listener)
  },
  onShortcut: (callback: (name: string) => void): (() => void) => {
    const listener = (_e: unknown, name: string): void => callback(name)
    ipcRenderer.on('browser:shortcut', listener)
    return () => ipcRenderer.removeListener('browser:shortcut', listener)
  },
  onFoundInPage: (callback: (result: FindResult) => void): (() => void) => {
    const listener = (_e: unknown, result: FindResult): void => callback(result)
    ipcRenderer.on('browser:foundInPage', listener)
    return () => ipcRenderer.removeListener('browser:foundInPage', listener)
  }
}

/**
 * Cầu nối tải xuống.
 *
 * <p>Mọi hàm ở đây chỉ RA LỆNH cho tiến trình chính; trạng thái đi ngược lại
 * qua {@link onChanged}. Không có hàm nào trả về trạng thái sau khi tác động —
 * và đó là có chủ ý: tải xuống là một luồng sự kiện bất đồng bộ, nên có đúng
 * MỘT nguồn sự thật (sự kiện `downloads:changed`) thay vì hai đường cập nhật
 * chạy đua nhau.
 */
const downloadsApi = {
  list: (): Promise<DownloadInfo[]> => ipcRenderer.invoke('downloads:list'),
  pause: (id: string): Promise<void> => ipcRenderer.invoke('downloads:pause', id),
  resume: (id: string): Promise<void> => ipcRenderer.invoke('downloads:resume', id),
  cancel: (id: string): Promise<void> => ipcRenderer.invoke('downloads:cancel', id),
  /** @returns chuỗi rỗng khi mở được; thông báo lỗi của hệ điều hành khi không. */
  open: (id: string): Promise<string> => ipcRenderer.invoke('downloads:open', id),
  showInFolder: (id: string): Promise<void> => ipcRenderer.invoke('downloads:showInFolder', id),
  /** Xoá khỏi DANH SÁCH, không xoá tệp trên đĩa. */
  remove: (id: string): Promise<void> => ipcRenderer.invoke('downloads:remove', id),
  clearFinished: (): Promise<void> => ipcRenderer.invoke('downloads:clearFinished'),
  defaultDirectory: (): Promise<string> => ipcRenderer.invoke('downloads:defaultDirectory'),

  onChanged: (callback: (items: DownloadInfo[]) => void): (() => void) => {
    const listener = (_e: unknown, items: DownloadInfo[]): void => callback(items)
    ipcRenderer.on('downloads:changed', listener)
    return () => ipcRenderer.removeListener('downloads:changed', listener)
  }
}

const windowApi = {
  minimize: (): void => ipcRenderer.send('win:minimize'),
  close: (): void => ipcRenderer.send('win:close'),
  toggleFullScreen: (): void => ipcRenderer.send('win:toggleFullScreen'),
  dragStart: (): void => ipcRenderer.send('win:dragStart'),
  dragEnd: (): void => ipcRenderer.send('win:dragEnd'),
  newWindow: (incognito = false): void => ipcRenderer.send('win:newWindow', incognito),
  isMaximized: (): Promise<boolean> => ipcRenderer.invoke('win:isMaximized'),
  toggleMaximize: (): Promise<boolean> => ipcRenderer.invoke('win:toggleMaximize'),
  onMaximizeChanged: (callback: (maximized: boolean) => void): (() => void) => {
    const listener = (_e: unknown, maximized: boolean): void => callback(maximized)
    ipcRenderer.on('win:maximizeChanged', listener)
    return () => ipcRenderer.removeListener('win:maximizeChanged', listener)
  }
}

export type BrowserApi = typeof browserApi
export type DownloadsApi = typeof downloadsApi
export type WindowApi = typeof windowApi

if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electron', electronAPI)
    contextBridge.exposeInMainWorld('browser', browserApi)
    contextBridge.exposeInMainWorld('downloads', downloadsApi)
    contextBridge.exposeInMainWorld('win', windowApi)
  } catch (error) {
    console.error(error)
  }
} else {
  const globals = window as unknown as Record<string, unknown>
  globals.electron = electronAPI
  globals.browser = browserApi
  globals.downloads = downloadsApi
  globals.win = windowApi
}
