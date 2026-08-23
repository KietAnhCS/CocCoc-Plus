import { useCallback, useEffect, useMemo, useRef, useState, type JSX } from 'react'
import {
  fetchFixtures,
  fetchLeagues,
  isImageEmblem,
  localDateKey,
  watchUrl,
  type FootballLeague,
  type FootballMatch,
  type FootballSource
} from '../lib/footballApi'
import { isFavourite, toFavourite, useFootballStore } from '../store/footballStore'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useTabStore } from '../store/tabStore'
import { AlertIcon, BallIcon, ExternalLinkIcon, StarIcon } from './icons'

/**
 * Nhịp tự làm mới của bảng, tính bằng mili-giây.
 *
 * 60 giây nhìn thì phí so với tầng đệm 15 phút của service, nhưng phần lớn
 * những lượt gọi này KHÔNG đi ra ngoài: chúng dừng lại ở đệm Postgres và chỉ
 * tốn một truy vấn cục bộ. Cái ta mua được là trận đang đá chuyển từ "45'"
 * sang "46'" mà không cần người dùng bấm gì.
 */
const REFRESH_MS = 60_000

/** Số ngày hiện ra hai bên hôm nay trong dải chọn ngày. */
const DAY_RANGE = 7

/** Một trận được coi là kéo dài 105 phút — 90 phút đá cộng nghỉ và bù giờ. */
const FULL_MATCH_MINUTES = 90

interface Outcome {
  key: string
  matches: FootballMatch[]
  source: FootballSource | null
  cachedAt: string
  offline: boolean
}

