import { useEffect, useState, type JSX } from 'react'
import {
  fetchLeagues,
  fetchTeams,
  type FootballLeague,
  type FootballTeam
} from '../../lib/footballApi'
import { isFavourite, toFavourite, useFootballStore } from '../../store/footballStore'
import { useFootballAppStore } from '../../store/footballAppStore'
import { ChevronDownIcon, SpinnerIcon, StarIcon } from '../icons'
import {
  Crest,
  EmptyState,
  ErrorState,
  GlassListRow,
  GlassSearchField,
  LoadingState,
  OfflineState,
  ScreenTitle
} from './glass'
import { useResource } from './useResource'

/** Chờ bấy nhiêu mili-giây sau phím cuối rồi mới gọi — đúng bản gốc. */
const SEARCH_DEBOUNCE_MS = 400

/** Ngưỡng tối thiểu để bắt đầu tìm, cũng lấy từ bản gốc. */
const MIN_QUERY = 2

/**
 * Tab Đội bóng — bản chuyển của `TeamsTabView`.
 *
 * Hai chế độ, y như bản gốc: ô tìm kiếm còn trống thì hiện danh sách giải mở
 * ra được (`DisclosureGroup`), gõ vào thì đổi sang danh sách kết quả tìm kiếm.
 */
function TeamsTab(): JSX.Element {
  const [query, setQuery] = useState('')
  const debounced = useDebounced(query.trim(), SEARCH_DEBOUNCE_MS)
  const searching = query.trim() !== debounced

  return (
    <>
      <header className="mb-4 flex flex-col gap-3.5">
        <ScreenTitle>Đội bóng</ScreenTitle>
        <GlassSearchField
          value={query}
          onChange={setQuery}
          placeholder="Tìm một đội bóng…"
          searching={searching}
        />
      </header>

      {query.trim() === '' ? <LeagueBrowser /> : <SearchResults query={debounced} />}
    </>
  )
}

/** Hoãn một giá trị lại vài trăm mili-giây — để mỗi phím gõ không là một request. */
function useDebounced(value: string, delay: number): string {
  const [settled, setSettled] = useState(value)

  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])

  return settled
}

/**
 * Kết quả tìm đội.
 *
 * <h3>Vì sao lọc bớt đội trẻ và đội nữ</h3>
 *
 * `/teams?search=` của API-Football trả về mọi đội mang tên ấy: đội một, đội
 * U19, U21, đội B, và đội nữ. Gõ "arsenal" ra bảy dòng gần như giống hệt nhau
 * thì người tìm phải đọc kỹ từng dòng mới chọn được đúng đội mình muốn. Bản
 * gốc lọc đúng bộ hậu tố này; giữ nguyên để hai bản cho cùng một kết quả.
 */
function SearchResults({ query }: { query: string }): JSX.Element {
  const teams = useResource<FootballTeam[]>(`team-search|${query}`, async () => {
    if (query.length < MIN_QUERY) {
      return []
    }
    return filterNoise(await fetchTeams(query))
  })

  if (query.length < MIN_QUERY) {
    return <EmptyState message={`Gõ ít nhất ${MIN_QUERY} ký tự để tìm đội.`} />
  }
  if (teams.loading) {
    return <LoadingState />
  }
  if (teams.failed) {
    return <OfflineState />
  }
  if ((teams.data ?? []).length === 0) {
    return <EmptyState message={`Không tìm thấy đội nào khớp “${query}”.`} />
  }

  return (
    <div className="flex flex-col gap-2.5">
      {(teams.data ?? []).map((team, index) => (
        <TeamRow key={team.id} team={team} index={index} />
      ))}
    </div>
  )
}

const NOISE_PATTERNS = [' u19', ' u20', ' u21', ' u23', 'women']
const NOISE_SUFFIXES = [' ii', ' b', ' w']

function filterNoise(teams: FootballTeam[]): FootballTeam[] {
  return teams.filter((team) => {
    const name = team.name.toLowerCase()
    if (NOISE_PATTERNS.some((needle) => name.includes(needle))) {
      return false
    }
    return !NOISE_SUFFIXES.some((suffix) => name.endsWith(suffix))
  })
}

/** Danh sách giải mở ra được, mỗi giải chứa đội của mùa đang chọn. */
function LeagueBrowser(): JSX.Element {
  const [expanded, setExpanded] = useState<string[]>([])

  const [teamsByLeague, setTeamsByLeague] = useState<Record<string, TeamsSlot>>({})

  const leagues = useResource<FootballLeague[]>('leagues', async () => {
    const envelope = await fetchLeagues()
    return envelope.data
  })

  function toggle(league: FootballLeague): void {
    if (expanded.includes(league.id)) {
      setExpanded(expanded.filter((id) => id !== league.id))
      return
    }
    setExpanded([...expanded, league.id])

    const slotKey = league.id
    if (teamsByLeague[slotKey] !== undefined) {
      return
    }

    // Tải ngay trong tay cầm sự kiện chứ không qua một effect: việc này do một
    // cú bấm gây ra, và viết thẳng ở đây thì luồng đọc từ trên xuống là đúng
    // thứ tự nó xảy ra.
    setTeamsByLeague((current) => ({ ...current, [slotKey]: 'loading' }))
    void fetchTeams('', league.id)
      .then((teams) => {
        setTeamsByLeague((current) => ({ ...current, [slotKey]: filterNoise(teams) }))
      })
      .catch(() => {
        setTeamsByLeague((current) => ({ ...current, [slotKey]: 'error' }))
      })
  }

  if (leagues.loading) {
    return <LoadingState />
  }
  if (leagues.failed) {
    return <OfflineState />
  }

  return (
    <div className="flex flex-col gap-2.5">
      {(leagues.data ?? []).map((league, index) => (
        <LeagueGroup
          key={league.id}
          league={league}
          index={index}
          open={expanded.includes(league.id)}
          slot={teamsByLeague[league.id]}
          onToggle={() => toggle(league)}
        />
      ))}
    </div>
  )
}

