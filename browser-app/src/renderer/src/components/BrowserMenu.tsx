import { useEffect, useRef, useState } from 'react'
import Popover from './Popover'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useHistoryStore } from '../store/historyStore'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useZoomStore } from '../store/zoomStore'
import { ACCOUNT } from '../lib/account'
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

/**
 * Menu chính của trình duyệt. Các mục chưa có phần xử lý thật (cửa sổ mới,
 * cửa sổ ẩn danh, tìm trong trang…) được để ở trạng thái TẮT kèm chú thích
 * trong `title`, thay vì bấm vào rồi không xảy ra gì — mục xám còn nói thật
 * về khả năng của ứng dụng.
 */
function BrowserMenu({ open, onClose }: BrowserMenuProps): JSX.Element {
  const newTab = useTabStore((s) => s.newTab)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const tabs = useTabStore((s) => s.tabs)
  const openPanel = useSidePanelStore((s) => s.openPanel)
  const clearAll = useHistoryStore((s) => s.clearAll)

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const onExternalPage = !!activeTab && activeTab.url !== HOME_URL

  /** Chạy một lệnh rồi đóng menu — hầu hết các mục đều theo nếp này. */
  function run(action: () => void): void {
    action()
    onClose()
  }

  return (
    <Popover open={open} onClose={onClose} width={304} label="Menu trình duyệt">
      <AccountRow />

      <div className="menu-sep" />

      <MenuItem icon={<VnSearchMark className="h-4 w-4" />} label="Thẻ mới" shortcut="Ctrl+T" onClick={() => run(() => newTab())} />
      <MenuItem
        icon={<WindowIcon className="h-[17px] w-[17px]" />}
        label="Cửa sổ mới"
        shortcut="Ctrl+N"
        disabled
        title="Ứng dụng hiện chỉ chạy một cửa sổ duy nhất."
      />
      <MenuItem
        icon={<IncognitoIcon className="h-[17px] w-[17px]" />}
        label="Cửa sổ ẩn danh mới"
        shortcut="Ctrl+Shift+N"
        disabled
        title="Chưa có phiên duyệt riêng tư tách biệt."
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
        label="Xoá dữ liệu duyệt web"
        shortcut="Ctrl+Shift+Del"
        onClick={() => run(clearAll)}
        title="Dọn hai chồng back/forward của mọi thẻ."
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
        disabled
        title="Chưa cài phần tìm trong trang."
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
        disabled
        title="Chưa có trang cài đặt."
      />
      <MenuItem
        icon={<ExitIcon className="h-[17px] w-[17px]" />}
        label="Thoát"
        onClick={() => window.win.close()}
      />
    </Popover>
  )
}

/* --- Các hàng --- */

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

/** Hàng tài khoản: avatar, tên, trạng thái, và mũi tên gợi ý có menu con. */
function AccountRow(): JSX.Element {
  return (
    <button className="menu-row py-2" title={ACCOUNT.email}>
      <span
        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full
                   bg-gradient-to-br from-rose-500 to-orange-400 text-[12px] font-bold text-white"
      >
        {ACCOUNT.initials}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[13px] font-medium text-ink">{ACCOUNT.name}</span>
        <span className="block truncate text-[12px] text-success">{ACCOUNT.status}</span>
      </span>
      <ChevronRightIcon className="h-4 w-4 shrink-0 text-faint" />
    </button>
  )
}

/**
 * Hàng "Thu phóng": hai nút -/+ kẹp mức phần trăm, bấm vào phần trăm thì về
 * 100%, nút cuối bật/tắt toàn màn hình.
 */
function ZoomRow({ onClose, enabled }: { onClose: () => void; enabled: boolean }): JSX.Element {
  const factor = useZoomStore((s) => s.factor)
  const zoomIn = useZoomStore((s) => s.zoomIn)
  const zoomOut = useZoomStore((s) => s.zoomOut)
  const reset = useZoomStore((s) => s.reset)

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

/**
 * Mục "Nhật ký": rê chuột vào thì mở panel con bên TRÁI (menu đã sát mép
 * phải cửa sổ nên không còn chỗ bên phải).
 *
 * Panel con đóng trễ 180 ms sau khi con trỏ rời đi, nếu không thì đoạn hở
 * giữa hàng menu và panel sẽ làm panel chớp tắt mỗi lần người dùng đưa chuột
 * sang.
 */
function HistoryItem({ onClose }: { onClose: () => void }): JSX.Element {
  const [open, setOpen] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  const recentUrls = useHistoryStore((s) => s.recentUrls)
  const histories = useHistoryStore((s) => s.histories)
  const navigate = useTabStore((s) => s.navigate)

  // Đọc `histories` để danh sách vẽ lại khi lịch sử đổi; giá trị không dùng trực tiếp.
  void histories
  const recent = recentUrls(8).filter((url) => url !== HOME_URL)

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
      <button className="menu-row" aria-haspopup="true" aria-expanded={open}>
        <span className="shrink-0 text-muted">
          <ClockIcon className="h-[17px] w-[17px]" />
        </span>
        <span className="min-w-0 flex-1 truncate">Nhật ký</span>
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
            <p className="px-2.5 pb-2 text-[12px] text-faint">Chưa ghé trang nào trong phiên này.</p>
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
              Chưa đồng bộ thiết bị nào — ứng dụng chưa có máy chủ tài khoản.
            </span>
          </div>
        </div>
      )}
    </div>
  )
}

export default BrowserMenu