function FootballPanel(): JSX.Element {
  const leagueFilter = useFootballStore((s) => s.leagueFilter)
  const setLeagueFilter = useFootballStore((s) => s.setLeagueFilter)
  const dayOffset = useFootballStore((s) => s.dayOffset)
  const setDayOffset = useFootballStore((s) => s.setDayOffset)
  const favourites = useFootballStore((s) => s.favourites)

  const [leagues, setLeagues] = useState<FootballLeague[]>([])
  const [outcome, setOutcome] = useState<Outcome | null>(null)

  const date = useMemo(() => {
    const day = new Date()
    day.setDate(day.getDate() + dayOffset)
    return localDateKey(day)
  }, [dayOffset])

  // Khoá của lô dữ liệu đang cần. Trạng thái "đang tải" được SUY RA từ việc
  // kết quả đã về có mang đúng khoá này chưa, chứ không phải một biến `loading`
  // riêng — cùng cách mà SearchResultList làm.
  //
  // Không chỉ để cho gọn: đặt `setLoading(true)` ngay trong thân effect là thứ
  // quy tắc react-hooks/set-state-in-effect chặn, vì nó ép thêm một lượt render
  // trước cả khi request kịp rời máy.
  const requestKey = `${date}|${leagueFilter}`
  const settled = outcome?.key === requestKey ? outcome : null
  const loading = settled === null
  const offline = settled?.offline ?? false

  const load = useCallback(async (key: string, day: string, league: string): Promise<Outcome> => {
    try {
      const envelope = await fetchFixtures(day, league)
      return {
        key,
        matches: envelope.data,
        source: envelope.meta.source,
        cachedAt: envelope.meta.cachedAt,
        offline: false
      }
    } catch {
      return { key, matches: [], source: null, cachedAt: '', offline: true }
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    void load(requestKey, date, leagueFilter).then((result) => {
      if (!cancelled) {
        setOutcome(result)
      }
    })
    return () => {
      cancelled = true
    }
  }, [load, requestKey, date, leagueFilter])

  useEffect(() => {
    let cancelled = false
    void fetchLeagues()
      .then((envelope) => {
        if (!cancelled) {
          setLeagues(envelope.data)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLeagues([])
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    // Chỉ tự làm mới khi đang xem HÔM NAY. Lịch của hôm qua đã cố định, gọi
    // lại chỉ tốn truy vấn để nhận về đúng thứ đang hiển thị.
    if (dayOffset !== 0) {
      return undefined
    }

    let cancelled = false
    const timer = window.setInterval(() => {
      void load(requestKey, date, leagueFilter).then((result) => {
        if (!cancelled) {
          setOutcome(result)
        }
      })
    }, REFRESH_MS)

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [dayOffset, load, requestKey, date, leagueFilter])

  // Bọc trong useMemo để tham chiếu mảng ổn định giữa các lượt render — nếu
  // không, `settled?.matches ?? []` sinh một mảng rỗng MỚI mỗi lần và useMemo
  // gom nhóm bên dưới sẽ chạy lại vô ích ở mọi lượt render.
  const matches = useMemo(() => settled?.matches ?? [], [settled])
  const liveCount = matches.filter((match) => match.status === 'live').length

  /**
   * Gom trận theo giải, và tách riêng nhóm "Đội của bạn" lên trên cùng.
   *
   * Nhóm theo giải chứ không đổ một danh sách phẳng: trong một bảng rộng 340px
   * thì tên giải lặp lại ở từng dòng chiếm mất chỗ của chính tên đội, và mắt
   * vẫn phải tự gom nhóm bằng tay. Một tiêu đề cho cả cụm nói được đúng chừng
   * ấy thông tin bằng một phần mười diện tích.
   */
  const groups = useMemo(() => {
    const favourite = new Set(favourites.map((team) => team.id))
    const pinned = matches.filter(
      (match) => favourite.has(match.homeTeam.id) || favourite.has(match.awayTeam.id)
    )
    const pinnedIds = new Set(pinned.map((match) => match.id))

    const byCompetition = new Map<
      string,
      { name: string; logo: string; matches: FootballMatch[] }
    >()
    for (const match of matches) {
      if (pinnedIds.has(match.id)) {
        continue
      }
      const group = byCompetition.get(match.competitionId) ?? {
        name: match.competition,
        logo: match.competitionLogo,
        matches: []
      }
      group.matches.push(match)
      byCompetition.set(match.competitionId, group)
    }

    const ordered: { id: string; name: string; logo: string; matches: FootballMatch[] }[] = []
    if (pinned.length > 0) {
      ordered.push({ id: '__favourites', name: 'Đội của bạn', logo: '', matches: pinned })
    }
    for (const [id, group] of byCompetition) {
      ordered.push({ id, ...group })
    }

    // Trong mỗi nhóm: trận đang đá lên trước, rồi theo giờ bóng lăn. Đó là thứ
    // người vừa mở bảng này muốn thấy ngay.
    for (const group of ordered) {
      group.matches.sort((a, b) => {
        if ((a.status === 'live') !== (b.status === 'live')) {
          return a.status === 'live' ? -1 : 1
        }
        return a.kickoff.localeCompare(b.kickoff)
      })
    }

    return ordered
  }, [matches, favourites])

  return (
    <div className="flex h-full flex-col bg-surface">
      <HeroStrip liveCount={liveCount} loading={loading} offline={offline} />
      <DayStrip selected={dayOffset} onSelect={setDayOffset} />
      <LeagueChips leagues={leagues} selected={leagueFilter} onSelect={setLeagueFilter} />

      <SourceBanner
        source={settled?.source ?? null}
        cachedAt={settled?.cachedAt ?? ''}
        offline={offline}
      />

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading && <PanelSkeleton />}
        {offline && <OfflineNote />}

        {!loading && !offline && groups.length === 0 && (
          <EmptyDay date={date} dayOffset={dayOffset} filtered={leagueFilter !== ''} />
        )}

        {groups.map((group, groupIndex) => (
          <section key={group.id}>
            <CompetitionHeader
              name={group.name}
              logo={group.logo}
              count={group.matches.length}
              favourites={group.id === '__favourites'}
            />
            <ul>
              {group.matches.map((match, index) => (
                <MatchCard key={match.id} match={match} index={groupIndex * 3 + index} />
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}

/**
 * Dải tiêu đề. Nói đúng MỘT câu: ngay lúc này có trận nào đang đá không.
 *
 * Đó là câu hỏi khiến người ta mở bảng này, và trả lời nó ở dòng đầu tiên giúp
 * họ khỏi phải quét cả danh sách để tự tìm.
 */
function HeroStrip({
  liveCount,
  loading,
  offline
}: {
  liveCount: number
  loading: boolean
  offline: boolean
}): JSX.Element {
  const live = liveCount > 0

  return (
    <div className="relative shrink-0 overflow-hidden border-b border-line px-3.5 py-3">
      <div
        className="absolute inset-0 bg-linear-to-br from-brand/16 via-brand/6 to-transparent"
        aria-hidden="true"
      />
      {live && (
        <div
          className="absolute -right-8 -top-10 h-24 w-24 rounded-full bg-brand/25 blur-2xl"
          aria-hidden="true"
        />
      )}

      <div className="relative flex items-center gap-2.5">
        <span
          className={
            'flex h-9 w-9 shrink-0 items-center justify-center rounded-xl transition-colors ' +
            (live ? 'bg-brand text-white shadow-glow' : 'bg-raised text-muted')
          }
        >
          <BallIcon
            className={'h-5 w-5 ' + (live ? 'animate-spin [animation-duration:3.5s]' : '')}
          />
        </span>

        <div className="min-w-0 flex-1 leading-tight">
          <p className="truncate text-[13.5px] font-semibold text-ink">
            {offline
              ? 'Mất kết nối'
              : loading
                ? 'Đang tải lịch đấu…'
                : live
                  ? `${liveCount} trận đang diễn ra`
                  : 'Không có trận nào đang đá'}
          </p>
          <p className="truncate text-[11px] text-faint">Bấm một trận để mở trang xem</p>
        </div>

        {live && (
          <span className="flex shrink-0 items-center gap-1 rounded-full bg-brand/15 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-brand">
            <span className="relative flex h-1.5 w-1.5">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand opacity-75" />
              <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-brand" />
            </span>
            Live
          </span>
        )}
      </div>
    </div>
  )
}

/**
 * Dải chọn ngày, kiểu lịch cuộn ngang.
 *
 * Thay cho cặp nút "‹ ›" của bản đầu. Hai nút mũi tên chỉ cho biết có thể đi
 * tới đi lui, còn dải này cho thấy LUÔN mình đang đứng ở đâu trong tuần — thứ
 * mà mọi ứng dụng thể thao đều hiển thị, vì "thứ Bảy có trận gì" là câu hỏi
 * thường gặp hơn hẳn "hôm qua có trận gì".
 */
function DayStrip({
  selected,
  onSelect
}: {
  selected: number
  onSelect: (offset: number) => void
}): JSX.Element {
  const activeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    // Kéo ngày đang chọn vào giữa tầm nhìn. `block: 'nearest'` để thao tác này
    // không kéo theo cả bảng bên trượt lên xuống.
    activeRef.current?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
  }, [selected])

  const offsets = useMemo(() => {
    const list: number[] = []
    for (let offset = -DAY_RANGE; offset <= DAY_RANGE; offset++) {
      list.push(offset)
    }
    return list
  }, [])

  return (
    <div className="no-scrollbar flex shrink-0 gap-1 overflow-x-auto border-b border-line px-2 py-2">
      {offsets.map((offset) => {
        const day = new Date()
        day.setDate(day.getDate() + offset)
        const active = offset === selected
        const today = offset === 0

        return (
          <button
            key={offset}
            ref={active ? activeRef : undefined}
            onClick={() => onSelect(offset)}
            aria-current={active ? 'date' : undefined}
            className={
              'press flex w-[42px] shrink-0 flex-col items-center gap-0.5 rounded-xl py-1.5 ' +
              'transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 ' +
              'focus-visible:ring-brand/60 ' +
              (active
                ? 'bg-brand text-white shadow-glow'
                : 'text-muted hover:bg-raised hover:text-ink')
            }
            title={day.toLocaleDateString('vi-VN', {
              weekday: 'long',
              day: 'numeric',
              month: 'long'
            })}
          >
            <span className="text-[10px] uppercase tracking-wide opacity-80">
              {today ? 'Nay' : weekdayLabel(day)}
            </span>
            <span className="text-[14px] font-semibold tabular-nums leading-none">
              {day.getDate()}
            </span>
          </button>
        )
      })}
    </div>
  )
}

/** "T2".."T7", "CN" — cách viết thứ trong tuần quen thuộc ở Việt Nam. */
function weekdayLabel(day: Date): string {
  const index = day.getDay()
  return index === 0 ? 'CN' : `T${index + 1}`
}

function LeagueChips({
  leagues,
  selected,
  onSelect
}: {
  leagues: FootballLeague[]
  selected: string
  onSelect: (id: string) => void
}): JSX.Element | null {
  if (leagues.length === 0) {
    return null
  }

  return (
    <div className="no-scrollbar flex shrink-0 gap-1.5 overflow-x-auto border-b border-line px-3 py-2">
      <Chip label="Tất cả" active={selected === ''} onClick={() => onSelect('')} />
      {leagues.map((league) => (
        <Chip
          key={league.id}
          label={league.name}
          title={league.country ? `${league.name} · ${league.country}` : league.name}
          active={selected === league.id}
          onClick={() => onSelect(selected === league.id ? '' : league.id)}
        />
      ))}
    </div>
  )
}

function Chip({
  label,
  title,
  active,
  onClick
}: {
  label: string
  title?: string
  active: boolean
  onClick: () => void
}): JSX.Element {
  return (
    <button
      onClick={onClick}
      title={title ?? label}
      aria-pressed={active}
      className={
        'press shrink-0 rounded-full border px-2.5 py-1 text-[11.5px] whitespace-nowrap ' +
        'transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 ' +
        'focus-visible:ring-brand/60 ' +
        (active
          ? 'border-brand/50 bg-brand-soft font-medium text-brand'
          : 'border-line text-muted hover:border-brand/30 hover:text-ink')
      }
    >
      {label}
    </button>
  )
}

function CompetitionHeader({
  name,
  logo,
  count,
  favourites
}: {
  name: string
  logo: string
  count: number
  favourites: boolean
}): JSX.Element {
  return (
    <div className="sticky top-0 z-10 flex items-center gap-2 border-y border-line bg-surface/92 px-3 py-1.5 backdrop-blur-md">
      {favourites ? (
        <StarIcon className="h-3.5 w-3.5 shrink-0 text-brand" filled />
      ) : isImageEmblem(logo) ? (
        <img src={logo} alt="" className="h-4 w-4 shrink-0 object-contain" loading="lazy" />
      ) : (
        <span className="h-3.5 w-[3px] shrink-0 rounded-full bg-linear-to-b from-brand to-accent" />
      )}
      <h3 className="min-w-0 flex-1 truncate text-[11px] font-semibold uppercase tracking-wide text-muted">
        {name}
      </h3>
      <span className="shrink-0 text-[10.5px] tabular-nums text-faint">{count}</span>
    </div>
  )
}

function MatchCard({ match, index }: { match: FootballMatch; index: number }): JSX.Element {
  const favourites = useFootballStore((s) => s.favourites)
  const toggleFavourite = useFootballStore((s) => s.toggleFavourite)
  const newTab = useTabStore((s) => s.newTab)
  const pinned = useSidePanelStore((s) => s.pinned)
  const closePanel = useSidePanelStore((s) => s.closePanel)

  const kickoff = new Date(match.kickoff)
  const kickoffLabel = Number.isNaN(kickoff.getTime())
    ? '--:--'
    : kickoff.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })

  const finished = match.status === 'finished'
  const live = match.status === 'live'

  function open(): void {
    newTab(watchUrl(match))
    // Bảng chưa ghim thì đóng lại sau khi mở tab, nhường chỗ cho trang vừa mở.
    // Ghim rồi thì giữ nguyên — người ghim là người muốn vừa xem vừa theo dõi.
    if (!pinned) {
      closePanel()
    }
  }

  return (
    <li
      className="group relative animate-fade-up border-b border-line/50"
      style={{ animationDelay: `${Math.min(index, 10) * 22}ms` }}
    >
      {/* Vạch xanh mọc dọc mép trái khi rê chuột — cùng ngôn ngữ với danh sách
          kết quả tìm kiếm, để hai chỗ "bấm được" trông giống nhau. */}
      <span
        aria-hidden="true"
        className="absolute inset-y-1.5 left-0 w-[3px] origin-center scale-y-0 rounded-full
                   bg-linear-to-b from-brand to-accent transition-transform duration-300
                   group-hover:scale-y-100"
      />

      <button
        onClick={open}
        className="w-full px-3 py-2.5 text-left transition-colors duration-200 hover:bg-raised/50
                   focus-visible:outline-none focus-visible:bg-raised/60"
        title={`${match.homeTeam.name} vs ${match.awayTeam.name} — mở trang xem`}
      >
        <div className="mb-1.5 flex items-center gap-2">
          <StatusChip status={match.status} elapsed={match.elapsed} kickoff={kickoffLabel} />
          <span className="min-w-0 flex-1 truncate text-[10.5px] text-faint">{match.round}</span>
          <ExternalLinkIcon className="h-3 w-3 shrink-0 text-faint opacity-0 transition-opacity group-hover:opacity-100" />
        </div>

        <TeamLine
          team={match.homeTeam}
          score={match.homeScore}
          dimmed={finished && losing(match.homeScore, match.awayScore)}
          winner={finished && losing(match.awayScore, match.homeScore)}
        />
        <TeamLine
          team={match.awayTeam}
          score={match.awayScore}
          dimmed={finished && losing(match.awayScore, match.homeScore)}
          winner={finished && losing(match.homeScore, match.awayScore)}
        />

        {live && match.elapsed !== null && (
          <div className="mt-2 h-[3px] overflow-hidden rounded-full bg-line">
            <div
              className="h-full rounded-full bg-linear-to-r from-brand to-accent transition-[width] duration-1000"
              style={{ width: `${Math.min(100, (match.elapsed / FULL_MATCH_MINUTES) * 100)}%` }}
            />
          </div>
        )}
      </button>

      {/* Nút ghim nằm NGOÀI nút mở trang: lồng hai nút vào nhau là HTML không
          hợp lệ, và một cú bấm ghim sẽ kéo theo cả việc mở tab. */}
      <div className="pointer-events-none absolute right-2 top-2 flex gap-0.5">
        <FavouriteButton
          teamId={match.homeTeam.id}
          teamName={match.homeTeam.name}
          active={isFavourite(favourites, match.homeTeam.id)}
          onToggle={() => toggleFavourite(toFavourite(match.homeTeam))}
        />
        <FavouriteButton
          teamId={match.awayTeam.id}
          teamName={match.awayTeam.name}
          active={isFavourite(favourites, match.awayTeam.id)}
          onToggle={() => toggleFavourite(toFavourite(match.awayTeam))}
        />
      </div>
    </li>
  )
}

function FavouriteButton({
  teamId,
  teamName,
  active,
  onToggle
}: {
  teamId: string
  teamName: string
  active: boolean
  onToggle: () => void
}): JSX.Element {
  return (
    <button
      key={teamId}
      onClick={onToggle}
      className={
        'pointer-events-auto rounded p-0.5 transition-all duration-200 focus-visible:outline-none ' +
        'focus-visible:ring-2 focus-visible:ring-brand/60 ' +
        (active
          ? 'text-brand hover:scale-110'
          : 'text-faint opacity-0 hover:scale-110 hover:text-brand group-hover:opacity-100')
      }
      aria-label={active ? `Bỏ ghim ${teamName}` : `Ghim ${teamName} lên đầu`}
      aria-pressed={active}
      title={active ? `Bỏ ghim ${teamName}` : `Ghim ${teamName} lên đầu bảng`}
    >
      <StarIcon className="h-3.5 w-3.5" filled={active} />
    </button>
  )
}

function losing(own: number | null, other: number | null): boolean {
  return own !== null && other !== null && own < other
}

function TeamLine({
  team,
  score,
  dimmed,
  winner
}: {
  team: FootballMatch['homeTeam']
  score: number | null
  dimmed: boolean
  winner: boolean
}): JSX.Element {
  return (
    <div className="flex items-center gap-2 py-[3px]">
      <span className="flex h-[22px] w-[22px] shrink-0 items-center justify-center overflow-hidden rounded-full bg-raised text-[13px]">
        {isImageEmblem(team.emblem) ? (
          <img
            src={team.emblem}
            alt=""
            className="h-[18px] w-[18px] object-contain"
            loading="lazy"
          />
        ) : (
          team.emblem || '⚽'
        )}
      </span>

      <span
        className={
          'min-w-0 flex-1 truncate text-[13px] transition-colors ' +
          (dimmed ? 'text-faint' : winner ? 'font-semibold text-ink' : 'text-ink')
        }
        title={team.name}
      >
        {team.shortName || team.name}
      </span>

      <span
        className={
          'flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-md text-[13px] ' +
          'font-bold tabular-nums transition-colors ' +
          (score === null
            ? 'text-faint'
            : winner
              ? 'bg-brand-soft text-brand'
              : dimmed
                ? 'text-muted'
                : 'text-ink')
        }
      >
        {score === null ? '–' : score}
      </span>
    </div>
  )
}

function StatusChip({
  status,
  elapsed,
  kickoff
}: {
  status: FootballMatch['status']
  elapsed: number | null
  kickoff: string
}): JSX.Element {
  if (status === 'live') {
    return (
      <span className="flex shrink-0 items-center gap-1 rounded-full bg-brand/15 px-1.5 py-0.5 text-[10px] font-bold text-brand">
        <span className="relative flex h-1.5 w-1.5">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand opacity-75" />
          <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-brand" />
        </span>
        {elapsed !== null ? `${elapsed}'` : 'LIVE'}
      </span>
    )
  }

  if (status === 'finished') {
    return (
      <span className="shrink-0 rounded-full bg-raised px-1.5 py-0.5 text-[10px] font-semibold text-faint">
        KẾT THÚC
      </span>
    )
  }

  return (
    <span className="shrink-0 rounded-full border border-line px-1.5 py-0.5 text-[10px] font-medium tabular-nums text-muted">
      {kickoff}
    </span>
  )
}

/**
 * Băng xuất xứ.
 *
 * Chỉ hiện khi dữ liệu KHÔNG tươi. Một băng "đang cập nhật trực tiếp" hiện
 * suốt ngày sẽ bị mắt bỏ qua sau vài lần, và đến lúc nó thật sự cần nói điều
 * gì thì không ai còn đọc nữa.
 */
function SourceBanner({
  source,
  cachedAt,
  offline
}: {
  source: FootballSource | null
  cachedAt: string
  offline: boolean
}): JSX.Element | null {
  if (offline || source === null || source === 'live' || source === 'cache') {
    return null
  }

  const time = cachedAt
    ? new Date(cachedAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
    : ''

  const text =
    source === 'unavailable'
      ? 'Chưa lấy được dữ liệu — kiểm tra FOOTBALL_API_KEY trong tệp .env.'
      : `Dữ liệu cũ, cập nhật lúc ${time}. Đã hết hạn mức gọi trong ngày.`

  return (
    <div className="flex shrink-0 items-start gap-2 border-b border-warn/20 bg-warn/8 px-3 py-1.5">
      <AlertIcon className="mt-px h-3.5 w-3.5 shrink-0 text-warn" />
      <p className="text-[11px] leading-relaxed text-warn">{text}</p>
    </div>
  )
}

function EmptyDay({
  date,
  dayOffset,
  filtered
}: {
  date: string
  dayOffset: number
  filtered: boolean
}): JSX.Element {
  return (
    <div className="flex flex-col items-center px-8 py-16 text-center">
      <span className="flex h-14 w-14 animate-float items-center justify-center rounded-full bg-raised text-faint">
        <BallIcon className="h-7 w-7" />
      </span>
      <p className="mt-4 text-[13px] text-muted">
        Không có trận nào {dayOffset === 0 ? 'hôm nay' : `ngày ${date}`}.
      </p>
      {filtered && (
        <p className="mt-1.5 text-[11.5px] leading-relaxed text-faint">
          Bạn đang lọc theo một giải — chọn “Tất cả” để xem mọi trận.
        </p>
      )}
    </div>
  )
}

function OfflineNote(): JSX.Element {
  return (
    <div className="px-6 py-14 text-center">
      <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-danger/10 text-danger">
        <AlertIcon className="h-7 w-7" />
      </span>
      <p className="mt-4 text-[13px] text-muted">Không kết nối được football-service.</p>
      <p className="mt-1.5 text-[11.5px] leading-relaxed text-faint">
        Service chạy ở cổng 8090 và nằm trong profile riêng:
      </p>
      <code className="selectable mt-2 block rounded-lg bg-raised px-2 py-1.5 text-[10.5px] leading-relaxed text-muted">
        docker compose --profile football up -d
      </code>
    </div>
  )
}

function PanelSkeleton(): JSX.Element {
  return (
    <div aria-hidden="true">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="border-b border-line/50 px-3 py-2.5">
          <div className="skeleton mb-2 h-3 w-16 rounded-full" />
          <div className="mb-1.5 flex items-center gap-2">
            <div className="skeleton h-[22px] w-[22px] rounded-full" />
            <div className="skeleton h-3 flex-1" />
            <div className="skeleton h-[22px] w-[22px] rounded-md" />
          </div>
          <div className="flex items-center gap-2">
            <div className="skeleton h-[22px] w-[22px] rounded-full" />
            <div className="skeleton h-3 flex-1" />
            <div className="skeleton h-[22px] w-[22px] rounded-md" />
          </div>
        </div>
      ))}
    </div>
  )
}

export default FootballPanel
