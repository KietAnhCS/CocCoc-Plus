import { useState, type JSX } from 'react'
import NavigationButtons from './NavigationButtons'
import AddressBar from './AddressBar'
import BrowserMenu from './BrowserMenu'
import Popover from './Popover'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useTabStore } from '../store/tabStore'
import { ACCOUNT } from '../lib/account'
import { DownloadIcon, MenuIcon, PuzzleIcon, SparkleIcon, SplitScreenIcon } from './icons'

function Toolbar(): JSX.Element {
  const [extensionsOpen, setExtensionsOpen] = useState(false)
  const [splitOpen, setSplitOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)

  const openPanel = useSidePanelStore((state) => state.openPanel)
  const togglePanel = useSidePanelStore((state) => state.togglePanel)
  const panelOpen = useSidePanelStore((state) => state.open)
  const tabCount = useTabStore((state) => state.tabs.length)

  return (
    <div className="flex h-12 shrink-0 items-center gap-1 bg-surface px-2.5">
      <NavigationButtons />
      <div className="mx-1 h-5 w-px shrink-0 bg-line" />

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

        <button
          onClick={() => openPanel('downloads')}
          className={'icon-btn ' + (panelOpen === 'downloads' ? 'bg-raised text-ink' : '')}
          aria-label="Tải xuống"
          title="Tải xuống (Ctrl+J)"
        >
          <DownloadIcon className="h-[18px] w-[18px]" />
        </button>

        <div className="relative">
          <button
            onClick={() => setAccountOpen((open) => !open)}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full
                       bg-linear-to-br from-rose-500 to-orange-400 text-[11px] font-bold text-white
                       transition hover:brightness-110 focus-visible:ring-2
                       focus-visible:ring-brand/60 focus-visible:outline-none"
            aria-label={`Tài khoản ${ACCOUNT.name}`}
            title={ACCOUNT.email}
            aria-expanded={accountOpen}
          >
            {ACCOUNT.initials}
          </button>
          <Popover
            open={accountOpen}
            onClose={() => setAccountOpen(false)}
            label="Tài khoản"
            width={260}
          >
            <div className="px-2.5 py-2">
              <p className="text-[13px] font-medium text-ink">{ACCOUNT.name}</p>
              <p className="mt-0.5 text-[12px] text-faint">{ACCOUNT.email}</p>
              <p className="mt-2 text-[12px] text-success">{ACCOUNT.status}</p>
            </div>
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
