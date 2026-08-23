import { useEffect, type JSX } from 'react'
import { useFootballAppStore, type FootballTab } from '../store/footballAppStore'
import { useOverlayStore } from '../store/overlayStore'
import { useSidePanelStore } from '../store/sidePanelStore'
import { AppBackground } from './football/glass'
import { BACKGROUNDS } from './football/backgrounds'
import HomeTab from './football/HomeTab'
import LeaguesTab from './football/LeaguesTab'
import TeamsTab from './football/TeamsTab'
import PlayersTab from './football/PlayersTab'
import ProfileTab from './football/ProfileTab'
import LeagueFixturesScreen from './football/LeagueFixturesScreen'
import TeamDetailScreen from './football/TeamDetailScreen'
import PlayerDetailScreen from './football/PlayerDetailScreen'
import {
  ChevronsRightIcon,
  CloseIcon,
  HomeIcon,
  ShieldIcon,
  TrophyIcon,
  UserCircleIcon,
  UsersIcon
} from './icons'

/**
 * Chiều rộng cột nội dung.
 *
 * Bằng đúng một màn hình điện thoại. Bản gốc là ứng dụng iOS và mọi tỉ lệ của
 * nó — thẻ trận ba cột, dòng danh sách, thanh tab năm mục — được cân cho bề
 * ngang ấy. Kéo giãn ra 1500px thì bố cục vẫn "chạy" nhưng không còn là bố cục
 * ấy nữa: thẻ trận biến thành ba hòn đảo cách nhau nửa gang tay, và tên đội
 * trôi ra tận hai mép trong khi tỉ số lạc lõng ở giữa.
 */
const COLUMN_WIDTH = 440

/**
 * Ảnh nền của từng tab — đúng ảnh mà bản gốc dùng cho màn hình tương ứng, chép
 * thẳng từ `FootballTracker/Resources/Assets/Images`.
 *
 * Đổi ảnh theo tab chứ không dùng một ảnh chung: đó là cách người dùng bản iOS
 * biết mình đang ở đâu mà không cần đọc tiêu đề, và nó gần như không tốn gì —
 * ảnh đã nằm sẵn trong gói cài.
 */
const TAB_BACKGROUNDS: Record<FootballTab, string> = {
  home: BACKGROUNDS.home,
  leagues: BACKGROUNDS.leagues,
  teams: BACKGROUNDS.teams,
  players: BACKGROUNDS.players,
  profile: BACKGROUNDS.profile
}

/** Sắc quầng sáng phủ thêm, để mỗi tab có một tông riêng. */
const TAB_HUES: Record<FootballTab, string> = {
  home: 'rgba(0,200,83,0.18)',
  leagues: 'rgba(255,193,7,0.14)',
  teams: 'rgba(41,121,255,0.14)',
  players: 'rgba(156,39,176,0.14)',
  profile: 'rgba(0,150,136,0.14)'
}

const TABS: { id: FootballTab; label: string; icon: JSX.Element }[] = [
  { id: 'home', label: 'Đội của tôi', icon: <HomeIcon className="h-5 w-5" /> },
  { id: 'leagues', label: 'Giải đấu', icon: <TrophyIcon className="h-5 w-5" /> },
  { id: 'teams', label: 'Đội bóng', icon: <ShieldIcon className="h-5 w-5" /> },
  { id: 'players', label: 'Cầu thủ', icon: <UsersIcon className="h-5 w-5" /> },
  { id: 'profile', label: 'Hồ sơ', icon: <UserCircleIcon className="h-5 w-5" /> }
]

/**
 * Trang bóng đá toàn màn hình — bản chuyển của FootballTracker (SwiftUI, iOS)
 * sang trình duyệt.
 *
 * <h3>Cái gì được giữ nguyên</h3>
 *
 * Năm tab đúng thứ tự bản gốc (`MainTabView`): Đội của tôi · Giải đấu · Đội
 * bóng · Cầu thủ · Hồ sơ. Ảnh nền tràn viền phủ gradient tối, các tấm Liquid
 * Glass nổi trên nền, thanh tab kính lơ lửng ở đáy, chữ bo tròn đậm, và
 * nguyên tắc "không lồng kính trong kính".
 *
 * <h3>Cái gì phải đổi, và vì sao</h3>
 *
 * **Một ngăn xếp điều hướng, không phải năm.** Bản gốc cho mỗi tab một
 * `NavigationStack` riêng nên mỗi tab nhớ được vị trí của mình. Trên máy tính,
 * thanh tab nằm ngay trước mắt suốt cả phiên, và một cú bấm vào tab đang mở dở
 * nửa chừng thì người bấm mong nó về đầu.
 *
 * **Nội dung bị giới hạn ở một cột hẹp** — xem `COLUMN_WIDTH`.
 *
 * **Phím Esc lùi một bước**, chứ không đóng thẳng cả trang: trên iOS thao tác
 * lùi là vuốt từ mép trái, ở đây Esc là thứ gần nhất với nó. Đang ở màn hình
 * gốc của một tab thì Esc mới đóng trang.
 */
