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
    'win:dragEnd'
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
