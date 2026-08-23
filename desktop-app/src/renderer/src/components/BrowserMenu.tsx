import { useEffect, useRef, useState, type JSX } from 'react'
import { useShallow } from 'zustand/shallow'
import Popover from './Popover'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useHistoryStore } from '../store/historyStore'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useZoomStore, zoomFactorOf } from '../store/zoomStore'
import { useSessionStore } from '../store/sessionStore'
import { usePagesStore } from '../store/pagesStore'
import { useFindStore } from '../store/findStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import {
  ChevronRightIcon,
  ClockIcon,
  DeviceIcon,
  DownloadIcon,
  ExitIcon,
  FullscreenIcon,
  HelpIcon,
  IncognitoIcon,
  MinusIcon,
  PlusIcon,
  PrintIcon,
  PuzzleIcon,
  SearchIcon,
  SettingsIcon,
  StarIcon,
  TranslateIcon,
  TrashIcon,
  VnSearchMark,
  WindowIcon
} from './icons'

interface BrowserMenuProps {
  open: boolean
  onClose: () => void
}

function BrowserMenu({ open, onClose }: BrowserMenuProps): JSX.Element {
  const newTab = useTabStore((s) => s.newTab)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const tabs = useTabStore((s) => s.tabs)
  const openPanel = useSidePanelStore((s) => s.openPanel)
  const closePanel = useSidePanelStore((s) => s.closePanel)
  const moTrang = usePagesStore((s) => s.mo)
  const moOTim = useFindStore((s) => s.moO)
  const clearAll = useHistoryStore((s) => s.clearAll)

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const onExternalPage = !!activeTab && activeTab.url !== HOME_URL

  function run(action: () => void): void {
    action()
    onClose()
  }

  return (
    <Popover open={open} onClose={onClose} width={304} label="Menu trình duyệt">
      <AccountRow />

      <div className="menu-sep" />

      <MenuItem
        icon={<VnSearchMark className="h-4 w-4" />}
        label="Thẻ mới"
        shortcut="Ctrl+T"
        onClick={() => run(() => newTab())}
      />
      <MenuItem
        icon={<WindowIcon className="h-[17px] w-[17px]" />}
        label="Cửa sổ mới"
        shortcut="Ctrl+N"
        onClick={() => run(() => window.win.newWindow(false))}
      />
      <MenuItem
        icon={<IncognitoIcon className="h-[17px] w-[17px]" />}
        label="Thẻ ẩn danh mới"
        shortcut="Ctrl+Shift+N"
        onClick={() => run(() => newTab(undefined, true))}
        title="Session riêng, không lưu gì xuống đĩa và không ghi lịch sử."
      />

      <div className="menu-sep" />

      <HistoryItem onClose={onClose} />
      <MenuItem
        icon={<DownloadIcon className="h-[17px] w-[17px]" />}
        label="Tải xuống"
        shortcut="Ctrl+J"
        onClick={() => run(() => openPanel('downloads'))}
      />
      <MenuItem
        icon={<StarIcon className="h-[17px] w-[17px]" />}
        label="Dấu trang"
        onClick={() => run(() => openPanel('bookmarks'))}
      />

      <div className="menu-sep" />

      <MenuItem
        icon={<PuzzleIcon className="h-[17px] w-[17px]" />}
        label="Tiện ích mở rộng"
        disabled
        title="Chưa nạp tiện ích Chrome."
      />
      <MenuItem
        icon={<TrashIcon className="h-[17px] w-[17px]" />}
        label="Xoá dữ liệu duyệt web…"
        shortcut="Ctrl+Shift+Del"
        onClick={() =>
          run(() => {
            // Dọn hai chồng back/forward tại chỗ, rồi mở trang Nhật ký — nơi
            // có hộp xác nhận cho phần xoá dữ liệu TRÊN MÁY CHỦ. Xoá thẳng từ
            // menu là xoá vĩnh viễn mà không hỏi, và menu là chỗ dễ bấm nhầm.
            clearAll()
            closePanel()
            moTrang('history')
          })
        }
        title="Mở Nhật ký để chọn khoảng thời gian cần xoá."
      />

      <div className="menu-sep" />

      <ZoomRow onClose={onClose} enabled={onExternalPage} />

      <div className="menu-sep" />

      <MenuItem
        icon={<PrintIcon className="h-[17px] w-[17px]" />}
        label="In…"
        shortcut="Ctrl+P"
        disabled={!onExternalPage}
        title={onExternalPage ? undefined : 'Chỉ in được trang web đang mở trong thẻ.'}
        onClick={() => run(() => activeTabId && window.browser.print(activeTabId))}
      />
      <MenuItem
        icon={<TranslateIcon className="h-[17px] w-[17px]" />}
        label="Dịch…"
        disabled
        title="Chưa nối với dịch vụ dịch nào."
      />
      <MenuItem
        icon={<SearchIcon className="h-[17px] w-[17px]" />}
        label="Tìm kiếm trong trang…"
        shortcut="Ctrl+F"
        disabled={!onExternalPage}
        title={onExternalPage ? undefined : 'Chỉ tìm được trong trang web đang mở.'}
        onClick={() => run(moOTim)}
      />

      <div className="menu-sep" />

      <MenuItem
        icon={<HelpIcon className="h-[17px] w-[17px]" />}
        label="Trợ giúp"
        disabled
        title="Xem README.md của dự án."
      />
      <MenuItem
        icon={<SettingsIcon className="h-[17px] w-[17px]" />}
        label="Cài đặt"
        onClick={() =>
          run(() => {
            closePanel()
            moTrang('settings')
          })
        }
      />
      <MenuItem
        icon={<ExitIcon className="h-[17px] w-[17px]" />}
        label="Thoát"
        onClick={() => window.win.close()}
      />
    </Popover>
  )
}

