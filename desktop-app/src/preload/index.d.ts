import type { ElectronAPI } from '@electron-toolkit/preload'
import type { BrowserApi, WindowApi } from './index'

declare global {
  interface Window {
    electron: ElectronAPI
    browser: BrowserApi
    win: WindowApi
  }
}

export {}
