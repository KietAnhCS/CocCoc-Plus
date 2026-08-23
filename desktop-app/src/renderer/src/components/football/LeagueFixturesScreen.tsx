import { type JSX } from 'react'
import {
  fetchLeagueFixtures,
  seasonOf,
  watchUrl,
  type FootballLeague,
  type FootballMatch
} from '../../lib/footballApi'
import { useFootballAppStore } from '../../store/footballAppStore'
import { useTabStore } from '../../store/tabStore'
import {
  BackButton,
  Crest,
  EmptyState,
  ErrorState,
  LoadingState,
  MatchCard,
  SeasonBadge,
  SourceNote
} from './glass'
import { useResource } from './useResource'

interface Loaded {
  matches: FootballMatch[]
  source: string
  cachedAt: string
}

/**
 * Lịch cả mùa của một giải — bản chuyển của `LeagueFixturesView`.
 *
 * Dòng tiêu đề giữ đúng bản gốc: nút quay lại, biểu trưng giải, tên và quốc
 * gia; bên dưới là ô chọn mùa rồi danh sách thẻ trận.
 */
function LeagueFixturesScreen({ league }: { league: FootballLeague }): JSX.Element {
  const pop = useFootballAppStore((s) => s.pop)
  const navigate = useTabStore((s) => s.navigate)

  const fixtures = useResource<Loaded>(`league-fixtures|${league.id}`, async () => {
    const envelope = await fetchLeagueFixtures(league.id)
    return {
      matches: envelope.data,
      source: envelope.meta.source,
      cachedAt: envelope.meta.cachedAt
    }
  })

  return (
    <>
      <header className="mb-4 flex flex-col gap-3.5">
        <div className="flex items-center gap-3">
          <BackButton onClick={pop} />
          <Crest emblem={league.icon} size={42} fallback="🏆" />
          <div className="min-w-0 flex-1">
            <p className="truncate font-display text-[19px] font-bold text-white">{league.name}</p>
            <p className="truncate text-[12px] text-white/50">{league.country}</p>
          </div>
        </div>
        <SeasonBadge season={seasonOf(fixtures.data?.matches ?? [])} />
      </header>

      {fixtures.loading && <LoadingState />}
      {!fixtures.loading && fixtures.failed && (
        <ErrorState message="Không tải được lịch của giải này." onRetry={fixtures.reload} />
      )}

      {fixtures.data && (
        <>
          <div className="mb-3">
            <SourceNote source={fixtures.data.source} cachedAt={fixtures.data.cachedAt} />
          </div>

          {fixtures.data.matches.length === 0 ? (
            <EmptyState message="Giải này chưa có trận nào." />
          ) : (
            <div className="flex flex-col gap-3">
              {fixtures.data.matches.map((match, index) => (
                <MatchCard
                  key={match.id}
                  match={match}
                  index={index}
                  onOpen={() => navigate(watchUrl(match))}
                />
              ))}
            </div>
          )}
        </>
      )}
    </>
  )
}

export default LeagueFixturesScreen