interface MenuItemProps {
  icon: JSX.Element
  label: string
  shortcut?: string
  onClick?: () => void
  disabled?: boolean
  title?: string
}

function MenuItem({ icon, label, shortcut, onClick, disabled, title }: MenuItemProps): JSX.Element {
  return (
    <button onClick={onClick} disabled={disabled} className="menu-row" title={title}>
      <span className="shrink-0 text-muted">{icon}</span>
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {shortcut && <span className="shrink-0 text-[12px] text-faint">{shortcut}</span>}
    </button>
  )
}

/**
 * Hàng tài khoản đầu menu.
 *
 * Đọc phiên THẬT thay vì một hằng số cứng. Trước đây hàng này luôn hiện
 * "admin — Đã đăng nhập" cho mọi người, kể cả khi chưa ai đăng nhập: menu nói
 * một đằng, máy chủ áp một nẻo.
 */
function AccountRow(): JSX.Element {
  const user = useSessionStore((state) => state.user)
  const isAdmin = user?.role === 'ADMIN'

  return (
    <button
      className="menu-row py-2"
      title={user ? 'Quản lý tài khoản ở nút avatar trên thanh công cụ' : 'Chưa đăng nhập'}
    >
      <span
        className={
          'flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[12px] font-bold ' +
          (user
            ? isAdmin
              ? 'bg-linear-to-br from-emerald-500 to-lime-400 text-white'
              : 'bg-linear-to-br from-teal-500 to-emerald-400 text-white'
            : 'border border-line bg-raised text-muted')
        }
      >
        {user ? user.username.slice(0, 2).toUpperCase() : '—'}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[13px] font-medium text-ink">
          {user ? user.username : 'Chưa đăng nhập'}
        </span>
        <span className={'block truncate text-[12px] ' + (isAdmin ? 'text-success' : 'text-muted')}>
          {user ? (isAdmin ? 'Quản trị viên' : 'Người dùng') : 'Bấm avatar để đăng nhập'}
        </span>
      </span>
      <ChevronRightIcon className="h-4 w-4 shrink-0 text-faint" />
    </button>
  )
}

