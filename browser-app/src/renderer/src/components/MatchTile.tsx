import { useEffect, useMemo, useState, type JSX } from 'react'
import { fetchRecentResults, isImageEmblem, type FootballMatch } from '../lib/footballApi'
import { useFootballAppStore } from '../store/footballAppStore'
import { BallIcon } from './icons'

/** Bao lâu thì đổi sang trận khác. */
const ROTATE_MS = 8000

/** Bao lâu thì hỏi lại lịch thi đấu. */
const REFRESH_MS = 60_000

/** Số trận nhiều nhất được luân phiên. */
const SPOTLIGHT_SIZE = 5

/**
 * Ô tỉ số nổi ở góc trên bên PHẢI khối hero — đối xứng với ô thời tiết bên
 * trái, cùng độ cao, cùng hình dáng.
 *
 * <h3>Vì sao không nằm trong hàng lối tắt</h3>
 *
 * Bản trước đặt nó thành một ô trong hàng lối tắt. Sai ở chỗ hàng ấy là những
 * thứ NGƯỜI DÙNG tự thêm và tự sắp; một ô nội dung tự đổi, tự biến mất khi
 * service tắt, chen vào giữa thì vừa phá tính đối xứng của hàng vừa khiến
 * người ta tưởng mình lỡ tay ghim nhầm một trang.
 *
 * Ô thời tiết mới là họ hàng đúng của nó: cùng là một mẩu thông tin tự cập
 * nhật, không ai thêm vào cũng không ai xoá đi, nổi trên nền hero và không
 * chiếm chỗ của bố cục chính. Nên nó dùng LẠI y nguyên hình dáng ấy — cùng bo
 * góc, cùng viền, cùng nền mờ, cùng chiều cao — và chỉ đổi bên.
 *
 * Nội dung là KẾT QUẢ, không phải lịch: nó lùi lại từng ngày cho tới khi gặp
 * một ngày đã có tỉ số — xem `fetchRecentResults`. Một ô tỉ số hiện "– –" kèm
 * giờ bóng lăn là một lời hẹn, không phải một câu trả lời.
 *
 * <h3>Vì sao nó luân phiên nhiều trận thay vì ghim một trận</h3>
 *
 * Một ngày có tới cả chục trận, còn chỗ này chỉ đủ cho một. Ghim cứng một trận
 * nghĩa là chín phần mười thời gian nó hiện thứ người xem không quan tâm. Luân
 * phiên tám giây một trận biến chỗ hẹp ấy thành một bản tóm tắt cả ngày.
 *
 * Thứ tự luân phiên KHÔNG ngẫu nhiên: trận đang đá trước, rồi trận sắp đá, rồi
 * trận vừa xong — xem `rankForSpotlight`.
 *
 * <h3>Vì sao hỏng thì biến mất hoàn toàn</h3>
 *
 * football-service nằm trong một profile riêng và có thể không chạy. Một ô báo
 * lỗi đỏ ngay giữa trang chủ, mỗi lần mở thẻ mới, chỉ để nói rằng một tính năng
 * tuỳ chọn đang tắt — đó là biến một lựa chọn thành một lời than phiền.
 */
