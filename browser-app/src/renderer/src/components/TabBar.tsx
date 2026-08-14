import { useEffect, useRef, useState, type JSX, type MouseEvent as ReactMouseEvent } from 'react'
import { useTabStore, HOME_URL, type TabInfo } from '../store/tabStore'
import {
  CloseIcon,
  PlusIcon,
  SpinnerIcon,
  VnSearchMark,
  WinCloseIcon,
  WinMaximizeIcon,
  WinMinimizeIcon,
  WinRestoreIcon
} from './icons'
import { siteGradient, siteInitial } from '../lib/site'

const DRAG_THRESHOLD_PX = 4

function TabBar(): JSX.Element {
  const tabs = useTabStore((state) => state.tabs)
  const activeTabId = useTabStore((state) => state.activeTabId)
  const switchTab = useTabStore((state) => state.switchTab)
  const closeTab = useTabStore((state) => state.closeTab)
  const newTab = useTabStore((state) => state.newTab)
  const drag = useWindowDrag()

  return (
    <div className="flex h-10 shrink-0 items-stretch bg-chrome">
      <div className="flex min-w-0 flex-1 items-end gap-px overflow-hidden pt-1.5 pl-2">
        {tabs.map((tab) => (
          <Tab
            key={tab.id}
            tab={tab}
            active={tab.id === activeTabId}
            single={tabs.length === 1}
            onSelect={() => switchTab(tab.id)}
            onClose={() => closeTab(tab.id)}
          />
        ))}

        <button
          onClick={() => newTab()}
          className="mb-1 ml-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg
                     text-muted transition-colors hover:bg-surface/70 hover:text-ink
                     focus-visible:ring-2 focus-visible:ring-brand/60 focus-visible:outline-none"
          aria-label="Tab mới"
          title="Tab mới (trang chủ VnSearch)"
        >
          <PlusIcon className="h-4 w-4" />
        </button>

        <div className="-mt-1.5 h-10 min-w-[24px] flex-1" {...drag} />
      </div>

      <WindowControls />
    </div>
  )
}

interface TabProps {
  tab: TabInfo
  active: boolean
  single: boolean
  onSelect: () => void
  onClose: () => void
}

function Tab({ tab, active, single, onSelect, onClose }: TabProps): JSX.Element {
  const isHome = tab.url === HOME_URL
  const label = tab.loading ? 'Đang tải…' : isHome ? 'Trang chủ VnSearch' : tab.title || tab.url

  return (
    <div
      onClick={onSelect}
      onAuxClick={(event) => {
        if (event.button === 1) {
          event.preventDefault()
          onClose()
        }
      }}
      title={isHome ? 'Trang chủ VnSearch' : `${tab.title}\n${tab.url}`}
      className={
        'group relative flex h-[34px] min-w-0 max-w-[240px] flex-1 cursor-default items-center ' +
        'gap-2 rounded-t-[10px] pr-1.5 pl-2.5 text-[13px] transition-colors duration-150 ' +
        (active
          ? 'z-10 bg-surface text-ink shadow-tab'
          : 'text-muted hover:bg-surface/45 hover:text-ink')
      }
    >
      {/* Vạch xanh trên đỉnh tab đang mở. Nền sáng hơn đã đủ để phân biệt,
          nhưng thêm một vạch màu thì mắt tìm ra tab hiện hành ngay cả khi
          hàng tab đã chật và chênh lệch nền chỉ còn vài pixel. */}
      {active && (
        <span
          aria-hidden="true"
          className="absolute inset-x-2 top-0 h-[2px] animate-scale-in rounded-full bg-linear-to-r from-brand to-accent"
        />
      )}

      {!active && !single && (
        <span className="absolute top-1/2 right-0 h-4 w-px -translate-y-1/2 bg-line group-hover:opacity-0" />
      )}

      <span className="flex h-4 w-4 shrink-0 items-center justify-center">
        {tab.loading ? (
          <SpinnerIcon className="h-3.5 w-3.5 text-brand" />
        ) : isHome ? (
          <VnSearchMark className="h-4 w-4 text-muted" />
        ) : (
          <span
            className="flex h-4 w-4 items-center justify-center rounded-[5px] text-[9px] font-bold text-white ring-1 ring-inset ring-white/25"
            style={{ background: siteGradient(tab.url) }}
          >
            {siteInitial(tab.url)}
          </span>
        )}
      </span>

      <span className="relative min-w-0 flex-1 overflow-hidden whitespace-nowrap">
        {label}
        <span
          className={
            'pointer-events-none absolute inset-y-0 right-0 w-8 bg-linear-to-l to-transparent ' +
            (active ? 'from-surface' : 'from-chrome')
          }
        />
      </span>

      <button
        onClick={(event) => {
          event.stopPropagation()
          onClose()
        }}
        className={
          'flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-muted ' +
          'transition hover:bg-danger/15 hover:text-danger focus-visible:ring-2 ' +
          'focus-visible:ring-brand/60 focus-visible:outline-none ' +
          (active ? 'opacity-100' : 'opacity-0 group-hover:opacity-100')
        }
        aria-label={`Đóng tab ${label}`}
        title="Đóng tab"
      >
        <CloseIcon className="h-3 w-3" strokeWidth={2.2} />
      </button>
    </div>
  )
}

