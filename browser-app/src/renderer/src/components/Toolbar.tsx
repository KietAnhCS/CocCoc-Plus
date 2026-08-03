import { useState } from 'react'
import NavigationButtons from './NavigationButtons'
import AddressBar from './AddressBar'
import BrowserMenu from './BrowserMenu'
import Popover from './Popover'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useTabStore } from '../store/tabStore'
import { ACCOUNT } from '../lib/account'
import {
  DownloadIcon,
  MenuIcon,
  PuzzleIcon,
  SparkleIcon,
  SplitScreenIcon
} from './icons'

/**
 * Thanh công cụ: cụm điều hướng bên trái, ô địa chỉ ở giữa, và cụm hành
 * động bên phải (tiện ích, Hỏi AI, chia đôi màn hình, tải xuống, tài khoản,
 * menu). Chiều cao cố định 48px — cùng với thanh tab và thanh dấu trang tạo
 * thành CHROME_HEIGHT bên main/tabManager.ts.
 */
function Toolbar(): JSX.Element {
  const [extensionsOpen, setExtensionsOpen] = useState(false)
  const [splitOpen, setSplitOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)

  const openPanel = useSidePanelStore((s) => s.openPanel)
  const togglePanel = useSidePanelStore((s) => s.togglePanel)
  const panelOpen = useSidePanelStore((s) => s.open)
  const tabCount = useTabStore((s) => s.tabs.length)

  return (
    <div className="flex h-12 shrink-0 items-center gap-1 bg-surface px-2.5">
      <NavigationButtons />
      <div className="mx-1 h-5 w-px shrink-0 bg-line" />
      <AddressBar />

      <div className="ml-0.5 flex shrink-0 items-center gap-0.5">
        <div className="relative">
          <button
            onClick={() => setExtensionsOpen((v) => !v)}
            className={'icon-btn ' + (extensionsOpen ? 'bg-raised text-ink' : '')}
            aria-label="Tiện ích mở rộng"
            title="Tiện ích mở rộng"
            aria-expanded={extensionsOpen}
          >
            <PuzzleIcon className="h-[18px] w-[18px]" />
          </button>
          <Popover
            open={extensionsOpen}
            onClose={() => setExtensionsOpen(false)}
            label="Tiện ích mở rộng"
            width={270}
          >
            <PopoverNote
              title="Chưa cài tiện ích nào"
              body="Trình duyệt này chạy WebContentsView thuần, chưa nạp tiện ích Chrome."
            />
          </Popover>
        </div>

        {/* Nút "Hỏi AI" dạng viên thuốc — điểm nhấn màu duy nhất trên thanh. */}
        <button
          onClick={() => openPanel('ai')}
          className={
            'ml-0.5 flex h-8 shrink-0 items-center gap-1.5 rounded-full border px-3 text-[13px] ' +
            'font-medium transition-colors duration-150 focus-visible:outline-none ' +
            'focus-visible:ring-2 focus-visible:ring-brand/60 ' +
            (panelOpen === 'ai'
              ? 'border-brand/50 bg-brand-soft text-brand'
              : 'border-line text-muted hover:border-brand/40 hover:bg-brand-soft hover:text-brand')
          }
          title="Hỏi AI về trang đang xem"
        >
          <SparkleIcon className="h-4 w-4" />
          Hỏi AI
        </button>

        <div className="relative">
          <button
            onClick={() => setSplitOpen((v) => !v)}
            className={'icon-btn ' + (splitOpen ? 'bg-raised text-ink' : '')}
            aria-label="Chia đôi màn hình"
            title="Chia đôi màn hình"
            aria-expanded={splitOpen}
          >
            <SplitScreenIcon className="h-[18px] w-[18px]" />
          </button>
          <Popover
            open={splitOpen}
            onClose={() => setSplitOpen(false)}
            label="Chia đôi màn hình"
            width={280}
          >
            <PopoverNote
              title="Chia đôi màn hình"
              body={
                tabCount < 2
                  ? 'Cần ít nhất hai thẻ đang mở để xếp cạnh nhau.'
                  : 'Phần xếp hai thẻ cạnh nhau chưa được cài đặt ở bản này.'
              }
            />
          </Popover>
        </div>

        <button
          onClick={() => togglePanel('downloads')}
          className={'icon-btn ' + (panelOpen === 'downloads' ? 'bg-raised text-ink' : '')}
          aria-label="Tải xuống"
          title="Tải xuống"
        >
          <DownloadIcon className="h-[18px] w-[18px]" />
        </button>

        <div className="relative">
          <button
            onClick={() => setAccountOpen((v) => !v)}
            className="ml-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full
                       bg-gradient-to-br from-rose-500 to-orange-400 text-[11px] font-bold text-white
                       transition hover:brightness-110 focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-brand/60"
            aria-label={`Tài khoản ${ACCOUNT.name}`}
            title={ACCOUNT.name}
            aria-expanded={accountOpen}
          >
            {ACCOUNT.initials}
          </button>
          <Popover
            open={accountOpen}
            onClose={() => setAccountOpen(false)}
            label="Tài khoản"
            width={280}
          >
            <div className="flex items-center gap-3 px-2.5 py-2">
              <span
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full
                           bg-gradient-to-br from-rose-500 to-orange-400 text-[13px] font-bold text-white"
              >
                {ACCOUNT.initials}
              </span>
              <span className="min-w-0">
                <span className="block truncate text-[13px] font-medium text-ink">
                  {ACCOUNT.name}
                </span>
                <span className="block truncate text-[12px] text-faint">{ACCOUNT.email}</span>
              </span>
            </div>
            <div className="menu-sep" />
            <p className="px-2.5 py-1.5 text-[12px] leading-relaxed text-faint">
              Chưa có máy chủ tài khoản, nên phần đồng bộ dấu trang và nhật ký giữa các thiết bị
              chưa hoạt động.
            </p>
          </Popover>
        </div>

        <div className="relative">
          <button
            onClick={() => setMenuOpen((v) => !v)}
            className={'icon-btn ' + (menuOpen ? 'bg-raised text-ink' : '')}
            aria-label="Menu trình duyệt"
            title="Tuỳ chỉnh và kiểm soát VnSearch"
            aria-expanded={menuOpen}
          >
            <MenuIcon className="h-[18px] w-[18px]" />
          </button>
          <BrowserMenu open={menuOpen} onClose={() => setMenuOpen(false)} />
        </div>
      </div>
    </div>
  )
}

/** Nội dung "trạng thái rỗng" gọn cho các popover nhỏ trên thanh công cụ. */
function PopoverNote({ title, body }: { title: string; body: string }): JSX.Element {
  return (
    <div className="px-2.5 py-2">
      <p className="text-[13px] font-medium text-ink">{title}</p>
      <p className="mt-1 text-[12px] leading-relaxed text-faint">{body}</p>
    </div>
  )
}

export default Toolbar
