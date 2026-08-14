import { useEffect, useState, type JSX } from 'react'
import { fetchPlayers, type FootballPlayer } from '../../lib/footballApi'
import { useFootballAppStore } from '../../store/footballAppStore'
import { ChevronRightIcon, UsersIcon } from '../icons'
import {
  EmptyState,
  GlassListRow,
  GlassSearchField,
  LoadingState,
  OfflineState,
  ScreenTitle
} from './glass'
import { useResource } from './useResource'

const SEARCH_DEBOUNCE_MS = 350

/** Ngưỡng của chính nhà cung cấp: `/players/profiles` đòi ít nhất 3 ký tự. */
const MIN_QUERY = 3

/**
 * Tab Cầu thủ — bản chuyển của `PlayerSearchView`.
 *
 */
function PlayersTab(): JSX.Element {
  const [query, setQuery] = useState('')
  const debounced = useDebounced(query.trim(), SEARCH_DEBOUNCE_MS)
  const push = useFootballAppStore((s) => s.push)

  const short = debounced.length < MIN_QUERY
  const players = useResource<FootballPlayer[]>(`players|${debounced}`, async () => {
    if (debounced.length < MIN_QUERY) {
      return []
    }
    return fetchPlayers(debounced)
  })

  const searching = !short && (players.loading || query.trim() !== debounced)

  return (
    <>
      <header className="mb-4 flex flex-col gap-3.5">
        <ScreenTitle>Cầu thủ</ScreenTitle>
        <GlassSearchField
          value={query}
          onChange={setQuery}
          placeholder="Tìm cầu thủ…"
          searching={searching}
          autoFocus
        />
      </header>

      {short && (
        <div className="flex flex-col items-center px-8 py-10 text-center">
          <UsersIcon className="h-12 w-12" style={{ color: 'rgba(0,200,83,0.5)' }} />
          <p className="mt-4 text-[17px] font-semibold text-white/85">Tìm bất kỳ cầu thủ nào</p>
          <p className="mt-1.5 text-[13px] text-white/45">Gõ ít nhất {MIN_QUERY} ký tự</p>
        </div>
      )}

      {!short && players.loading && <LoadingState />}
      {!short && !players.loading && players.failed && <OfflineState />}
      {!short && !players.loading && !players.failed && (players.data ?? []).length === 0 && (
        <EmptyState message={`Không tìm thấy cầu thủ nào khớp “${debounced}”.`} />
      )}

      <div className="flex flex-col gap-2.5">
        {(players.data ?? []).map((player, index) => (
          <GlassListRow
            key={player.id}
            index={index}
            onClick={() => push({ kind: 'player', player })}
            title={`Xem hồ sơ ${player.name}`}
          >
            <PlayerPhoto photo={player.photo} size={44} />

            <span className="min-w-0 flex-1">
              <span className="block truncate text-[14.5px] font-semibold text-white">
                {player.name}
              </span>
              <span className="block truncate text-[12px] text-white/50">{describe(player)}</span>
            </span>

            <ChevronRightIcon className="h-3.5 w-3.5 shrink-0 text-white/25" />
          </GlassListRow>
        ))}
      </div>
    </>
  )
}

/** "Tiền đạo · Liverpool · Ai Cập" — bỏ qua phần nào không có. */
function describe(player: FootballPlayer): string {
  const stat = player.statistics[0]
  return [stat?.position, stat?.teamName, player.nationality].filter(Boolean).join(' · ')
}

/** Ảnh cầu thủ, lùi về biểu tượng người khi nhà cung cấp không có ảnh. */
export function PlayerPhoto({ photo, size }: { photo: string; size: number }): JSX.Element {
  return (
    <span
      className="flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-white/10"
      style={{ width: size, height: size }}
    >
      {photo ? (
        <img src={photo} alt="" loading="lazy" className="h-full w-full object-cover" />
      ) : (
        <UsersIcon className="text-white/45" style={{ width: size * 0.45, height: size * 0.45 }} />
      )}
    </span>
  )
}

function useDebounced(value: string, delay: number): string {
  const [settled, setSettled] = useState(value)

  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])

  return settled
}

export default PlayersTab