function MatchTile(): JSX.Element | null {
  const [matches, setMatches] = useState<FootballMatch[]>([])
  const [index, setIndex] = useState(0)

  const openFootballApp = useFootballAppStore((s) => s.openApp)

  useEffect(() => {
    let cancelled = false

    const load = (): void => {
      void fetchRecentResults()
        .then((results) => {
          if (!cancelled) {
            setMatches(results.slice(0, SPOTLIGHT_SIZE))
          }
        })
        .catch(() => {
          if (!cancelled) {
            setMatches([])
          }
        })
    }

    load()
    const timer = window.setInterval(load, REFRESH_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  useEffect(() => {
    if (matches.length <= 1) {
      return undefined
    }
    const timer = window.setInterval(() => {
      // Chốt theo ĐỘ DÀI hiện tại chứ không theo `matches` bắt được lúc effect
      // chạy: lô mới về có thể ngắn hơn, và một chỉ số trỏ ra ngoài mảng sẽ
      // làm ô trống trơn trong tám giây.
      setIndex((current) => (current + 1) % matches.length)
    }, ROTATE_MS)
    return () => window.clearInterval(timer)
  }, [matches.length])

  const match = matches[index % Math.max(1, matches.length)]

  const kickoffLabel = useMemo(() => {
    if (!match) {
      return ''
    }
    const kickoff = new Date(match.kickoff)
    return Number.isNaN(kickoff.getTime())
      ? '--:--'
      : kickoff.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
  }, [match])

  if (!match) {
    return null
  }

  const live = match.status === 'live'
  const finished = match.status === 'finished'
  const scored = match.homeScore !== null && match.awayScore !== null

  return (
    <button
      onClick={() => openFootballApp('home')}
      // Cùng viền, cùng nền mờ, cùng bo góc và cùng mốc trên với ô thời tiết —
      // hai khối phải trông là một cặp. Chỉ khác CHIỀU CAO: ô này gấp đôi, vì
      // nó phải chứa ba dòng.
      //
      // Ba dòng chứ không phải một: nhồi cả hai tên đội, tỉ số và tên giải vào
      // một dòng thì tên nào cũng phải cắt bớt, và "Manchester City" thành
      // "Manches…" là một ô tỉ số không nói được điều nó sinh ra để nói. Mỗi
      // đội một dòng thì tên hiện đủ, tỉ số thẳng cột, và mắt đọc ra ngay ai
      // đang dẫn.
      className="group absolute right-6 top-5 z-10 flex w-[300px] animate-fade-up flex-col gap-1.5
                 rounded-2xl border border-white/12 bg-white/8 px-4 py-3 text-left text-white
                 shadow-lg backdrop-blur-xl transition-colors duration-300 hover:border-brand/40
                 hover:bg-white/12 focus-visible:outline-none focus-visible:ring-2
                 focus-visible:ring-brand/60"
      title={`${match.homeTeam.name} vs ${match.awayTeam.name} — mở trang bóng đá`}
    >
      <span className="flex items-center gap-2">
        <BallIcon
          className={
            'h-4 w-4 shrink-0 text-brand ' + (live ? 'animate-spin [animation-duration:3.5s]' : '')
          }
        />
        <span className="min-w-0 flex-1 truncate text-[12px] text-white/65">
          {match.competition}
        </span>

        {live ? (
          <span className="flex shrink-0 items-center gap-1 text-[11px] font-bold text-brand">
            <span className="relative flex h-1.5 w-1.5">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand opacity-75" />
              <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-brand" />
            </span>
            {match.elapsed !== null ? `${match.elapsed}'` : 'LIVE'}
          </span>
        ) : (
          <span className="shrink-0 text-[11px] font-medium tabular-nums text-white/60">
            {finished ? 'Kết thúc' : kickoffLabel}
          </span>
        )}
      </span>

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
    </button>
  )
}

/**
 * Một dòng đội: huy hiệu, tên ĐẦY ĐỦ, tỉ số.
 *
 * Tên không bị cắt vì nó là thứ duy nhất ở đây không đoán được — tỉ số và giờ
 * thì nhìn cột khác vẫn ra, còn "Manches…" thì không.
 */
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
            className="h-[18px] w-[18px] object-contain"
            loading="lazy"
          />
        ) : (
          team.emblem || '⚽'
        )}
      </span>

      <span
        className={
          'min-w-0 flex-1 text-[13.5px] leading-tight ' +
          (leading ? 'font-semibold text-white' : 'text-white/85')
        }
      >
        {team.name}
      </span>

      <span
        className={
          'shrink-0 text-[15px] font-bold tabular-nums ' +
          (score === null ? 'text-white/45' : leading ? 'text-brand' : 'text-white')
        }
      >
        {score === null ? '–' : score}
      </span>
    </span>
  )
}

export default MatchTile
