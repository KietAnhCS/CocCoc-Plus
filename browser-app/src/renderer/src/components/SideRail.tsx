import { useSidePanelStore, type RailItem } from '../store/sidePanelStore'
import { useThemeStore } from '../store/themeStore'
import { AppTile, findApp } from '../lib/apps'
import { siteGradient, siteInitial, hostOf } from '../lib/site'
import {
  ClockIcon,
  CloseIcon,
  MoonIcon,
  PlusIcon,
  SettingsIcon,
  SunIcon,
  TranslateIcon
} from './icons'

/**
 * Cột biểu tượng dọc sát mép phải. Bấm một ô thì mở bảng bên tương ứng
 * (SidePanel), bấm lần nữa thì đóng.
 *
 * Bề ngang cột (48px) được main process trừ ra khỏi bề ngang trang ngoài để
 * cột không bị WebContentsView đè lên — xem SIDE_RAIL_WIDTH ở cả
 * store/sidePanelStore.ts lẫn main/tabManager.ts.
 */
function SideRail(): JSX.Element {
  const items = useSidePanelStore((s) => s.items)
  const open = useSidePanelStore((s) => s.open)
  const activeItemId = useSidePanelStore((s) => s.activeItemId)
  const openApp = useSidePanelStore((s) => s.openApp)
  const closePanel = useSidePanelStore((s) => s.closePanel)
  const togglePanel = useSidePanelStore((s) => s.togglePanel)
  const removeItem = useSidePanelStore((s) => s.removeItem)
  const theme = useThemeStore((s) => s.theme)
  const toggleTheme = useThemeStore((s) => s.toggleTheme)

  return (
    <aside
      className="flex w-12 shrink-0 flex-col items-center gap-1 border-l border-line bg-chrome py-2"
      aria-label="Thanh bên"
    >
      <button
        onClick={() => togglePanel('add-site')}
        className={
          'rail-btn ' + (open === 'add-site' ? 'bg-raised text-ink' : '')
        }
        aria-label="Thêm trang web vào thanh bên"
        title="Thêm trang web vào thanh bên"
      >
        <PlusIcon className="h-[18px] w-[18px]" />
      </button>

      <div className="my-1 h-px w-6 bg-line" />

      <div className="flex min-h-0 flex-1 flex-col items-center gap-1.5 overflow-y-auto">
        {items.map((item) => (
          <RailAppButton
            key={item.id}
            item={item}
            active={open === 'app' && activeItemId === item.id}
            onOpen={() => (open === 'app' && activeItemId === item.id ? closePanel() : openApp(item.id))}
            onRemove={() => removeItem(item.id)}
          />
        ))}
      </div>

      {/* Nhóm cuối cột: dịch trang, nhật ký, giao diện, cài đặt. */}
      <div className="mt-1 flex flex-col items-center gap-1 border-t border-line pt-2">
        <button className="rail-btn" aria-label="Dịch trang" title="Dịch trang này">
          <TranslateIcon className="h-[18px] w-[18px]" />
        </button>
        <button
          onClick={() => togglePanel('downloads')}
          className={'rail-btn ' + (open === 'downloads' ? 'bg-raised text-ink' : '')}
          aria-label="Nhật ký và tải xuống"
          title="Nhật ký và tải xuống"
        >
          <ClockIcon className="h-[18px] w-[18px]" />
        </button>
        <button
          onClick={toggleTheme}
          className="rail-btn"
          aria-label="Đổi giao diện sáng/tối"
          title={theme === 'dark' ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối'}
        >
          {theme === 'dark' ? (
            <SunIcon className="h-[18px] w-[18px]" />
          ) : (
            <MoonIcon className="h-[18px] w-[18px]" />
          )}
        </button>
        <button className="rail-btn" aria-label="Cài đặt" title="Cài đặt">
          <SettingsIcon className="h-[18px] w-[18px]" />
        </button>
      </div>
    </aside>
  )
}

interface RailAppButtonProps {
  item: RailItem
  active: boolean
  onOpen: () => void
  onRemove: () => void
}

/**
 * Một ô trên cột. Ô có thể là ứng dụng dựng sẵn (lấy hình từ lib/apps.tsx)
 * hoặc trang người dùng tự dán URL — trang tự thêm không có logo nên dùng
 * "favicon giả" như phần còn lại của ứng dụng (lib/site.ts).
 */
function RailAppButton({ item, active, onOpen, onRemove }: RailAppButtonProps): JSX.Element | null {
  const app = item.url ? undefined : findApp(item.id)
  if (!app && !item.url) {
    // Danh mục đổi mà localStorage còn giữ id cũ -> bỏ qua ô đó.
    return null
  }

  const label = app ? app.name : (item.name ?? hostOf(item.url ?? ''))

  return (
    <div className="group relative">
      <button
        onClick={onOpen}
        className={
          'flex h-9 w-9 items-center justify-center rounded-xl transition-colors duration-150 ' +
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60 ' +
          (active ? 'bg-raised' : 'hover:bg-raised')
        }
        aria-label={label}
        title={label}
      >
        {app ? (
          <AppTile app={app} size={26} />
        ) : (
          <span
            className="flex h-[26px] w-[26px] items-center justify-center rounded-full text-[11px] font-bold text-white"
            style={{ background: siteGradient(item.url ?? '') }}
          >
            {siteInitial(item.url ?? '')}
          </span>
        )}
      </button>

      {/* Vạch sáng bên trái báo ô đang mở — kiểu chỉ báo của thanh bên Cốc Cốc. */}
      {active && (
        <span className="pointer-events-none absolute -left-2 top-1/2 h-4 w-[3px] -translate-y-1/2 rounded-full bg-brand" />
      )}

      <button
        onClick={(e) => {
          e.stopPropagation()
          onRemove()
        }}
        className="absolute -right-0.5 -top-0.5 hidden h-4 w-4 items-center justify-center rounded-full
                   bg-raised text-faint shadow-tab transition hover:bg-danger/20 hover:text-danger
                   focus-visible:outline-none group-hover:flex"
        aria-label={`Gỡ ${label} khỏi thanh bên`}
        title="Gỡ khỏi thanh bên"
      >
        <CloseIcon className="h-2.5 w-2.5" strokeWidth={2.6} />
      </button>
    </div>
  )
}

export default SideRail
