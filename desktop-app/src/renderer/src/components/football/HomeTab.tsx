import { type JSX } from 'react'
import {
  fetchTeamFixtures,
  watchUrl,
  type FootballMatch,
  type FootballTeam
} from '../../lib/footballApi'
import { searchImages, type ImageResultDto } from '../../lib/searchApi'
import { useFootballStore, type FavouriteTeam } from '../../store/footballStore'
import { useFootballAppStore } from '../../store/footballAppStore'
import { useTabStore } from '../../store/tabStore'
import { ShieldIcon, StarIcon } from '../icons'
import {
  Crest,
  ErrorState,
  GlassCard,
  LoadingState,
  MatchCard,
  ScreenTitle,
  SectionTitle
} from './glass'
import { useResource } from './useResource'

/** Truy vấn dùng để lấy dải tin — xem `NewsStrip`. */
const NEWS_QUERY = 'bóng đá'

/** Số thẻ tin trong dải ngang. */
const NEWS_SIZE = 8

/**
 * Tab Đội của tôi — bản chuyển của `TeamHomeView`.
 *
 * Giữ nguyên bố cục bản gốc: tiêu đề, ô chọn mùa, dải tin cuộn ngang, rồi mỗi
 * đội đã ghim là một khối gồm dòng tiêu đề đội và MỘT trận nổi bật.
 */
function HomeTab(): JSX.Element {
  const favourites = useFootballStore((s) => s.favourites)

  return (
    <>
      <header className="mb-4 flex flex-col gap-3.5">
        <ScreenTitle>Đội của tôi</ScreenTitle>
      </header>

      <NewsStrip />

      {favourites.length === 0 ? (
        <EmptyTeamPrompt />
      ) : (
        <FavouriteSections favourites={favourites} />
      )}
    </>
  )
}

/**
 * Dải tin cuộn ngang.
 *
 * <h3>Vì sao không bê nguyên `mockNews` của bản iOS</h3>
 *
 * Bản gốc ghim cứng ba bài báo bịa kèm ảnh Unsplash. Trên một màn hình demo
 * thì vô hại; trong một trình duyệt thật thì đó là ba tiêu đề trông y như tin
 * thật, bấm vào không đi đâu cả. Repo này lại sẵn có thứ tốt hơn hẳn: chỉ mục
 * do chính nó crawl về, và `/api/images` trả ra ảnh kèm tiêu đề và địa chỉ
 * trang gốc — tức là đúng hình dạng của một thẻ tin, nhưng là tin THẬT và bấm
 * được.
 *
 * Hỏng thì biến mất hoàn toàn: dải này lấy dữ liệu từ backend tìm kiếm (cổng
 * 8080), một tiến trình khác hẳn football-service (cổng 8090). Một ô báo lỗi
 * đỏ vì một tính năng phụ đang tắt là biến một lựa chọn thành một lời than
 * phiền.
 */
function NewsStrip(): JSX.Element | null {
  const navigate = useTabStore((s) => s.navigate)
  const news = useResource<ImageResultDto[]>('news', async () => {
    const response = await searchImages(NEWS_QUERY, 1, NEWS_SIZE)
    return response.results
  })

  if (news.loading || news.failed || news.data === null || news.data.length === 0) {
    return null
  }

  return (
    <section className="mb-6">
      <SectionTitle>Tin mới nhất</SectionTitle>
      <div className="no-scrollbar -mx-1 mt-3 flex gap-3 overflow-x-auto px-1 pb-1">
        {news.data.map((article) => (
          <button
            key={article.imageUrl}
            onClick={() => navigate(article.pageUrl)}
            title={article.pageTitle}
            className="glass w-[230px] shrink-0 overflow-hidden rounded-[20px] text-left
                       transition-transform duration-300 hover:-translate-y-0.5
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50"
          >
            <img
              src={article.imageUrl}
              alt={article.altText}
              loading="lazy"
              className="h-[110px] w-full object-cover"
            />
            <div className="p-3">
              <p className="line-clamp-2 text-[13.5px] font-semibold leading-snug text-white">
                {article.pageTitle}
              </p>
              <p className="mt-1.5 truncate text-[11px] text-white/50">{article.host}</p>
            </div>
          </button>
        ))}
      </div>
    </section>
  )
}

/**
 * Một khối cho mỗi đội đã ghim.
 *
 * Tải lịch của mọi đội trong MỘT lượt `Promise.all` thay vì mỗi đội một
 * `useResource`: số đội ghim thay đổi theo người dùng, mà số lần gọi hook thì
 * không được đổi giữa các lượt render.
 */
function FavouriteSections({ favourites }: { favourites: FavouriteTeam[] }): JSX.Element {
  const key = `home|${favourites.map((team) => team.id).join(',')}`

  const fixtures = useResource<Record<string, FootballMatch | null>>(key, async () => {
    const entries = await Promise.all(
      favourites.map(async (team) => {
        const id = team.id
        try {
          const envelope = await fetchTeamFixtures(id, team.leagueId)
          rememberProfile(id, envelope.data)
          return [id, featuredMatch(envelope.data)] as const
        } catch {
          // Một đội hỏng không được kéo cả trang xuống theo: người ghim năm
          // đội vẫn phải thấy bốn đội còn lại.
          return [id, null] as const
        }
      })
    )
    return Object.fromEntries(entries)
  })

  if (fixtures.loading) {
    return <LoadingState />
  }
  if (fixtures.failed) {
    return <ErrorState message="Không tải được lịch thi đấu." onRetry={fixtures.reload} />
  }

  return (
    <div className="flex flex-col gap-6">
      {favourites.map((team) => (
        <TeamSection key={team.id} team={team} match={fixtures.data?.[team.id] ?? null} />
      ))}
    </div>
  )
}

