import { type JSX } from 'react'
import { fetchTeamFixtures, seasonOf, watchUrl, type FootballMatch } from '../../lib/footballApi'
import { isFavourite, useFootballStore, type FavouriteTeam } from '../../store/footballStore'
import { useFootballAppStore } from '../../store/footballAppStore'
import { useTabStore } from '../../store/tabStore'
import { StarIcon } from '../icons'
import {
  BackButton,
  Crest,
  EmptyState,
  ErrorState,
  LoadingState,
  MatchCard,
  SeasonBadge,
  SectionTitle,
  SourceNote
} from './glass'
import { useResource } from './useResource'

interface Loaded {
  matches: FootballMatch[]
  source: string
  cachedAt: string
}

/**
 * Trang một đội bóng — bản chuyển của `TeamDetailView`.
 *
 * Bản gốc đặt nút ghim trên thanh điều hướng của hệ thống. Ở đây không có
 * thanh ấy, nên nút nằm cùng hàng với nút quay lại — vẫn ở mép trên, vẫn là
 * hai thao tác duy nhất của màn hình này.
 */
function TeamDetailScreen({ team }: { team: FavouriteTeam }): JSX.Element {
  const pop = useFootballAppStore((s) => s.pop)
  const favourites = useFootballStore((s) => s.favourites)
  const toggleFavourite = useFootballStore((s) => s.toggleFavourite)
  const navigate = useTabStore((s) => s.navigate)

  const pinned = isFavourite(favourites, team.id)

  const fixtures = useResource<Loaded>(`team-fixtures|${team.id}`, async () => {
    const envelope = await fetchTeamFixtures(team.id, team.leagueId)
    return {
      matches: envelope.data,
      source: envelope.meta.source,
      cachedAt: envelope.meta.cachedAt
    }
  })

  return (
    <>
      <div className="mb-4 flex items-center justify-between">
        <BackButton onClick={pop} />
        <button
          onClick={() => toggleFavourite(team)}
          aria-pressed={pinned}
          className={
            'glass flex h-9 w-9 items-center justify-center rounded-full transition ' +
            'hover:scale-105 focus-visible:outline-none focus-visible:ring-2 ' +
            'focus-visible:ring-white/60 ' +
            (pinned ? 'text-[#FFD54F]' : 'text-white/60')
          }
          aria-label={pinned ? `Bỏ ghim ${team.name}` : `Ghim ${team.name}`}
          title={pinned ? `Bỏ ghim ${team.name}` : `Ghim ${team.name} lên tab Đội của tôi`}
        >
          <StarIcon className="h-4 w-4" filled={pinned} />
        </button>
      </div>

      <header className="mb-6 flex flex-col items-center gap-3">
        <Crest emblem={team.emblem} size={80} />
        <h1 className="text-center font-display text-[26px] font-bold tracking-tight text-white drop-shadow-[0_2px_10px_rgba(0,0,0,0.5)]">
          {team.name}
        </h1>
        {team.country !== '' && <p className="text-[13px] text-white/55">{team.country}</p>}
      </header>

      <div className="mb-4 flex flex-col gap-3">
        <SectionTitle>Lịch thi đấu</SectionTitle>
        <SeasonBadge season={seasonOf(fixtures.data?.matches ?? [])} />
      </div>

      {fixtures.loading && <LoadingState />}
      {!fixtures.loading && fixtures.failed && (
        <ErrorState message="Không tải được lịch của đội này." onRetry={fixtures.reload} />
      )}

      {fixtures.data && (
        <>
          <div className="mb-3">
            <SourceNote source={fixtures.data.source} cachedAt={fixtures.data.cachedAt} />
          </div>

          {fixtures.data.matches.length === 0 ? (
            <EmptyState message="Chưa có trận nào của đội này." />
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

export default TeamDetailScreen
