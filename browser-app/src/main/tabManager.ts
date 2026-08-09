import { BrowserWindow, WebContentsView, type Input, type WebContents } from 'electron'
import { join } from 'path'
import { is } from '@electron-toolkit/utils'
import { HOME_URL, clampZoomFactor, resolveNavigable } from './urlPolicy'

const CHROME_HEIGHT = 122
const SIDE_RAIL_WIDTH = 48

export { HOME_URL }

export interface TabState {
  id: string
  url: string
  title: string
  loading: boolean
}

export interface TabsSnapshot {
  tabs: TabState[]
  activeTabId: string | null
}

interface TabEntry {
  state: TabState
  view: WebContentsView | null
}

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

export class TabManager {
  private readonly window: BrowserWindow
  private readonly chromeView: WebContentsView
  private readonly tabs = new Map<string, TabEntry>()
  private order: string[] = []
  private activeTabId: string | null = null
  private nextTabId = 1
  private panelWidth = 0
  private overlay = false

  constructor(window: BrowserWindow) {
    this.window = window
    this.chromeView = new WebContentsView({
      webPreferences: {
        preload: join(__dirname, '../preload/index.js'),
        contextIsolation: true,
        nodeIntegration: false,
        // BẬT sandbox. Trước đây là `false`, và đó là mặc định nguy hiểm nhất
        // trong tệp này: khung nhìn NÀY là khung duy nhất có preload, tức là
        // khung duy nhất chạm được tới IPC. Tắt sandbox nghĩa là nếu có một lỗ
        // hổng XSS trong giao diện, mã của kẻ tấn công chạy trong một tiến
        // trình có toàn quyền Node — đọc được tệp, mở được tiến trình con.
        //
        // Không có gì phải đánh đổi: preload ở đây chỉ dùng `ipcRenderer` và
        // `contextBridge`, cả hai đều có sẵn trong preload đã sandbox. Đã kiểm
        // tra cả `@electron-toolkit/preload` — nó chỉ đụng tới `electron` và
        // `process.platform`/`versions`/`env`, đều được phép.
        sandbox: true
      }
    })

    // Vỏ giao diện KHÔNG được rời khỏi trang của chính nó.
    //
    // Đây là khung có preload. Nếu một liên kết trong giao diện (hoặc một lỗi
    // lập trình) khiến nó điều hướng sang một trang ngoài, thì trang ngoài đó
    // thừa hưởng luôn cầu nối IPC. Nội dung web phải sống trong các tab, và
    // các tab thì không có preload.
    this.chromeView.webContents.on('will-navigate', (event, url) => {
      const current = this.chromeView.webContents.getURL()
      if (url !== current) {
        event.preventDefault()
        this.createTab(url)
      }
    })
    // `target="_blank"` trong vỏ giao diện cũng vậy: mở thành tab, không mở
    // thành một cửa sổ mới nằm ngoài mọi ràng buộc ở trên.
    this.chromeView.webContents.setWindowOpenHandler(({ url }) => {
      this.createTab(url)
      return { action: 'deny' }
    })
    this.window.contentView.addChildView(this.chromeView)
    this.layoutChrome()

    if (is.dev && process.env['ELECTRON_RENDERER_URL']) {
      this.chromeView.webContents.loadURL(process.env['ELECTRON_RENDERER_URL'])
    } else {
      this.chromeView.webContents.loadFile(join(__dirname, '../renderer/index.html'))
    }

    this.window.on('resize', () => this.layoutAll())

    this.createTab(HOME_URL)
  }

  get chromeContents(): WebContents {
    return this.chromeView.webContents
  }

  onChromeReady(callback: () => void): void {
    this.chromeView.webContents.once('did-finish-load', callback)
  }

  snapshot(): TabsSnapshot {
    return {
      tabs: this.order.map((id) => this.tabs.get(id)!.state),
      activeTabId: this.activeTabId
    }
  }

  createTab(url: string = HOME_URL): string {
    const id = `tab-${this.nextTabId++}`
    this.tabs.set(id, {
      state: { id, url: HOME_URL, title: 'Tab mới', loading: false },
      view: null
    })
    this.order.push(id)
    this.activeTabId = id

    if (url !== HOME_URL) {
      this.navigate(id, url)
    } else {
      this.layoutAll()
      this.emit()
    }
    return id
  }

  closeTab(id: string): void {
    const entry = this.tabs.get(id)
    if (!entry) {
      return
    }

    this.destroyView(entry)
    this.tabs.delete(id)
    const index = this.order.indexOf(id)
    this.order = this.order.filter((tabId) => tabId !== id)

    if (this.order.length === 0) {
      this.activeTabId = null
      this.createTab(HOME_URL)
      return
    }

    if (this.activeTabId === id) {
      this.activeTabId = this.order[Math.min(index, this.order.length - 1)]
    }
    this.layoutAll()
    this.emit()
  }

