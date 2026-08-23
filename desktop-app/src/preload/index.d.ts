import type { ElectronAPI } from '@electron-toolkit/preload'
import type { BrowserApi, DownloadsApi, WindowApi } from './index'

declare global {
  interface Window {
    electron: ElectronAPI
    browser: BrowserApi
    downloads: DownloadsApi
    win: WindowApi
  }
}

export {}
