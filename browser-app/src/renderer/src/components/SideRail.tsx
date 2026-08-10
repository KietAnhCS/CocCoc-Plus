import type { JSX } from 'react'
import { useSidePanelStore, type RailItem } from '../store/sidePanelStore'
import { useThemeStore } from '../store/themeStore'
import { useAdminCredential, useAdminStore } from '../store/adminStore'
import { findApp } from '../lib/apps'
import AppTile from './AppTile'
import { siteGradient, siteInitial, hostOf } from '../lib/site'
import {
  ClockIcon,
  CloseIcon,
  MoonIcon,
  PlusIcon,
  SettingsIcon,
  ShieldCheckIcon,
  ShieldIcon,
  SunIcon,
  TranslateIcon
} from './icons'

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
  // Có quyền quản trị hay không — bất kể quyền đó đến từ tài khoản hay từ khoá API.
  const hasAdmin = useAdminCredential() !== null
  const dashboardOpen = useAdminStore((s) => s.dashboardOpen)
  const openDashboard = useAdminStore((s) => s.openDashboard)
  const closeDashboard = useAdminStore((s) => s.closeDashboard)

  return (
    <aside
      className="flex w-12 shrink-0 flex-col items-center gap-1 border-l border-line bg-chrome py-2"
      aria-label="Thanh bên"
    >
      <button
        onClick={() => togglePanel('add-site')}
        className={'rail-btn ' + (open === 'add-site' ? 'bg-raised text-ink' : '')}
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
            onOpen={() =>
              open === 'app' && activeItemId === item.id ? closePanel() : openApp(item.id)
            }
            onRemove={() => removeItem(item.id)}
          />
        ))}
      </div>

      <div className="mt-1 flex flex-col items-center gap-1 border-t border-line pt-2">
        {/*
          Nút vào khu vực quản trị LUÔN hiển thị, kể cả khi chưa có quyền.

          Ẩn nút đi khi chưa đăng nhập là cách làm phổ biến và ở đây nó sai
          theo hai hướng: nó KHÔNG bảo vệ được gì (máy chủ mới là nơi chặn, và
          nó chặn bất kể giao diện vẽ ra cái gì), lại còn giấu mất lối vào của
          chính người có quyền — họ không có cách nào để đăng nhập. Nút hiện,
          bấm vào thì gặp cửa xác thực: ranh giới quyền được NÓI RA thay vì
          được che đi.
        */}
        <button
          onClick={() => (dashboardOpen ? closeDashboard() : openDashboard())}
          className={'rail-btn ' + (dashboardOpen ? 'bg-raised text-ink' : '')}
          aria-label="Bảng điều khiển quản trị"
          title={
            hasAdmin
              ? 'Bảng điều khiển quản trị (đang ở vai trò ADMIN)'
              : 'Bảng điều khiển quản trị — cần đăng nhập bằng tài khoản quản trị'
          }
          aria-pressed={dashboardOpen}
        >
          {hasAdmin ? (
            <ShieldCheckIcon className="h-[18px] w-[18px] text-success" />
          ) : (
            <ShieldIcon className="h-[18px] w-[18px]" />
          )}
        </button>

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

function RailAppButton({ item, active, onOpen, onRemove }: RailAppButtonProps): JSX.Element | null {
  const app = item.url ? undefined : findApp(item.id)
  if (!app && !item.url) {
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