function FootballApp(): JSX.Element | null {
  const open = useFootballAppStore((s) => s.open)
  const closeApp = useFootballAppStore((s) => s.closeApp)
  const tab = useFootballAppStore((s) => s.tab)
  const setTab = useFootballAppStore((s) => s.setTab)
  const stack = useFootballAppStore((s) => s.stack)
  const pop = useFootballAppStore((s) => s.pop)

  const acquireOverlay = useOverlayStore((s) => s.acquire)
  const releaseOverlay = useOverlayStore((s) => s.release)
  const openPanel = useSidePanelStore((s) => s.openPanel)

  function shrinkToPanel(): void {
    closeApp()
    openPanel('football')
  }

  // Lớp phủ này che khung nội dung web, nên tiến trình chính phải biết để ẩn
  // WebContentsView đi — nếu không, trang web sẽ vẽ ĐÈ LÊN trang này.
  useEffect(() => {
    if (!open) {
      return undefined
    }
    acquireOverlay()
    return () => releaseOverlay()
  }, [open, acquireOverlay, releaseOverlay])

  useEffect(() => {
    if (!open) {
      return undefined
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') {
        return
      }
      if (useFootballAppStore.getState().stack.length > 0) {
        pop()
      } else {
        closeApp()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, closeApp, pop])

  if (!open) {
    return null
  }

  const route = stack[stack.length - 1]

  return (
    <div className="absolute inset-0 z-40 flex animate-blur-in flex-col overflow-hidden">
      {/* Màn hình lịch giải có ảnh nền RIÊNG ở bản gốc (`img_bg_8`), không
          dùng lại ảnh của tab đã mở ra nó. */}
      <AppBackground
        image={route?.kind === 'league' ? BACKGROUNDS.fixtures : TAB_BACKGROUNDS[tab]}
        hue={TAB_HUES[tab]}
      />

      <div className="absolute right-5 top-5 z-10 flex gap-2">
        {/* Đường về bảng bên. Nút thanh bên giờ mở thẳng trang này, nên nếu
            không có nút đây thì bảng thu gọn — thứ để vừa duyệt web vừa liếc
            tỉ số — sẽ không còn lối vào nào. */}
        <button
          onClick={shrinkToPanel}
          className="glass flex h-9 w-9 items-center justify-center rounded-full text-white/80
                     transition hover:text-white focus-visible:outline-none focus-visible:ring-2
                     focus-visible:ring-white/60"
          aria-label="Thu gọn thành bảng bên"
          title="Thu gọn thành bảng bên"
        >
          <ChevronsRightIcon className="h-4 w-4" />
        </button>

        <button
          onClick={closeApp}
          className="glass flex h-9 w-9 items-center justify-center rounded-full text-white/80
                     transition hover:text-white focus-visible:outline-none focus-visible:ring-2
                     focus-visible:ring-white/60"
          aria-label="Đóng trang bóng đá"
          title="Đóng"
        >
          <CloseIcon className="h-4 w-4" strokeWidth={2.2} />
        </button>
      </div>

      <div className="relative min-h-0 flex-1 overflow-y-auto px-5 pb-28 pt-6">
        {/* `key` đổi theo màn hình đang mở để mỗi lần điều hướng là một lần
            gắn mới: hoạt ảnh nổi lên của các thẻ chạy lại, và vị trí cuộn
            không bị kế thừa từ màn hình trước. */}
        <div
          key={routeKey(tab, stack.length)}
          className="mx-auto w-full"
          style={{ maxWidth: COLUMN_WIDTH }}
        >
          {route === undefined && <TabScreen tab={tab} />}
          {route?.kind === 'league' && <LeagueFixturesScreen league={route.league} />}
          {route?.kind === 'team' && <TeamDetailScreen team={route.team} />}
          {route?.kind === 'player' && <PlayerDetailScreen player={route.player} />}
        </div>
      </div>

      <TabBar selected={tab} onSelect={setTab} />
    </div>
  )
}

function routeKey(tab: FootballTab, depth: number): string {
  return `${tab}:${depth}`
}

function TabScreen({ tab }: { tab: FootballTab }): JSX.Element {
  switch (tab) {
    case 'home':
      return <HomeTab />
    case 'leagues':
      return <LeaguesTab />
    case 'teams':
      return <TeamsTab />
    case 'players':
      return <PlayersTab />
    case 'profile':
      return <ProfileTab />
  }
}

/**
 * Thanh tab kính lơ lửng ở đáy — chi tiết nhận diện rõ nhất của bản gốc.
 *
 * Vẫn ở ĐÁY dù trên máy tính quy ước là trên đỉnh: ở đây nó lơ lửng giữa màn
 * hình chứ không dính mép, nên không lẫn với thanh công cụ của trình duyệt.
 */
function TabBar({
  selected,
  onSelect
}: {
  selected: FootballTab
  onSelect: (tab: FootballTab) => void
}): JSX.Element {
  return (
    <nav className="pointer-events-none absolute inset-x-0 bottom-5 flex justify-center">
      <div className="glass pointer-events-auto flex gap-1 rounded-full p-1.5">
        {TABS.map((item) => {
          const active = item.id === selected
          return (
            <button
              key={item.id}
              onClick={() => onSelect(item.id)}
              aria-current={active ? 'page' : undefined}
              className={
                'flex min-w-[76px] flex-col items-center gap-0.5 rounded-full px-3 py-2 ' +
                'transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 ' +
                'focus-visible:ring-white/60 ' +
                (active ? 'glass-tinted text-[#00E676]' : 'text-white/55 hover:text-white/85')
              }
            >
              {item.icon}
              <span className="text-[10px] font-semibold">{item.label}</span>
            </button>
          )
        })}
      </div>
    </nav>
  )
}

export default FootballApp
