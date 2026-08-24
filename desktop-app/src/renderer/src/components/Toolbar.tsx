import { useState, type JSX } from 'react'
import NavigationButtons from './NavigationButtons'
import AddressBar from './AddressBar'
import BrowserMenu from './BrowserMenu'
import Popover from './Popover'
import AccountMenu from './AccountMenu'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useTabStore } from '../store/tabStore'
import { useSessionStore } from '../store/sessionStore'
import { useAdminStore } from '../store/adminStore'
import { DownloadButton } from './DownloadIndicator'
import {
  DownloadIcon,
  IncognitoIcon,
  MenuIcon,
  PuzzleIcon,
  SparkleIcon,
  SplitScreenIcon,
  UserIcon
} from './icons'

function Toolbar(): JSX.Element {
  const [extensionsOpen, setExtensionsOpen] = useState(false)
  const [splitOpen, setSplitOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)

  const openPanel = useSidePanelStore((state) => state.openPanel)
  const togglePanel = useSidePanelStore((state) => state.togglePanel)
  const panelOpen = useSidePanelStore((state) => state.open)
  const tabCount = useTabStore((state) => state.tabs.length)
  const anDanh = useTabStore(
    (state) => state.tabs.find((tab) => tab.id === state.activeTabId)?.incognito ?? false
  )
  const user = useSessionStore((state) => state.user)
  const openDashboard = useAdminStore((state) => state.openDashboard)

  return (
    <div
      className={
        'flex h-12 shrink-0 items-center gap-1 px-2.5 transition-colors duration-200 ' +
        (anDanh ? 'bg-violet-950/95 text-violet-100' : 'bg-surface')
      }
    >
      <NavigationButtons />
      <div className={'mx-1 h-5 w-px shrink-0 ' + (anDanh ? 'bg-violet-400/30' : 'bg-line')} />

      {anDanh && (
        <span
          className="flex h-7 shrink-0 items-center gap-1.5 rounded-full border border-violet-400/40
                     bg-violet-500/20 px-2.5 text-[12px] font-medium text-violet-100"
          title="Thẻ ẩn danh — không ghi lịch sử duyệt web"
        >
          <IncognitoIcon className="h-4 w-4 text-violet-300" />
          Ẩn danh
        </span>
      )}

      <AddressBar />

      <div className="ml-0.5 flex shrink-0 items-center gap-0.5">
        <div className="relative">
          <button
            onClick={() => setExtensionsOpen((open) => !open)}
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

        <button
          onClick={() => togglePanel('ai')}
          className={
            'flex h-8 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-[12.5px] ' +
            'transition-colors duration-150 focus-visible:ring-2 focus-visible:ring-brand/60 ' +
            'focus-visible:outline-none ' +
            (panelOpen === 'ai'
              ? 'bg-brand-soft text-brand'
              : 'text-muted hover:bg-raised hover:text-ink')
          }
          aria-label="Hỏi AI"
          title="Hỏi AI"
          aria-pressed={panelOpen === 'ai'}
        >
          <SparkleIcon className="h-4 w-4" />
          Hỏi AI
        </button>

        <div className="relative">
          <button
            onClick={() => setSplitOpen((open) => !open)}
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
            width={270}
          >
            <PopoverNote
              title="Chưa hỗ trợ chia đôi màn hình"
              body={`Cửa sổ hiện có ${tabCount} thẻ, nhưng mỗi lúc chỉ hiển thị được một thẻ.`}
            />
          </Popover>
        </div>

        <DownloadButton
          buttonClass="icon-btn"
          label="Tải xuống"
          description="Tải xuống (Ctrl+J)"
          active={panelOpen === 'downloads'}
          onClick={() => openPanel('downloads')}
          announce
        >
          <DownloadIcon className="h-[18px] w-[18px]" />
        </DownloadButton>

        <div className="relative">
          <button
            onClick={() => setAccountOpen((open) => !open)}
            className={
              'flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[11px] ' +
              'font-bold transition hover:brightness-110 focus-visible:ring-2 ' +
              'focus-visible:ring-brand/60 focus-visible:outline-none ' +
              // Ba trạng thái, ba diện mạo — avatar phải NÓI THẬT về quyền
              // hiện tại. Trước đây nó luôn hiện một tài khoản "admin" cứng,
              // kể cả khi chưa ai đăng nhập.
              (user
                ? user.role === 'ADMIN'
                  ? 'bg-linear-to-br from-emerald-500 to-lime-400 text-white'
                  : 'bg-linear-to-br from-teal-500 to-emerald-400 text-white'
                : 'border border-line bg-raised text-muted')
            }
            aria-label={user ? `Tài khoản ${user.username}` : 'Chưa đăng nhập'}
            title={
              user
                ? `${user.username} — ${user.role === 'ADMIN' ? 'Quản trị viên' : 'Người dùng'}`
                : 'Chưa đăng nhập'
            }
            aria-expanded={accountOpen}
          >
            {user ? user.username.slice(0, 2).toUpperCase() : <UserIcon className="h-4 w-4" />}
          </button>
          <Popover
            open={accountOpen}
            onClose={() => setAccountOpen(false)}
            label="Tài khoản"
            width={280}
          >
            <AccountMenu
              onNavigateAdmin={() => {
                setAccountOpen(false)
                openDashboard()
              }}
            />
          </Popover>
        </div>

        <div className="relative">
          <button
            onClick={() => setMenuOpen((open) => !open)}
            className={'icon-btn ' + (menuOpen ? 'bg-raised text-ink' : '')}
            aria-label="Tuỳ chọn"
            title="Tuỳ chọn"
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

function PopoverNote({ title, body }: { title: string; body: string }): JSX.Element {
  return (
    <div className="px-2.5 py-2">
      <p className="text-[13px] font-medium text-ink">{title}</p>
      <p className="mt-1 text-[12px] leading-relaxed text-faint">{body}</p>
    </div>
  )
}

export default Toolbar
