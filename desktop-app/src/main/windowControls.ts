import { BrowserWindow, ipcMain, screen, type WebContents } from 'electron'

const DRAG_TICK_MS = 16

export function registerWindowControls(window: BrowserWindow, chrome: WebContents): void {
  let dragTimer: NodeJS.Timeout | null = null

  function stopDrag(): void {
    if (dragTimer) {
      clearInterval(dragTimer)
      dragTimer = null
    }
  }

  function restoreUnderCursor(cursorX: number): void {
    const maximized = window.getBounds()
    const fraction = maximized.width > 0 ? (cursorX - maximized.x) / maximized.width : 0.5
    window.unmaximize()
    const restored = window.getBounds()
    window.setPosition(Math.round(cursorX - restored.width * fraction), maximized.y)
  }

  function startDrag(): void {
    stopDrag()
    const cursor = screen.getCursorScreenPoint()
    if (window.isMaximized()) {
      restoreUnderCursor(cursor.x)
    }

    const bounds = window.getBounds()
    const offsetX = cursor.x - bounds.x
    const offsetY = cursor.y - bounds.y

    dragTimer = setInterval(() => {
      if (window.isDestroyed()) {
        stopDrag()
        return
      }
      const point = screen.getCursorScreenPoint()
      window.setPosition(point.x - offsetX, point.y - offsetY)
    }, DRAG_TICK_MS)
  }

  function notifyMaximizeChanged(): void {
    if (!chrome.isDestroyed()) {
      chrome.send('win:maximizeChanged', window.isMaximized())
    }
  }

  for (const channel of [
    'win:minimize',
    'win:close',
    'win:toggleFullScreen',
    'win:dragStart',
    'win:dragEnd',
    'win:newWindow'
  ]) {
    ipcMain.removeAllListeners(channel)
  }
  ipcMain.removeHandler('win:isMaximized')
  ipcMain.removeHandler('win:toggleMaximize')

  ipcMain.on('win:minimize', () => window.minimize())
  ipcMain.on('win:close', () => window.close())
  ipcMain.on('win:toggleFullScreen', () => window.setFullScreen(!window.isFullScreen()))
  ipcMain.on('win:dragStart', startDrag)
  ipcMain.on('win:dragEnd', stopDrag)

  /**
   * Mở thêm một cửa sổ (thường hoặc ẩn danh).
   *
   * `import()` động chứ không `import` ở đầu tệp: `index.ts` đã nhập tệp này,
   * nên một lời nhập tĩnh theo chiều ngược lại tạo ra vòng nhập. Với ESM thì
   * vòng đó không lỗi ngay — nó khiến `openWindow` là `undefined` vào đúng lúc
   * dòng này chạy, và triệu chứng là nút "Cửa sổ mới" không làm gì cả.
   */
  ipcMain.on('win:newWindow', (_event, incognito: boolean) => {
    void import('./index').then((module) => module.openWindow(incognito === true))
  })

  ipcMain.handle('win:isMaximized', () => window.isMaximized())
  ipcMain.handle('win:toggleMaximize', () => {
    if (window.isMaximized()) {
      window.unmaximize()
    } else {
      window.maximize()
    }
    return window.isMaximized()
  })

  window.on('maximize', notifyMaximizeChanged)
  window.on('unmaximize', notifyMaximizeChanged)
  window.on('closed', stopDrag)
}
