import { useMemo, useState, type JSX } from 'react'
import { fetchLeagues, isImageEmblem, type FootballLeague } from '../../lib/footballApi'
import { useFootballAppStore } from '../../store/footballAppStore'
import { ChevronRightIcon } from '../icons'
import {
  Crest,
  EmptyState,
  GlassSearchField,
  GlassListRow,
  LoadingState,
  OfflineState,
  ScreenTitle
} from './glass'
import { useResource } from './useResource'

/**
 * Tab Giải đấu — bản chuyển của `LeagueListView`.
 *
 * Bố cục bản gốc: tiêu đề, ô tìm kiếm kính, dải chip quốc gia cuộn ngang, rồi
 * danh sách giải. Bấm một giải mở màn hình lịch cả mùa của giải đó.
 *
 * Lọc ở MÁY KHÁCH, không gọi lại service: `/leagues` trả về toàn bộ danh sách
 * trong một lượt và nó gần như bất động (đệm 7 ngày). Gõ từng ký tự mà mỗi ký
 * tự là một request thì vừa chậm hơn vừa tiêu hạn mức cho một việc mà một phép
 * `includes` làm xong trong không tới một mili-giây.
 */
function LeaguesTab(): JSX.Element {
  const [query, setQuery] = useState('')
  const [country, setCountry] = useState('')
  const push = useFootballAppStore((s) => s.push)

  const leagues = useResource<FootballLeague[]>('leagues', async () => {
    const envelope = await fetchLeagues()
    return envelope.data
  })

  const countries = useMemo(() => {
    const names = new Set<string>()
    for (const league of leagues.data ?? []) {
      if (league.country) {
        names.add(league.country)
      }
    }
    return [...names].sort((a, b) => a.localeCompare(b, 'vi'))
  }, [leagues.data])

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (leagues.data ?? []).filter((league) => {
      if (country !== '' && league.country !== country) {
        return false
      }
      return needle === '' || league.name.toLowerCase().includes(needle)
    })
  }, [leagues.data, query, country])

  return (
    <>
      <header className="mb-4 flex flex-col gap-3.5">
        <ScreenTitle>Giải đấu</ScreenTitle>
        <GlassSearchField value={query} onChange={setQuery} placeholder="Tìm giải đấu…" />

        {countries.length > 0 && (
          <div className="no-scrollbar -mx-1 flex gap-2 overflow-x-auto px-1 pb-1">
            <CountryChip label="Tất cả" active={country === ''} onClick={() => setCountry('')} />
            {countries.map((name) => (
              <CountryChip
                key={name}
                label={name}
                active={country === name}
                onClick={() => setCountry(country === name ? '' : name)}
              />
            ))}
          </div>
        )}
      </header>

      {leagues.loading && <LoadingState />}
      {!leagues.loading && leagues.failed && <OfflineState />}
      {!leagues.loading && !leagues.failed && filtered.length === 0 && (
        <EmptyState message="Không có giải nào khớp bộ lọc." />
      )}

      <div className="flex flex-col gap-2.5">
        {filtered.map((league, index) => (
          <GlassListRow
            key={league.id}
            index={index}
            onClick={() => push({ kind: 'league', league })}
            title={`Xem lịch ${league.name}`}
          >
            <Crest emblem={league.icon} size={38} fallback="🏆" />

            <span className="min-w-0 flex-1">
              <span className="block truncate text-[14.5px] font-semibold text-white">
                {league.name}
              </span>
              <span className="mt-0.5 flex items-center gap-1.5">
                {isImageEmblem(league.flag) && (
                  <img
                    src={league.flag}
                    alt=""
                    loading="lazy"
                    className="h-3 w-4 shrink-0 rounded-[2px] object-cover"
                  />
                )}
                <span className="truncate text-[12px] text-white/50">{league.country}</span>
              </span>
            </span>

            {league.status === 'Active' && (
              <span
                className="shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold"
                style={{ background: 'rgba(0,200,83,0.2)', color: '#00E676' }}
              >
                ĐANG DIỄN RA
              </span>
            )}
            <ChevronRightIcon className="h-3.5 w-3.5 shrink-0 text-white/25" />
          </GlassListRow>
        ))}
      </div>
    </>
  )
}

function CountryChip({
  label,
  active,
  onClick
}: {
  label: string
  active: boolean
  onClick: () => void
}): JSX.Element {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={
        'shrink-0 whitespace-nowrap rounded-full px-3.5 py-1.5 text-[12.5px] font-semibold ' +
        'transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60 ' +
        (active ? 'glass-tinted text-white' : 'glass text-white/65 hover:text-white')
      }
    >
      {label}
    </button>
  )
}

export default LeaguesTab
