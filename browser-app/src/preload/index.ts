import { contextBridge } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'

/**
 * PHASE 1: contextBridge khung, contextIsolation=true / nodeIntegration=false
 * (yeu cau bao mat co dinh cua do an). Cac ham browser.* se duoc noi voi
 * ipcRenderer.invoke that trong PHASE 7, tuong ung ipcHandlers.ts o main.
 */
const browserApi = {
  newTab: (_url: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:newTab', url)
  },
  closeTab: (_id: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:closeTab', id)
  },
  switchTab: (_id: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:switchTab', id)
  },
  navigate: (_id: string, _url: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:navigate', id, url)
  },
  goBack: (_id: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:goBack', id)
  },
  goForward: (_id: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:goForward', id)
  },
  reload: (_id: string): void => {
    // TODO (PHASE 7): ipcRenderer.invoke('browser:reload', id)
  },
  onTabUpdate: (_callback: (payload: unknown) => void): void => {
    // TODO (PHASE 7): ipcRenderer.on('browser:tabUpdate', ...)
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
