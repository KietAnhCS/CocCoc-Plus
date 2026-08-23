import { contextBridge, ipcRenderer } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'
import type { TabsSnapshot } from '../main/tabManager'

const browserApi = {
  listTabs: (): Promise<TabsSnapshot> => ipcRenderer.invoke('browser:listTabs'),
  newTab: (url?: string): Promise<string> => ipcRenderer.invoke('browser:newTab', url),
  closeTab: (id: string): Promise<void> => ipcRenderer.invoke('browser:closeTab', id),
  switchTab: (id: string): Promise<void> => ipcRenderer.invoke('browser:switchTab', id),
  navigate: (id: string, url: string): Promise<void> =>
    ipcRenderer.invoke('browser:navigate', id, url),
  reload: (id: string): Promise<void> => ipcRenderer.invoke('browser:reload', id),
  print: (id: string): Promise<void> => ipcRenderer.invoke('browser:print', id),
  setZoom: (id: string, factor: number): Promise<void> =>
    ipcRenderer.invoke('browser:setZoom', id, factor),

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
  }
}

const windowApi = {
  minimize: (): void => ipcRenderer.send('win:minimize'),
  close: (): void => ipcRenderer.send('win:close'),
  toggleFullScreen: (): void => ipcRenderer.send('win:toggleFullScreen'),
  dragStart: (): void => ipcRenderer.send('win:dragStart'),
  dragEnd: (): void => ipcRenderer.send('win:dragEnd'),
  isMaximized: (): Promise<boolean> => ipcRenderer.invoke('win:isMaximized'),
  toggleMaximize: (): Promise<boolean> => ipcRenderer.invoke('win:toggleMaximize'),
  onMaximizeChanged: (callback: (maximized: boolean) => void): (() => void) => {
    const listener = (_e: unknown, maximized: boolean): void => callback(maximized)
    ipcRenderer.on('win:maximizeChanged', listener)
    return () => ipcRenderer.removeListener('win:maximizeChanged', listener)
  }
}

export type BrowserApi = typeof browserApi
export type WindowApi = typeof windowApi

if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electron', electronAPI)
    contextBridge.exposeInMainWorld('browser', browserApi)
    contextBridge.exposeInMainWorld('win', windowApi)
  } catch (error) {
    console.error(error)
  }
} else {
  const globals = window as unknown as Record<string, unknown>
  globals.electron = electronAPI
  globals.browser = browserApi
  globals.win = windowApi
}
