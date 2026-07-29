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
  onTabUpdate: (callback: (payload: unknown) => void) => void
}

declare global {
  interface Window {
    electron: ElectronAPI
    browser: BrowserApi
  }
}
