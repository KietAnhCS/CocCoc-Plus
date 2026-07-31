import { BrowserWindow, Rectangle, ipcMain, screen } from 'electron'

/**
 * Cửa sổ chạy ở chế độ frameless (`frame: false`) để thanh tab nằm ngang
 * hàng với ba nút thu nhỏ / phóng to / đóng — đúng cách Chrome, Edge, Cốc
 * Cốc bố trí. Đổi lại, phần việc mà khung cửa sổ của hệ điều hành vốn lo
 * hộ (kéo cửa sổ đi, bấm ba nút, nháy đúp để phóng to) nay phải tự làm.
 *
 * Vì sao KHÔNG dùng `-webkit-app-region: drag`? Giao diện vỏ trình duyệt
 * nằm trong một WebContentsView con chứ không phải webContents gốc của
 * BrowserWindow, mà vùng kéo bằng CSS chỉ được hệ thống hiểu ở webContents
 * gốc. Nên phần kéo được làm thủ công: renderer báo "bắt đầu kéo", main
 * process bám theo con trỏ chuột cho tới khi nhả tay.
 */
export function registerWindowControls(window: BrowserWindow): void {
  const maximizer = new Maximizer(window)

  ipcMain.handle('window:minimize', () => window.minimize())
  ipcMain.handle('window:toggleMaximize', () => maximizer.toggle())
  ipcMain.handle('window:close', () => window.close())
  ipcMain.handle('window:isMaximized', () => maximizer.isMaximized())

  registerManualDrag(window, maximizer)
}

/**
 * Phóng to "thủ công": tự đặt bounds bằng đúng vùng làm việc của màn hình
 * thay vì gọi `window.maximize()`.
 *
 * Lý do: trên Windows, một cửa sổ frameless khi phóng to bị hệ điều hành
 * cho tràn ra ngoài mép màn hình đúng bằng bề dày viền kéo giãn vô hình
 * (~7 px mỗi bên). Với cửa sổ có khung thật thì không ai thấy, nhưng ở đây
 * nó xén mất phần trên của thanh tab và ba nút điều khiển. Tự đặt bounds
 * thì cửa sổ khít vùng làm việc, không cần bù trừ ở giao diện.
 */
class Maximizer {
  private readonly window: BrowserWindow
  /** Bounds trước khi phóng to; khác null nghĩa là đang ở trạng thái phóng to. */
  private restoreBounds: Rectangle | null = null

  constructor(window: BrowserWindow) {
    this.window = window

    // Win + ↑ vẫn gọi phóng to "thật" của hệ điều hành — chuyển ngay về cách
    // của mình để không bao giờ rơi vào trạng thái bị xén mép.
    window.on('maximize', () => {
      window.unmaximize()
      this.maximize()
    })

    // Người dùng tự kéo mép cửa sổ thì coi như đã thoát trạng thái phóng to
    // ('resized' chỉ bắn khi người dùng kéo, không bắn khi mình setBounds).
    window.on('resized', () => {
      if (this.restoreBounds) {
        this.restoreBounds = null
        this.emit()
      }
    })
  }

  isMaximized(): boolean {
    return this.restoreBounds !== null
  }

  toggle(): boolean {
    if (this.restoreBounds) {
      this.restore()
    } else {
      this.maximize()
    }
    return this.isMaximized()
  }

  maximize(): void {
    if (this.restoreBounds) {
      return
    }
    const bounds = this.window.getBounds()
    this.restoreBounds = bounds
    this.window.setBounds(screen.getDisplayMatching(bounds).workArea)
    this.emit()
  }

  restore(): void {
    if (!this.restoreBounds) {
      return
    }
    this.window.setBounds(this.restoreBounds)
    this.restoreBounds = null
    this.emit()
  }

  /** Nút phóng to phải tự đổi hình mỗi khi trạng thái đổi, kể cả do phím tắt. */
  private emit(): void {
    if (!this.window.isDestroyed()) {
      this.window.webContents.send('window:maximizeChanged', this.isMaximized())
    }
  }
}

/**
 * Kéo cửa sổ thủ công: mỗi 8 ms lấy vị trí con trỏ và dời cửa sổ sao cho
 * điểm người dùng "nắm" luôn nằm dưới con trỏ. Dùng setInterval thay vì
 * nghe mousemove ở renderer vì khi kéo nhanh, con trỏ hay vượt ra ngoài
 * cửa sổ và chuỗi mousemove sẽ đứt.
 */
function registerManualDrag(window: BrowserWindow, maximizer: Maximizer): void {
  let timer: NodeJS.Timeout | null = null

  const stop = (): void => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  ipcMain.on('window:dragStart', () => {
    stop()

    // Kéo một cửa sổ đang phóng to thì nó phải thu lại rồi mới đi theo tay —
    // hệt như trình duyệt thật. Giữ nguyên tỉ lệ hoành độ để cửa sổ không
    // "nhảy" ra khỏi con trỏ.
    if (maximizer.isMaximized()) {
      const cursorBefore = screen.getCursorScreenPoint()
      const maximizedBounds = window.getBounds()
      const ratio = (cursorBefore.x - maximizedBounds.x) / Math.max(1, maximizedBounds.width)
      maximizer.restore()
      const restored = window.getBounds()
      window.setBounds({
        ...restored,
        x: Math.round(cursorBefore.x - ratio * restored.width),
        y: cursorBefore.y - 16
      })
    }

    const start = screen.getCursorScreenPoint()
    const bounds = window.getBounds()
    const offsetX = start.x - bounds.x
    const offsetY = start.y - bounds.y

    timer = setInterval(() => {
      if (window.isDestroyed()) {
        stop()
        return
      }
      const cursor = screen.getCursorScreenPoint()
      const current = window.getBounds()
      window.setBounds({
        ...current,
        x: cursor.x - offsetX,
        y: cursor.y - offsetY
      })
    }, 8)
  })

  ipcMain.on('window:dragEnd', stop)
  window.on('closed', stop)
}
