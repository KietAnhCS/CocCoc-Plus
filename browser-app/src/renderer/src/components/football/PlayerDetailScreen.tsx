import { type JSX, type ReactNode } from 'react'
import {
  fetchPlayer,
  isImageEmblem,
  seasonLabel,
  type FootballPlayer,
  type FootballPlayerStat
} from '../../lib/footballApi'
import { useFootballAppStore } from '../../store/footballAppStore'
import { AlertIcon, GlobeIcon, UserIcon } from '../icons'
import { ACCENT, BackButton, EmptyState, GlassCard, LoadingState, SectionTitle } from './glass'
import { PlayerPhoto } from './PlayersTab'
import { useResource } from './useResource'

/**
 * Hồ sơ một cầu thủ — bản chuyển của `PlayerDetailView`.
 *
 * Mở ra là đã có sẵn dữ liệu từ danh sách tìm kiếm, rồi mới gọi
 * `/players/{id}?season=` để lấy thống kê đầy đủ. Đó cũng là cách bản gốc làm
 * (`detailedPlayer ?? initialPlayer`), và nó giữ cho màn hình không bao giờ
 * trắng: tên, ảnh, quốc tịch hiện ngay, phần số liệu điền vào sau.
 */
function PlayerDetailScreen({ player: initial }: { player: FootballPlayer }): JSX.Element {
  const pop = useFootballAppStore((s) => s.pop)

  const detailed = useResource<FootballPlayer | null>(`player|${initial.id}`, async () =>
    fetchPlayer(initial.id)
  )

  const player = detailed.data ?? initial
  const stat = player.statistics[0]

  return (
    <>
      <div className="mb-4">
        <BackButton onClick={pop} />
      </div>

      <GlassCard className="px-6 py-7">
        <div className="flex flex-col items-center gap-4">
          <span
            className="rounded-full p-[2px]"
            style={{ boxShadow: `0 0 0 2px rgba(0,200,83,0.5)` }}
          >
            <PlayerPhoto photo={player.photo} size={96} />
          </span>

          <div className="flex flex-col items-center gap-2.5">
            <h1 className="text-center font-display text-[23px] font-bold tracking-tight text-white">
              {player.name}
            </h1>

            <div className="flex flex-wrap items-center justify-center gap-2">
              {player.nationality && (
                <Pill icon={<GlobeIcon className="h-3 w-3" />}>{player.nationality}</Pill>
              )}
              {player.age !== null && (
                <Pill icon={<UserIcon className="h-3 w-3" />}>{player.age} tuổi</Pill>
              )}
              {player.injured && (
                <Pill icon={<AlertIcon className="h-3 w-3" />} tone="#FF8A80">
                  Chấn thương
                </Pill>
              )}
            </div>
          </div>

          {stat && (
            <div className="mt-1 flex flex-wrap items-start justify-center gap-6">
              <StatBubble value={stat.goals} label="Bàn thắng" />
              <StatBubble value={stat.assists} label="Kiến tạo" />
              <StatBubble value={stat.appearances} label="Trận" />
              {stat.rating !== null && <StatBubble value={stat.rating.toFixed(1)} label="Điểm" />}
            </div>
          )}
        </div>
      </GlassCard>

      <div className="mb-3 mt-7">
        <SectionTitle>Câu lạc bộ</SectionTitle>
      </div>

      {detailed.loading && <LoadingState />}

      {!detailed.loading && player.statistics.length === 0 && (
        <EmptyState message="Nhà cung cấp không có số liệu cho cầu thủ này." />
      )}

      <div className="flex flex-col gap-2.5">
        {player.statistics.map((entry, index) => (
          <CareerRow
            key={`${entry.teamName}-${entry.leagueName}-${index}`}
            stat={entry}
            index={index}
          />
        ))}
      </div>
    </>
  )
}

function Pill({
  children,
  icon,
  tone = 'rgba(255,255,255,0.65)'
}: {
  children: ReactNode
  icon: ReactNode
  tone?: string
}): JSX.Element {
  return (
    <span
      className="glass-inner flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-medium"
      style={{ color: tone }}
    >
      {icon}
      {children}
    </span>
  )
}

function StatBubble({ value, label }: { value: number | string; label: string }): JSX.Element {
  return (
    <span className="flex min-w-[58px] flex-col items-center gap-0.5">
      <span className="font-display text-[21px] font-bold tabular-nums" style={{ color: ACCENT }}>
        {value}
      </span>
      <span className="text-[11px] text-white/50">{label}</span>
    </span>
  )
}

/** Một dòng sự nghiệp: đội, giải, và ba con số quan trọng nhất. */
function CareerRow({ stat, index }: { stat: FootballPlayerStat; index: number }): JSX.Element {
  return (
    <div
      className="glass flex animate-rise-in items-center gap-3.5 rounded-2xl px-4 py-3"
      style={{ animationDelay: `${Math.min(index, 10) * 35}ms` }}
    >
      <span className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-white/10">
        {isImageEmblem(stat.teamLogo) ? (
          <img src={stat.teamLogo} alt="" loading="lazy" className="h-7 w-7 object-contain" />
        ) : (
          <span className="text-[15px]">⚽</span>
        )}
      </span>

      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14px] font-semibold text-white">{stat.teamName}</span>
        <span className="block truncate text-[12px] text-white/50">
          {stat.leagueName}
          {stat.season !== null ? ` · ${seasonLabel(stat.season)}` : ''}
        </span>
      </span>

      <span className="flex shrink-0 gap-3.5">
        <MiniStat icon="⚽" value={stat.goals} label="bàn thắng" />
        <MiniStat icon="🅰️" value={stat.assists} label="kiến tạo" />
        <MiniStat icon="📅" value={stat.appearances} label="trận" />
      </span>
    </div>
  )
}

function MiniStat({
  icon,
  value,
  label
}: {
  icon: string
  value: number
  label: string
}): JSX.Element {
  return (
    <span className="flex flex-col items-center gap-0.5" title={`${value} ${label}`}>
      <span className="text-[11px]" aria-hidden="true">
        {icon}
      </span>
      <span className="text-[12px] font-bold tabular-nums text-white/85">{value}</span>
    </span>
  )
}

export default PlayerDetailScreen