  switchTab(id: string): void {
    if (!this.tabs.has(id)) {
      return
    }
    this.activeTabId = id
    this.layoutAll()
    this.emit()
  }

  navigate(id: string, url: string): void {
    const entry = this.tabs.get(id)
    if (!entry) {
      return
    }

    if (url === HOME_URL) {
      this.destroyView(entry)
      entry.state = { ...entry.state, url: HOME_URL, title: 'Tab mới', loading: false }
      this.layoutAll()
      this.emit()
      return
    }

    // Danh sách CHO PHÉP http/https — xem `urlPolicy.ts` về lỗ hổng `file://`
    // mà phép kiểm tra này vá. URL đến đây từ BA nguồn và không nguồn nào đáng
    // tin: thanh địa chỉ, `window.open` của trang đang mở, và liên kết bị chặn
    // ở vỏ giao diện.
    const target = resolveNavigable(url)
    if (target === null) {
      entry.state = { ...entry.state, loading: false, title: 'Địa chỉ không được phép mở' }
      this.emit()
      return
    }
    if (target === HOME_URL) {
      this.navigate(id, HOME_URL)
      return
    }

    const view = entry.view ?? this.createView(entry)
    entry.state = { ...entry.state, url: target, loading: true }
    this.layoutAll()
    this.emit()

    view.webContents.loadURL(target).catch(() => {
      entry.state = { ...entry.state, loading: false, title: 'Không mở được trang' }
      this.emit()
    })
  }

  reload(id: string): void {
    this.tabs.get(id)?.view?.webContents.reload()
  }

  print(id: string): void {
    this.tabs.get(id)?.view?.webContents.print({}, () => undefined)
  }

  setZoom(id: string, factor: number): void {
    // Ép về dải hợp lệ: giá trị này đến từ renderer qua IPC nên có thể là NaN,
    // 0 hay số âm — và `setZoomFactor(0)` làm nội dung biến mất hẳn, không có
    // cách nào phục hồi bằng giao diện.
    this.tabs.get(id)?.view?.webContents.setZoomFactor(clampZoomFactor(factor))
  }

  setPanelWidth(px: number): void {
    this.panelWidth = Math.max(0, Math.round(px))
    this.layoutAll()
  }

  setOverlay(active: boolean): void {
    this.overlay = active
    this.layoutAll()
  }

  private layoutChrome(): void {
    const { width, height } = this.window.getContentBounds()
    this.chromeView.setBounds({ x: 0, y: 0, width, height })
  }

  private layoutTabView(view: WebContentsView): void {
    const { width, height } = this.window.getContentBounds()
    view.setBounds({
      x: 0,
      y: CHROME_HEIGHT,
      width: Math.max(0, width - SIDE_RAIL_WIDTH - this.panelWidth),
      height: Math.max(0, height - CHROME_HEIGHT)
    })
  }

  private layoutAll(): void {
    this.layoutChrome()
    for (const [id, entry] of this.tabs) {
      if (!entry.view) {
        continue
      }
      const visible = id === this.activeTabId && !this.overlay
      entry.view.setVisible(visible)
      if (visible) {
        this.layoutTabView(entry.view)
      }
    }
  }

  private createView(entry: TabEntry): WebContentsView {
    const view = new WebContentsView({
      webPreferences: { contextIsolation: true, nodeIntegration: false }
    })
    entry.view = view
    this.window.contentView.addChildView(view)
    this.layoutTabView(view)
    this.bindViewEvents(entry, view)
    this.forwardShortcuts(view.webContents)
    return view
  }

  private destroyView(entry: TabEntry): void {
    if (!entry.view) {
      return
    }
    this.window.contentView.removeChildView(entry.view)
    entry.view.webContents.close()
    entry.view = null
  }

  private bindViewEvents(entry: TabEntry, view: WebContentsView): void {
    const wc = view.webContents
    const sync = (patch: Partial<TabState>): void => {
      entry.state = { ...entry.state, ...patch }
      this.emit()
    }

    wc.on('did-start-loading', () => sync({ loading: true }))
    wc.on('did-stop-loading', () => sync({ loading: false }))
    wc.on('page-title-updated', (_e, title) => sync({ title }))
    wc.on('did-navigate', (_e, url) => sync({ url }))
    wc.on('did-navigate-in-page', (_e, url) => sync({ url }))
    wc.setWindowOpenHandler(({ url }) => {
      this.createTab(url)
      return { action: 'deny' }
    })
  }

  private forwardShortcuts(wc: WebContents): void {
    wc.on('before-input-event', (event, input) => {
      if (input.type !== 'keyDown') {
        return
      }
      const name = shortcutName(input)
      if (!name) {
        return
      }
      event.preventDefault()
      this.chromeView.webContents.send('browser:shortcut', name)
    })
  }

  private emit(): void {
    if (this.chromeView.webContents.isDestroyed()) {
      return
    }
    this.chromeView.webContents.send('browser:tabs', this.snapshot())
  }
}
