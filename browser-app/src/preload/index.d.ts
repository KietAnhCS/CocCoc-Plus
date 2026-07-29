import { ElectronAPI } from '@electron-toolkit/preload'

interface BrowserApi {
  newTab: (url: string) => void
  closeTab: (id: string) => void
  switchTab: (id: string) => void
  navigate: (id: string, url: string) => void
  goBack: (id: string) => void
  goForward: (id: string) => void
  reload: (id: string) => void
  onTabUpdate: (callback: (payload: unknown) => void) => void
}

declare global {
  interface Window {
    electron: ElectronAPI
    browser: BrowserApi
  }
}
