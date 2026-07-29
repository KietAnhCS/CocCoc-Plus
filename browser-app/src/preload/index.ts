import { contextBridge, ipcRenderer } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'

/**
 * contextBridge that ket noi voi ipcHandlers.ts o main process qua
 * ipcRenderer.invoke. contextIsolation=true / nodeIntegration=false duoc
 * giu nguyen (yeu cau bao mat co dinh cua do an) - renderer khong bao gio
 * co truy cap truc tiep vao Node.js/Electron API, chi qua cac ham nay.
 */
const browserApi = {
  listTabs: (): Promise<unknown[]> => ipcRenderer.invoke('browser:listTabs'),
  newTab: (url: string): Promise<string> => ipcRenderer.invoke('browser:newTab', url),
  closeTab: (id: string): Promise<void> => ipcRenderer.invoke('browser:closeTab', id),
  switchTab: (id: string): Promise<void> => ipcRenderer.invoke('browser:switchTab', id),
  navigate: (id: string, url: string): Promise<void> => ipcRenderer.invoke('browser:navigate', id, url),
  goBack: (id: string): Promise<void> => ipcRenderer.invoke('browser:goBack', id),
  goForward: (id: string): Promise<void> => ipcRenderer.invoke('browser:goForward', id),
  reload: (id: string): Promise<void> => ipcRenderer.invoke('browser:reload', id),
  onTabUpdate: (callback: (payload: unknown) => void): void => {
    ipcRenderer.on('browser:tabUpdate', (_event, payload) => callback(payload))
  }
}

if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electron', electronAPI)
    contextBridge.exposeInMainWorld('browser', browserApi)
  } catch (error) {
    console.error(error)
  }
} else {
  // @ts-ignore (define in dts)
  window.electron = electronAPI
  // @ts-ignore (define in dts)
  window.browser = browserApi
}
