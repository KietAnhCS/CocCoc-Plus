import {
  BrowserWindow,
  WebContentsView,
  session,
  type Input,
  type Session,
  type WebContents
} from 'electron'
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
  /** Thẻ ẩn danh: dùng session riêng, không ghi lịch sử. */
  incognito: boolean
}

/**
 * Session dùng cho các thẻ ẩn danh.
 *
 * VÌ SAO `fromPartition` KHÔNG CÓ TIỀN TỐ `persist:`. Đó chính là điều làm nên
 * chế độ ẩn danh: một partition không có tiền tố `persist:` sống hoàn toàn
 * trong bộ nhớ — cookie, localStorage, IndexedDB, bộ đệm HTTP đều biến mất khi
 * tiến trình kết thúc, và KHÔNG BAO GIỜ chạm tới đĩa.
 *
 * Thêm `persist:` vào đây là hỏng toàn bộ lời hứa, và nó hỏng ÂM THẦM: mọi thứ
 * vẫn chạy đúng, chỉ có điều dữ liệu duyệt web nằm lại trên máy sau khi người
 * dùng đóng thẻ. Không bài test nào bắt được điều đó nếu không đi đọc đĩa.
 *
 * Một partition DUY NHẤT cho mọi thẻ ẩn danh, đúng như Chrome: các thẻ ẩn danh
 * chia sẻ phiên đăng nhập với nhau (mở hai thẻ ẩn danh để đăng nhập hai tài
 * khoản là không được), nhưng tách hoàn toàn khỏi phiên thường.
 */
const INCOGNITO_PARTITION = 'vnsearch-incognito'

function incognitoSession(): Session {
  return session.fromPartition(INCOGNITO_PARTITION)
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
    if (key === 'f') return 'findInPage'
    if (key === 'j') return 'downloads'
    if (key === 'h') return 'history'
    if (key === 'n') return 'newWindow'
  }
  if (input.control && input.shift && !input.alt) {
    if (key === 'n') return 'newIncognitoTab'
    if (key === 'delete') return 'clearBrowsingData'
  }
  if (key === 'escape' && !input.control && !input.alt) {
    // Escape đóng ô tìm trong trang. Chuyển tiếp cả khi tiêu điểm đang ở nội
    // dung web: người dùng vừa gõ vào ô tìm, rồi bấm vào trang để đọc kết quả,
    // và Escape lúc đó vẫn phải đóng ô đó.
    return 'escape'
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

  /**
   * Được gọi mỗi khi một session mới xuất hiện, để nơi khác gắn thêm phần
   * lắng nghe (hiện tại: DownloadManager).
   *
   * Kiểu callback thay vì tham chiếu thẳng tới DownloadManager: TabManager
   * không cần biết ai quan tâm tới session, và nhờ vậy nó vẫn kiểm thử được
   * mà không phải dựng một trình quản lý tải xuống.
   */
  private onSessionCreated: ((session: Session, incognito: boolean) => void) | null = null

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

  /**
   * Đăng ký người quan tâm tới session, và gọi ngay cho những session ĐÃ tồn
   * tại.
   *
   * Lần gọi ngay lập tức là bắt buộc: TabManager tạo thẻ đầu tiên trong hàm
   * dựng, tức là trước khi ai kịp đăng ký. Thiếu nó thì mọi lượt tải từ thẻ
   * đầu tiên biến mất khỏi giao diện — và chỉ thẻ đầu tiên, nên lỗi trông
   * hoàn toàn ngẫu nhiên.
   */
  onSession(callback: (session: Session, incognito: boolean) => void): void {
    this.onSessionCreated = callback
    callback(session.defaultSession, false)
  }

  /** Đang có thẻ ẩn danh nào mở không — giao diện dùng để đổi màu thanh công cụ. */
  hasIncognitoTab(): boolean {
    return [...this.tabs.values()].some((entry) => entry.state.incognito)
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

  createTab(url: string = HOME_URL, incognito = false): string {
    const id = `tab-${this.nextTabId++}`
    this.tabs.set(id, {
      state: { id, url: HOME_URL, title: 'Tab mới', loading: false, incognito },
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

  /**
   * Tìm trong trang.
   *
   * <p>`findNext: false` cho lần tìm ĐẦU TIÊN của một từ khoá, `true` cho
   * những lần bấm "tiếp theo". Gửi sai cờ này là lỗi tinh vi nhất của API
   * findInPage: luôn gửi `true` thì mỗi ký tự người dùng gõ sẽ được coi là
   * "tìm tiếp", và con trỏ nhảy loạn qua trang thay vì bám vào kết quả đầu.
   *
   * <p>Kết quả không trả về theo lối gọi-nhận mà bắn ra sự kiện
   * `found-in-page` — xem {@link #bindViewEvents}.
   */
  findInPage(id: string, text: string, options: { forward: boolean; findNext: boolean }): void {
    const contents = this.tabs.get(id)?.view?.webContents
    if (!contents) {
      return
    }
    if (!text) {
      contents.stopFindInPage('clearSelection')
      return
    }
    contents.findInPage(text, { forward: options.forward, findNext: options.findNext })
  }

  /**
   * Dừng tìm và BỎ ĐÁNH DẤU.
   *
   * <p>`clearSelection` chứ không `keepSelection`: giữ lại vùng chọn nghĩa là
   * trang vẫn còn một đoạn bôi vàng sau khi người dùng đã đóng ô tìm — trông
   * như một lỗi hiển thị.
   */
  stopFindInPage(id: string): void {
    this.tabs.get(id)?.view?.webContents.stopFindInPage('clearSelection')
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
    const incognito = entry.state.incognito
    const tabSession = incognito ? incognitoSession() : session.defaultSession
    this.onSessionCreated?.(tabSession, incognito)

    const view = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        // Gán session TẠI ĐÂY, lúc tạo view. Đây là thời điểm DUY NHẤT gán
        // được: `webContents.session` chỉ đọc, nên một thẻ đã tạo không đổi
        // được sang session khác. Hệ quả là "chuyển thẻ này sang ẩn danh"
        // không phải một thao tác — phải mở thẻ mới.
        session: tabSession
      }
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
    // Kết quả tìm trong trang. Điều hướng sang trang khác thì mọi kết quả cũ
    // mất hiệu lực, nên phải báo về giao diện để nó xoá bộ đếm — nếu không,
    // ô tìm sẽ hiện "3/17" cho một trang không còn chữ nào khớp.
    wc.on('found-in-page', (_e, result) => {
      if (this.chromeView.webContents.isDestroyed()) {
        return
      }
      this.chromeView.webContents.send('browser:foundInPage', {
        tabId: entry.state.id,
        activeMatchOrdinal: result.activeMatchOrdinal,
        matches: result.matches
      })
    })

    // Liên kết mở trong thẻ mới GIỮ NGUYÊN chế độ ẩn danh của thẻ gốc.
    // Không giữ thì một cú Ctrl+click trong thẻ ẩn danh sẽ lặng lẽ mở một thẻ
    // thường — và người dùng vẫn tin rằng mình đang duyệt riêng tư.
    wc.setWindowOpenHandler(({ url }) => {
      this.createTab(url, entry.state.incognito)
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
