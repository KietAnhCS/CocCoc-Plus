import { useEffect, useState, type JSX } from 'react'
import { fetchRecentResults, isImageEmblem, type FootballMatch } from '../lib/footballApi'
import { useFootballAppStore } from '../store/footballAppStore'
import { useTabStore } from '../store/tabStore'
import { BallIcon, ChevronsRightIcon } from './icons'

/** Nhiều nhất bấy nhiêu trận trong một thẻ. */
const MAX_MATCHES = 3

/**
 * Từ khoá nói rằng người dùng đang hỏi về bóng đá nói chung, chứ không về một
 * đội cụ thể. Với những truy vấn này thì thẻ hiện các trận nổi bật trong ngày.
 */
const TOPIC_WORDS = [
  'bong da',
  'ti so',
  'ket qua bong da',
  'lich thi dau',
  'football',
  'ngoai hang'
]

/**
 * Bỏ dấu tiếng Việt và hạ chữ thường.
 *
 * Cần vì người ta gõ "bong da" nhiều không kém "bóng đá", và một phép so khớp
 * trên chuỗi nguyên vẹn sẽ trượt hết nửa số truy vấn thật.
 */
function fold(text: string): string {
  return text.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd')
}

/**
 * Thẻ tỉ số trên TRANG KẾT QUẢ TÌM KIẾM.
 *
 * <h3>Vì sao không hiện với mọi truy vấn</h3>
 *
 * Nó chỉ hiện khi truy vấn thật sự nói về bóng đá: hoặc chứa một từ khoá chủ
 * đề ("tỉ số", "bóng đá"), hoặc trùng tên một đội đang đá hôm nay. Một thẻ tỉ
 * số nằm trên đầu kết quả của truy vấn "giá vàng" thì không phải tiện ích mà
 * là quảng cáo — và sau vài lần, mắt sẽ bỏ qua nó cả những lúc nó đúng.
 *
 * <h3>Vì sao lọc ở máy khách</h3>
 *
 * Lịch trong ngày đã nằm sẵn trong đệm của service (một truy vấn cục bộ, không
 * đi ra ngoài), nên so khớp tên đội ngay tại đây rẻ hơn hẳn việc thêm một
 * endpoint tìm kiếm mới. Hỏng hay không khớp thì thẻ biến mất hoàn toàn: trang
 * kết quả không được phép vì một tính năng phụ mà mọc thêm một ô báo lỗi.
 */
function SearchMatchCard({ query }: { query: string }): JSX.Element | null {
  const [matches, setMatches] = useState<FootballMatch[]>([])
  const navigate = useTabStore((s) => s.navigate)
  const openFootballApp = useFootballAppStore((s) => s.openApp)

  useEffect(() => {
    const needle = fold(query).trim()
    if (needle.length < 3) {
      return undefined
    }

    let cancelled = false
    void fetchRecentResults()
      .then((results) => {
        if (cancelled) {
          return
        }

        const topical = TOPIC_WORDS.some((word) => needle.includes(word))
        const hit = results.filter((match) => {
          if (topical) {
            return true
          }
          return [
            match.homeTeam.name,
            match.awayTeam.name,
            match.homeTeam.shortName,
            match.awayTeam.shortName,
            match.competition
          ].some((name) => name !== '' && needle.includes(fold(name)))
        })

        setMatches(hit.slice(0, MAX_MATCHES))
      })
      .catch(() => {
        if (!cancelled) {
          setMatches([])
        }
      })

    return () => {
      cancelled = true
    }
  }, [query])

  if (matches.length === 0) {
    return null
  }

  return (
    <section
      className="mb-6 animate-fade-up overflow-hidden rounded-2xl border border-line bg-raised/40"
      aria-label="Tỉ số bóng đá"
    >
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5">
        <BallIcon className="h-4 w-4 shrink-0 text-brand" />
        <h2 className="min-w-0 flex-1 text-[12.5px] font-semibold text-ink">Kết quả gần nhất</h2>
        <button
          onClick={() => openFootballApp('home')}
          className="flex shrink-0 items-center gap-1 text-[11.5px] text-muted transition
                     hover:text-brand focus-visible:outline-none focus-visible:text-brand"
        >
          Mở trang bóng đá
          <ChevronsRightIcon className="h-3 w-3" />
        </button>
      </header>

      <ul>
        {matches.map((match) => (
          <MatchRow key={match.id} match={match} onOpen={() => navigate(watchOf(match))} />
        ))}
      </ul>
    </section>
  )
}

/** Địa chỉ mở khi bấm một trận — dùng lại quy tắc của `watchUrl`. */
function watchOf(match: FootballMatch): string {
  const query = `${match.homeTeam.name} vs ${match.awayTeam.name} ${match.competition} trực tiếp`
  return `https://www.google.com/search?q=${encodeURIComponent(query)}`
}

function MatchRow({ match, onOpen }: { match: FootballMatch; onOpen: () => void }): JSX.Element {
  const live = match.status === 'live'
  const finished = match.status === 'finished'
  const scored = match.homeScore !== null && match.awayScore !== null

  const kickoff = new Date(match.kickoff)
  const timeLabel = Number.isNaN(kickoff.getTime())
    ? '--:--'
    : kickoff.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })

  return (
    <li className="border-b border-line/60 last:border-b-0">
      <button
        onClick={onOpen}
        className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors
                   hover:bg-raised focus-visible:outline-none focus-visible:bg-raised"
        title={`${match.homeTeam.name} vs ${match.awayTeam.name} — mở trang xem`}
      >
        <span className="flex w-[86px] shrink-0 flex-col gap-0.5">
          <span className="truncate text-[11px] text-faint">{match.competition}</span>
          {live ? (
            <span className="flex items-center gap-1 text-[11px] font-bold text-brand">
              <span className="relative flex h-1.5 w-1.5">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand opacity-75" />
                <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-brand" />
              </span>
              {match.elapsed !== null ? `${match.elapsed}'` : 'LIVE'}
            </span>
          ) : (
            <span className="text-[11px] tabular-nums text-muted">
              {finished ? 'Kết thúc' : timeLabel}
            </span>
          )}
        </span>

        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <TeamLine
            team={match.homeTeam}
            score={scored ? match.homeScore : null}
            leading={scored && (match.homeScore ?? 0) > (match.awayScore ?? 0)}
          />
          <TeamLine
            team={match.awayTeam}
            score={scored ? match.awayScore : null}
            leading={scored && (match.awayScore ?? 0) > (match.homeScore ?? 0)}
          />
        </span>
      </button>
    </li>
  )
}

function TeamLine({
  team,
  score,
  leading
}: {
  team: FootballMatch['homeTeam']
  score: number | null
  leading: boolean
}): JSX.Element {
  return (
    <span className="flex items-center gap-2">
      <span className="flex h-[18px] w-[18px] shrink-0 items-center justify-center text-[13px]">
        {isImageEmblem(team.emblem) ? (
          <img
            src={team.emblem}
            alt=""
            loading="lazy"
            className="h-[18px] w-[18px] object-contain"
          />
        ) : (
          team.emblem || '⚽'
        )}
      </span>
      <span
        className={
          'min-w-0 flex-1 text-[13.5px] ' + (leading ? 'font-semibold text-ink' : 'text-ink')
        }
      >
        {team.name}
      </span>
      <span
        className={
          'shrink-0 text-[14px] font-bold tabular-nums ' +
          (score === null ? 'text-faint' : leading ? 'text-brand' : 'text-ink')
        }
      >
        {score === null ? '–' : score}
      </span>
    </span>
  )
}

export default SearchMatchCard