type TeamsSlot = FootballTeam[] | 'loading' | 'error' | undefined

function LeagueGroup({
  league,
  index,
  open,
  slot,
  onToggle
}: {
  league: FootballLeague
  index: number
  open: boolean
  slot: TeamsSlot
  onToggle: () => void
}): JSX.Element {
  return (
    <div
      className="glass animate-rise-in overflow-hidden rounded-2xl"
      style={{ animationDelay: `${Math.min(index, 10) * 35}ms` }}
    >
      <button
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors
                   hover:bg-white/5 focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-white/50"
      >
        <Crest emblem={league.icon} size={32} fallback="🏆" />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[15px] font-semibold text-white">{league.name}</span>
          <span className="block truncate text-[12px] text-white/55">{league.country}</span>
        </span>
        <ChevronDownIcon
          className={
            'h-4 w-4 shrink-0 text-white/45 transition-transform ' + (open ? 'rotate-180' : '')
          }
        />
      </button>

      {open && (
        <div className="px-2 pb-2">
          {slot === undefined || slot === 'loading' ? (
            <div className="flex items-center justify-center py-5">
              <SpinnerIcon className="h-5 w-5 text-white/60" />
            </div>
          ) : slot === 'error' ? (
            <ErrorState message="Không tải được danh sách đội của giải này." />
          ) : slot.length === 0 ? (
            <p className="px-3 py-4 text-center text-[12.5px] text-white/50">
              Mùa này chưa có danh sách đội.
            </p>
          ) : (
            <ul className="flex flex-col">
              {slot.map((team) => (
                <li key={team.id} className="border-t border-white/8 first:border-t-0">
                  <TeamRow team={team} flat />
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Một dòng đội: bấm vào phần thân để xem lịch, bấm sao để ghim.
 *
 * Nút sao nằm NGOÀI nút mở chi tiết, không lồng vào trong: hai nút lồng nhau
 * là HTML không hợp lệ, và một cú bấm ghim sẽ kéo theo cả việc mở màn hình
 * khác — đúng lỗi mà bảng bên đã phải sửa một lần.
 */
function TeamRow({
  team,
  index = 0,
  flat = false
}: {
  team: FootballTeam
  index?: number
  flat?: boolean
}): JSX.Element {
  const favourites = useFootballStore((s) => s.favourites)
  const toggleFavourite = useFootballStore((s) => s.toggleFavourite)
  const push = useFootballAppStore((s) => s.push)

  const pinned = isFavourite(favourites, team.id)

  const body = (
    <>
      <Crest emblem={team.emblem} size={38} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14.5px] font-semibold text-white">{team.name}</span>
        <span className="block truncate text-[12px] text-white/50">{team.country ?? ''}</span>
      </span>
    </>
  )

  const star = (
    <button
      onClick={() => toggleFavourite(toFavourite(team))}
      aria-pressed={pinned}
      aria-label={pinned ? `Bỏ ghim ${team.name}` : `Ghim ${team.name}`}
      title={pinned ? `Bỏ ghim ${team.name}` : `Ghim ${team.name} lên tab Đội của tôi`}
      className={
        'shrink-0 rounded-full p-2 transition hover:scale-110 focus-visible:outline-none ' +
        'focus-visible:ring-2 focus-visible:ring-white/60 ' +
        (pinned ? 'text-[#FFD54F]' : 'text-white/30 hover:text-white/70')
      }
    >
      <StarIcon className="h-[18px] w-[18px]" filled={pinned} />
    </button>
  )

  const open = (): void =>
    push({
      kind: 'team',
      team: toFavourite(team)
    })

  if (flat) {
    return (
      <div className="flex items-center gap-3 px-2 py-2">
        <button
          onClick={open}
          className="flex min-w-0 flex-1 items-center gap-3 text-left transition hover:opacity-85
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50"
          title={`Xem lịch của ${team.name}`}
        >
          {body}
        </button>
        {star}
      </div>
    )
  }

  return (
    <div className="relative">
      <GlassListRow index={index} onClick={open} title={`Xem lịch của ${team.name}`}>
        {body}
        <span className="w-9 shrink-0" aria-hidden="true" />
      </GlassListRow>
      <div className="absolute right-2 top-1/2 -translate-y-1/2">{star}</div>
    </div>
  )
}

export default TeamsTab
