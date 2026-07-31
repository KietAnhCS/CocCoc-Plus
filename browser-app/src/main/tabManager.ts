import { BrowserWindow, WebContentsView, type Input, type WebContents } from 'electron'
import { join } from 'path'
import { is } from '@electron-toolkit/utils'

/**
 * Chiều cao (px) vùng TabBar + AddressBar ở renderer: 40px thanh tab + 48px
 * thanh công cụ (kể cả đường kẻ dưới). PHẢI khớp với App.tsx. Các
 * WebContentsView của tab (trang ngoài) được đặt bên dưới vùng này để không
 * che mất thanh công cụ, giống cách Chrome/Cốc Cốc bố trí.
 */
const CHROME_HEIGHT = 88

/** URL noi bo dai dien cho "trang chu tim kiem" - khong tao WebContentsView rieng,
 *  chrome view (chinh la React app) tu ve SearchHomePage/SearchResultList. */
export const HOME_URL = 'vnsearch://home'

/**
 * Bảng phím tắt phía main process. Phải khớp với `shortcutFromEvent` bên
 * renderer (lib/useBrowserShortcuts.ts) — hai tiến trình không dùng chung
 * mã được, nên chỗ nào sửa thì sửa cả hai.
 */
function shortcutName(input: Input): string | null {
  const key = input.key.toLowerCase()

  if (input.control && !input.alt && !input.shift) {
    if (key === 't') return 'newTab'
    if (key === 'w') return 'closeTab'
    if (key === 'l') return 'focusOmnibox'
    if (key === 'd') return 'bookmark'
    if (key === 'r') return 'reload'
  }
  if (input.alt && !input.control) {
    if (key === 'd') return 'focusOmnibox'
    if (key === 'arrowleft') return 'back'
    if (key === 'arrowright') return 'forward'
    if (key === 'home') return 'home'
  }
  if (key === 'f5' && !input.control && !input.alt) {
    return 'reload'
  }
  return null
}

export interface TabState {
  id: string
  url: string
  title: string
  loading: boolean
  canGoBack: boolean
  canGoForward: boolean
}

interface TabEntry {
  state: TabState
  view: WebContentsView | null // null = dang o trang home noi bo, chua co WebContentsView rieng
}

/**
 * Quan ly tab bang WebContentsView (Electron 30+), KHONG dung <webview> tag
 * (deprecated) hay BrowserView (dang bi loai bo dan).
 *
 * Kien truc: mot "chromeView" duy nhat (tai renderer React app - TabBar,
 * AddressBar, SearchHomePage/SearchResultList) phu kin toan bo cua so va
 * luon hien thi. Khi mot tab dieu huong sang URL thuc (khac HOME_URL), mot
 * WebContentsView RIENG duoc tao va chong len phia tren chromeView, chi
 * phu vung noi dung ben duoi CHROME_HEIGHT — nho vay thanh tab/dia chi cua
 * chromeView van luon nhin thay duoc du tab dang hien trang ngoai hay
 * trang home. Khi tab quay ve HOME_URL, WebContentsView rieng bi go bo va
 * chromeView (dang render san SearchHomePage cho tab do qua state React)
 * lo ra tro lai.
 */
export class TabManager {
  private readonly window: BrowserWindow
  private readonly chromeView: WebContentsView
  private readonly tabs = new Map<string, TabEntry>()
  private activeTabId: string | null = null
  private nextTabId = 1

