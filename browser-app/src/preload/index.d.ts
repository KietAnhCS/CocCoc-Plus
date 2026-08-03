import { ElectronAPI } from '@electron-toolkit/preload'

interface BrowserApi {
  listTabs: () => Promise<unknown[]>
  newTab: (url: string) => Promise<string>
  closeTab: (id: string) => Promise<void>
  switchTab: (id: string) => Promise<void>
  navigate: (id: string, url: string) => Promise<void>
  goBack: (id: string) => Promise<void>
  goForward: (id: string) => Promise<void>
  reload: (id: string) => Promise<void>
  setPanelWidth: (px: number) => Promise<void>
  setOverlay: (active: boolean) => Promise<void>
  setZoom: (id: string, factor: number) => Promise<void>
  print: (id: string) => Promise<void>
  onTabUpdate: (callback: (payload: unknown) => void) => void
  onShortcut: (callback: (name: string) => void) => void
}

interface WindowApi {
  minimize: () => Promise<void>
  toggleMaximize: () => Promise<boolean>
  close: () => Promise<void>
  isMaximized: () => Promise<boolean>
  toggleFullScreen: () => Promise<boolean>
  dragStart: () => void
  dragEnd: () => void
  onMaximizeChanged: (callback: (maximized: boolean) => void) => void
}

declare global {
  interface Window {
    electron: ElectronAPI
    browser: BrowserApi
    win: WindowApi
  }
}