/**
 * Trận NỔI BẬT của một đội: trận có giờ bóng lăn gần hiện tại nhất, dù đã đá
 * xong hay còn ở tương lai.
 *
 * Đúng cách chọn của bản gốc (`matches.min(by: abs(timeIntervalSinceNow))`),
 * và nó hợp lý: giữa mùa thì đó là trận vừa đá tối qua hoặc trận cuối tuần
 * này — hai thứ người ta thật sự muốn biết.
 */
function featuredMatch(matches: FootballMatch[]): FootballMatch | null {
  let best: FootballMatch | null = null
  let bestDistance = Number.POSITIVE_INFINITY
  const now = Date.now()

  for (const match of matches) {
    const kickoff = new Date(match.kickoff).getTime()
    if (Number.isNaN(kickoff)) {
      continue
    }
    // Trận đang đá luôn thắng: khoảng cách của nó là 0 theo nghĩa người xem
    // quan tâm, dù giờ bóng lăn đã trôi qua 60 phút.
    const distance = match.status === 'live' ? 0 : Math.abs(kickoff - now)
    if (distance < bestDistance) {
      best = match
      bestDistance = distance
    }
  }
  return best
}

/**
 * Vá tên và huy hiệu thật cho một đội đã ghim.
 *
 * Danh sách ghim lưu ở localStorage có thể đến từ bản cũ, vốn chỉ lưu mã đội.
 * Lịch thi đấu vừa tải về LUÔN chứa đội ấy ở một trong hai phía, nên đây là
 * chỗ rẻ nhất để biết nó thật sự tên là gì.
 */
function rememberProfile(teamId: string, matches: FootballMatch[]): void {
  for (const match of matches) {
    const found: FootballTeam | undefined = [match.homeTeam, match.awayTeam].find(
      (team) => team.id === teamId
    )
    if (found) {
      useFootballStore.getState().rememberTeam({
        id: found.id,
        name: found.name,
        shortName: found.shortName || found.name,
        emblem: found.emblem,
        country: found.country ?? '',
        leagueId: found.leagueId ?? ''
      })
      return
    }
  }
}

function TeamSection({
  team,
  match
}: {
  team: FavouriteTeam
  match: FootballMatch | null
}): JSX.Element {
  const removeFavourite = useFootballStore((s) => s.removeFavourite)
  const push = useFootballAppStore((s) => s.push)
  const navigate = useTabStore((s) => s.navigate)

  return (
    <section>
      <div className="mb-3 flex items-center gap-3.5">
        <button
          onClick={() => push({ kind: 'team', team })}
          className="flex min-w-0 flex-1 items-center gap-3.5 text-left transition hover:opacity-85
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50"
          title={`Xem lịch của ${team.name}`}
        >
          <Crest emblem={team.emblem} size={44} />
          <span className="min-w-0">
            <span className="block truncate font-display text-[17px] font-bold text-white">
              {team.name}
            </span>
            <span className="block truncate text-[12px] text-white/50">{team.country}</span>
          </span>
        </button>

        <button
          onClick={() => removeFavourite(team.id)}
          className="glass flex h-9 w-9 shrink-0 items-center justify-center rounded-full
                     text-[#FFD54F] transition hover:scale-105 focus-visible:outline-none
                     focus-visible:ring-2 focus-visible:ring-white/60"
          aria-label={`Bỏ ghim ${team.name}`}
          title={`Bỏ ghim ${team.name}`}
        >
          <StarIcon className="h-4 w-4" filled />
        </button>
      </div>

      {match ? (
        <MatchCard match={match} onOpen={() => navigate(watchUrl(match))} />
      ) : (
        <GlassCard className="px-4 py-4">
          <p className="text-[12.5px] text-white/50">Mùa này chưa có trận nào để hiển thị.</p>
        </GlassCard>
      )}
    </section>
  )
}

/** `emptyTeamPrompt` — chưa ghim đội nào. */
function EmptyTeamPrompt(): JSX.Element {
  const setTab = useFootballAppStore((s) => s.setTab)

  return (
    <div className="flex flex-col items-center px-8 py-10 text-center">
      <ShieldIcon className="h-12 w-12" style={{ color: 'rgba(0,200,83,0.6)' }} />
      <p className="mt-4 text-[17px] font-semibold text-white/85">Chưa ghim đội nào</p>
      <p className="mt-1.5 text-[13px] leading-relaxed text-white/45">
        Sang tab Đội bóng để tìm và ghim đội bạn theo dõi.
      </p>
      <button
        onClick={() => setTab('teams')}
        className="glass-tinted mt-5 rounded-full px-5 py-2 text-[13.5px] font-bold text-white
                   transition hover:brightness-110 focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-white/60"
      >
        Mở tab Đội bóng
      </button>
    </div>
  )
}

export default HomeTab