function ZoomRow({ onClose, enabled }: { onClose: () => void; enabled: boolean }): JSX.Element {
  const activeTabId = useTabStore((state) => state.activeTabId)
  const factor = useZoomStore((state) => zoomFactorOf(state.factors, activeTabId))
  const zoomIn = useZoomStore((state) => state.zoomIn)
  const zoomOut = useZoomStore((state) => state.zoomOut)
  const reset = useZoomStore((state) => state.reset)

  const button =
    'flex h-7 w-7 items-center justify-center rounded-full text-muted transition-colors ' +
    'hover:bg-line hover:text-ink focus-visible:outline-none focus-visible:ring-2 ' +
    'focus-visible:ring-brand/60 disabled:pointer-events-none disabled:text-faint/50'

  return (
    <div
      className="flex items-center gap-3 px-2.5 py-1.5 text-[13px] text-ink"
      title={enabled ? undefined : 'Chỉ thu phóng được trang web mở trong thẻ.'}
    >
      <span className="flex-1">Thu phóng</span>
      <button onClick={zoomOut} disabled={!enabled} className={button} aria-label="Thu nhỏ">
        <MinusIcon className="h-4 w-4" />
      </button>
      <button
        onClick={reset}
        disabled={!enabled}
        className="w-12 shrink-0 rounded-md py-0.5 text-center text-[13px] tabular-nums text-ink
                   transition-colors hover:bg-line focus-visible:outline-none
                   disabled:pointer-events-none disabled:text-faint/60"
        aria-label="Đặt lại thu phóng về 100%"
        title="Đặt lại về 100%"
      >
        {Math.round(factor * 100)}%
      </button>
      <button onClick={zoomIn} disabled={!enabled} className={button} aria-label="Phóng to">
        <PlusIcon className="h-4 w-4" />
      </button>
      <button
        onClick={() => {
          window.win.toggleFullScreen()
          onClose()
        }}
        className={button}
        aria-label="Toàn màn hình"
        title="Toàn màn hình (F11)"
      >
        <FullscreenIcon className="h-4 w-4" />
      </button>
    </div>
  )
}

function HistoryItem({ onClose }: { onClose: () => void }): JSX.Element {
  const [open, setOpen] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  const navigate = useTabStore((state) => state.navigate)
  const recent = useHistoryStore(
    useShallow((state) => state.recentUrls(8).filter((url) => url !== HOME_URL))
  )

  useEffect(() => () => window.clearTimeout(timer.current), [])

  function show(): void {
    window.clearTimeout(timer.current)
    setOpen(true)
  }

  function hideSoon(): void {
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setOpen(false), 180)
  }

  return (
    <div className="relative" onMouseEnter={show} onMouseLeave={hideSoon}>
      <button
        className="menu-row"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => {
          usePagesStore.getState().mo('history')
          onClose()
        }}
        title="Mở trang Nhật ký đầy đủ (Ctrl+H)"
      >
        <span className="shrink-0 text-muted">
          <ClockIcon className="h-[17px] w-[17px]" />
        </span>
        <span className="min-w-0 flex-1 truncate">Nhật ký</span>
        <span className="shrink-0 text-[12px] text-faint">Ctrl+H</span>
        <ChevronRightIcon className="h-4 w-4 shrink-0 text-faint" />
      </button>

      {open && (
        <div
          className="absolute right-[calc(100%+8px)] top-0 z-50 w-[290px] animate-scale-in rounded-xl
                     border border-line bg-surface p-1.5 shadow-pop"
          role="menu"
          aria-label="Nhật ký"
        >
          <p className="px-2.5 py-1.5 text-[12px] font-semibold uppercase tracking-wide text-faint">
            Thẻ gần đây
          </p>

          {recent.length === 0 ? (
            <p className="px-2.5 pb-2 text-[12px] text-faint">
              Chưa ghé trang nào trong phiên này.
            </p>
          ) : (
            recent.map((url) => (
              <button
                key={url}
                onClick={() => {
                  navigate(url)
                  onClose()
                }}
                className="menu-row"
                title={url}
              >
                <span
                  className="flex h-4 w-4 shrink-0 items-center justify-center rounded-[5px]
                             text-[9px] font-bold text-white"
                  style={{ background: siteGradient(url) }}
                >
                  {siteInitial(url)}
                </span>
                <span className="min-w-0 flex-1 truncate">{hostOf(url)}</span>
              </button>
            ))
          )}

          <div className="menu-sep" />

          <p className="px-2.5 py-1.5 text-[12px] font-semibold uppercase tracking-wide text-faint">
            Các thiết bị của bạn
          </p>
          <div className="flex items-start gap-3 px-2.5 pb-2 text-[12px] text-faint">
            <DeviceIcon className="mt-0.5 h-4 w-4 shrink-0" />
            <span className="leading-relaxed">
              Lịch sử được đồng bộ theo tài khoản. Mở <b>Nhật ký</b> để xem đầy đủ và tìm kiếm trong
              đó.
            </span>
          </div>
        </div>
      )}
    </div>
  )
}

export default BrowserMenu