  constructor(window: BrowserWindow) {
    this.window = window
    this.chromeView = new WebContentsView({
      webPreferences: {
        preload: join(__dirname, '../preload/index.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false
      }
    })
    this.window.contentView.addChildView(this.chromeView)
    this.layoutChrome()

    // window.show() thuong duoc goi qua su kien 'ready-to-show' cua BrowserWindow,
    // nhung su kien do gan voi webContents CUA CHINH mainWindow - ma o day mainWindow
    // khong bao gio tu load noi dung (chi co child view chromeView lam viec do), nen
    // 'ready-to-show' se khong bao gio ban. Thay vao do, hien cua so ngay khi chromeView
    // (noi dung thuc su nguoi dung se thay) tai xong lan dau.
    this.chromeView.webContents.once('did-finish-load', () => this.window.show())

    if (is.dev && process.env['ELECTRON_RENDERER_URL']) {
      this.chromeView.webContents.loadURL(process.env['ELECTRON_RENDERER_URL'])
    } else {
      this.chromeView.webContents.loadFile(join(__dirname, '../renderer/index.html'))
    }

    this.window.on('resize', () => this.layoutAll())

    // Luon co it nhat 1 tab (trang home) khi khoi tao.
    this.createTab(HOME_URL)
  }

  private layoutChrome(): void {
    const { width, height } = this.window.getContentBounds()
    this.chromeView.setBounds({ x: 0, y: 0, width, height })
  }

  private layoutTabView(view: WebContentsView): void {
    const { width, height } = this.window.getContentBounds()
    view.setBounds({ x: 0, y: CHROME_HEIGHT, width, height: Math.max(0, height - CHROME_HEIGHT) })
  }

  private layoutAll(): void {
    this.layoutChrome()
    const active = this.activeTabId ? this.tabs.get(this.activeTabId) : undefined
    if (active?.view) {
      this.layoutTabView(active.view)
    }
  }

  listTabs(): TabState[] {
    return Array.from(this.tabs.values()).map((t) => t.state)
  }

  createTab(url: string = HOME_URL): string {
    const id = `tab-${this.nextTabId++}`
    const entry: TabEntry = {
      state: {
        id,
        url,
        title: url === HOME_URL ? 'Trang chu' : url,
        loading: false,
        canGoBack: false,
        canGoForward: false
      },
      view: null
    }
    this.tabs.set(id, entry)
    this.switchTab(id)
    if (url !== HOME_URL) {
      this.navigate(id, url)
    } else {
      this.emit(entry.state)
    }
    return id
  }

  closeTab(id: string): void {
    const entry = this.tabs.get(id)
    if (!entry) {
      return
    }
    if (entry.view) {
      this.window.contentView.removeChildView(entry.view)
      entry.view.webContents.close()
    }
    this.tabs.delete(id)

    if (this.activeTabId === id) {
      this.activeTabId = null
      const nextId = this.tabs.keys().next().value
      if (nextId) {
        this.switchTab(nextId)
      } else {
        // Khong con tab nao -> mo lai 1 tab home moi de khong bao gio "trang trui" cua so.
        this.createTab(HOME_URL)
      }
    }
  }

  switchTab(id: string): void {
    const entry = this.tabs.get(id)
    if (!entry) {
      return
    }

    if (this.activeTabId && this.activeTabId !== id) {
      const prevEntry = this.tabs.get(this.activeTabId)
      if (prevEntry?.view) {
        this.window.contentView.removeChildView(prevEntry.view)
      }
    }

    this.activeTabId = id
    if (entry.view) {
      this.window.contentView.addChildView(entry.view)
      this.layoutTabView(entry.view)
    }
    this.emit(entry.state)
  }

  navigate(id: string, url: string): void {
    const entry = this.tabs.get(id)
    if (!entry) {
      return
    }

    if (url === HOME_URL) {
      if (entry.view) {
        this.window.contentView.removeChildView(entry.view)
        entry.view.webContents.close()
        entry.view = null
      }
      entry.state = {
        id,
        url,
        title: 'Trang chu',
        loading: false,
        canGoBack: false,
        canGoForward: false
      }
      this.emit(entry.state)
      return
    }

    if (!entry.view) {
      entry.view = this.createExternalView(id)
      if (this.activeTabId === id) {
        this.window.contentView.addChildView(entry.view)
        this.layoutTabView(entry.view)
      }
    }
    entry.view.webContents.loadURL(url)
  }

  goBack(id: string): void {
    const wc = this.tabs.get(id)?.view?.webContents
    if (wc?.canGoBack()) {
      wc.goBack()
    }
  }

  goForward(id: string): void {
    const wc = this.tabs.get(id)?.view?.webContents
    if (wc?.canGoForward()) {
      wc.goForward()
    }
  }

  reload(id: string): void {
    this.tabs.get(id)?.view?.webContents.reload()
  }

  private createExternalView(id: string): WebContentsView {
    const view = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true
      }
    })
    const wc = view.webContents

    const pushUpdate = (): void => {
      const entry = this.tabs.get(id)
      if (!entry) {
        return
      }
      entry.state = {
        id,
        url: wc.getURL(),
        title: wc.getTitle() || wc.getURL(),
        loading: wc.isLoading(),
        canGoBack: wc.canGoBack(),
        canGoForward: wc.canGoForward()
      }
      this.emit(entry.state)
    }

    wc.on('did-start-loading', pushUpdate)
    wc.on('did-stop-loading', pushUpdate)
    wc.on('page-title-updated', pushUpdate)
    wc.on('did-navigate', pushUpdate)
    wc.on('did-navigate-in-page', pushUpdate)

    this.forwardShortcuts(wc)

    // Chan popup va bien target=_blank thanh tab moi thay vi mo cua so Electron rieng.
    wc.setWindowOpenHandler(({ url: targetUrl }) => {
      this.createTab(targetUrl)
      return { action: 'deny' }
    })

    return view
  }

  /**
   * Khi con trỏ đang ở một trang ngoài, phím bấm đi thẳng vào WebContentsView
   * của trang đó và vỏ trình duyệt không hề hay biết — Ctrl+T sẽ "chết".
   * Bắt lấy các tổ hợp của trình duyệt ở đây rồi chuyển tiếp về vỏ để chỉ có
   * một chỗ duy nhất thực thi lệnh (xem renderer lib/useBrowserShortcuts.ts).
   */
  private forwardShortcuts(wc: WebContents): void {
    wc.on('before-input-event', (event, input) => {
      if (input.type !== 'keyDown') {
        return
      }
      const name = shortcutName(input)
      if (name) {
        event.preventDefault()
        if (!this.chromeView.webContents.isDestroyed()) {
          this.chromeView.webContents.send('browser:shortcut', name)
        }
      }
    })
  }

  /** Chrome view (React app) la noi DUY NHAT hien thi TabBar/AddressBar nen no
   *  la dich duy nhat nhan su kien cap nhat trang thai tab. */
  private emit(state: TabState): void {
    if (!this.chromeView.webContents.isDestroyed()) {
      this.chromeView.webContents.send('browser:tabUpdate', state)
    }
  }
}