function WindowControls(): JSX.Element {
  const [maximized, setMaximized] = useState(false)

  useEffect(() => {
    window.win.isMaximized().then(setMaximized)
    return window.win.onMaximizeChanged(setMaximized)
  }, [])

  const buttonClass =
    'flex h-10 w-[46px] shrink-0 items-center justify-center text-muted transition-colors ' +
    'hover:bg-black/[0.06] hover:text-ink focus-visible:outline-none dark:hover:bg-white/10'

  return (
    <div className="flex shrink-0 items-start">
      <button
        onClick={() => window.win.minimize()}
        className={buttonClass}
        aria-label="Thu nhỏ"
        title="Thu nhỏ"
      >
        <WinMinimizeIcon className="h-[10px] w-[10px]" />
      </button>
      <button
        onClick={() => window.win.toggleMaximize().then(setMaximized)}
        className={buttonClass}
        aria-label={maximized ? 'Khôi phục cửa sổ' : 'Phóng to'}
        title={maximized ? 'Khôi phục cửa sổ' : 'Phóng to'}
      >
        {maximized ? (
          <WinRestoreIcon className="h-[10px] w-[10px]" />
        ) : (
          <WinMaximizeIcon className="h-[10px] w-[10px]" />
        )}
      </button>
      <button
        onClick={() => window.win.close()}
        className={buttonClass + ' hover:bg-[#c42b1c]! hover:text-white!'}
        aria-label="Đóng cửa sổ"
        title="Đóng"
      >
        <WinCloseIcon className="h-[10px] w-[10px]" />
      </button>
    </div>
  )
}

interface WindowDragHandlers {
  onMouseDown: (event: ReactMouseEvent) => void
  onDoubleClick: () => void
}

function useWindowDrag(): WindowDragHandlers {
  const dragging = useRef(false)

  useEffect(() => {
    const stop = (): void => {
      if (dragging.current) {
        dragging.current = false
        window.win.dragEnd()
      }
    }
    window.addEventListener('mouseup', stop)
    window.addEventListener('blur', stop)
    return () => {
      window.removeEventListener('mouseup', stop)
      window.removeEventListener('blur', stop)
    }
  }, [])

  function onMouseDown(event: ReactMouseEvent): void {
    if (event.button !== 0) {
      return
    }
    const startX = event.screenX
    const startY = event.screenY

    const cleanup = (): void => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', cleanup)
    }

    function onMove(move: MouseEvent): void {
      const travelled = Math.abs(move.screenX - startX) + Math.abs(move.screenY - startY)
      if (travelled < DRAG_THRESHOLD_PX) {
        return
      }
      cleanup()
      dragging.current = true
      window.win.dragStart()
    }

    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', cleanup)
  }

  return { onMouseDown, onDoubleClick: () => window.win.toggleMaximize() }
}

export default TabBar
