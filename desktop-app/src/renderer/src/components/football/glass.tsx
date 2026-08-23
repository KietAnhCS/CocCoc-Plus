import { type JSX, type ReactNode } from 'react'
import { isImageEmblem, seasonLabel, type FootballMatch } from '../../lib/footballApi'
import { AlertIcon, BallIcon, ChevronLeftIcon, SearchIcon, SpinnerIcon } from '../icons'

/**
 * Bộ phận dùng chung của trang bóng đá — bản chuyển thư mục `UI/` của
 * FootballTracker (SwiftUI) sang CSS.
 *
 * <h3>Quy tắc quan trọng nhất, lấy nguyên từ bản gốc</h3>
 *
 * KHÔNG lồng kính trong kính. `.glassEffect()` của Apple làm mờ thứ phía sau;
 * hai lớp mờ chồng lên nhau cho ra một mảng xám đục và mất hẳn cảm giác trong
 * suốt. Nội dung nằm bên trong một tấm kính phải dùng nền phẳng
 * `.glass-inner`. Ba lớp tiện ích `.glass`, `.glass-tinted`, `.glass-inner`
 * nằm trong `index.css`.
 */

/** Màu nhấn của bản gốc — `Theme.accentGlass`. */
export const ACCENT = '#00C853'

/** Màu dành riêng cho trận đang đá — `Theme.accentLive`. */
export const ACCENT_LIVE = '#FF5252'

/**
 * Nền toàn màn hình: ảnh thật, phủ gradient tối.
 *
 * Ảnh thật chứ không phải một mảng màu, vì Liquid Glass chỉ có nghĩa khi có
 * thứ gì đó ĐỂ khúc xạ. Đặt kính lên một nền phẳng thì nó chỉ còn là một hình
 * chữ nhật xám nhạt.
 *
 * Ảnh để NÉT, và mỗi màn hình một ảnh — đúng như bản gốc. Chính sáu tấm ảnh
 * ấy (`img_bg_1`, `img_bg_3`…) được chép sang đây, không phải một tấm thay
 * thế.
 *
 * <h3>Bản trước làm mờ ảnh, và đó là một sai lầm</h3>
 *
 * Lý do khi ấy nghe hợp lý: cửa sổ trình duyệt rộng hơn điện thoại nhiều nên
 * ảnh chiếm gần hết khung hình, và ảnh nét thì tranh chỗ với chữ. Nhưng
 * `blur(7px)` cộng một lớp phủ đen 86% không giải quyết chuyện đó — nó xoá
 * luôn thứ khiến giao diện này đáng nhìn, và cho ra một mảng nâu xám mà không
 * ai nhận ra là sân bóng. Cùng lúc, kính đặt trên một nền đã phẳng thì không
 * còn gì để khúc xạ, nên toàn bộ hiệu ứng Liquid Glass cũng mất theo.
 *
 * Cách đúng là cách bản gốc dùng: giữ ảnh nét, và chỉ phủ một dốc đen vừa đủ
 * (35% ở đỉnh tới 72% ở đáy — đậm dần xuống dưới, nơi có nhiều chữ nhất). Chữ
 * vẫn đọc được vì mọi khối chữ đều nằm trên một tấm kính có nền riêng.
 */
export function AppBackground({ image, hue }: { image: string; hue: string }): JSX.Element {
  return (
    <div className="absolute inset-0 -z-10 overflow-hidden" aria-hidden="true">
      <img src={image} alt="" className="h-full w-full object-cover" />
      <div className="absolute inset-0 bg-linear-to-b from-black/35 via-black/55 to-black/72" />
      <div
        className="absolute inset-0"
        style={{
          background: `radial-gradient(ellipse 78% 52% at 50% 0%, ${hue}, transparent 70%)`
        }}
      />
    </div>
  )
}

/** `GlassCard` — tấm kính NGOÀI CÙNG của thứ nó bọc. */
export function GlassCard({
  children,
  className = '',
  tinted = false
}: {
  children: ReactNode
  className?: string
  tinted?: boolean
}): JSX.Element {
  return (
    <div className={(tinted ? 'glass-tinted' : 'glass') + ' rounded-[20px] ' + className}>
      {children}
    </div>
  )
}

/**
 * `GlassSegmentedControl` — ô chọn mùa giải.
 *
 * Bản gốc dùng `matchedGeometryEffect` để viên thuốc trắng TRƯỢT giữa các
 * đoạn. Ở đây làm bằng một phần tử tuyệt đối rộng 1/n, dịch bằng `translateX`
 * — cùng hiệu ứng, và cùng lý do: mắt bám theo được vật đang di chuyển, còn
 * một khối màu nhảy cóc thì phải phát hiện lại từ đầu.
 */
export function SegmentedControl<T extends string | number>({
  items,
  selected,
  onSelect,
  label
}: {
  items: { id: T; label: string }[]
  selected: T
  onSelect: (id: T) => void
  label: string
}): JSX.Element {
  const index = Math.max(
    0,
    items.findIndex((item) => item.id === selected)
  )

  return (
    <div className="glass relative flex rounded-full p-1" role="tablist" aria-label={label}>
      {/* Viên thuốc XANH, không phải trắng: đó là màu trong ảnh chụp bản gốc,
          và nó cũng đúng hơn — trắng đặc trên nền kính trắng mờ thì mục đang
          chọn chỉ khác các mục kia ở độ sáng, còn xanh thì khác cả sắc, nên
          mắt bắt được ngay cả khi liếc qua. */}
      <span
        aria-hidden="true"
        className="absolute inset-y-1 left-1 rounded-full shadow-lg transition-transform duration-300 ease-out"
        style={{
          width: `calc((100% - 0.5rem) / ${items.length})`,
          transform: `translateX(${index * 100}%)`,
          background: ACCENT,
          boxShadow: '0 4px 14px rgba(0,200,83,0.45)'
        }}
      />
      {items.map((item) => (
        <button
          key={item.id}
          role="tab"
          onClick={() => onSelect(item.id)}
          aria-selected={item.id === selected}
          className={
            'relative flex-1 rounded-full py-2 text-[13px] font-bold transition-colors duration-200 ' +
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60 ' +
            (item.id === selected ? 'text-white' : 'text-white/60 hover:text-white/85')
          }
        >
          {item.label}
        </button>
      ))}
    </div>
  )
}

/**
 * Nhãn mùa giải.
 *
 * Trước đây chỗ này là một ô CHỌN ba mùa. Nó bị hạ xuống thành nhãn vì nhà
 * cung cấp hiện tại bỏ qua tham số mùa — xem `seasonOf` trong `footballApi`.
 * Một cái nhãn nói đúng sự thật hơn hẳn một cái nút bấm vào không đổi gì.
 */
export function SeasonBadge({ season }: { season: number | null }): JSX.Element | null {
  if (season === null) {
    return null
  }
  return (
    <span
      className="glass inline-flex items-center gap-2 self-center rounded-full px-4 py-1.5
                 text-[12.5px] font-bold text-white"
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: ACCENT }} />
      Mùa {seasonLabel(season)}
    </span>
  )
}

/** `GlassSearchField` — ô tìm kiếm nằm TRỰC TIẾP trên nền, nên nó tự là kính. */
export function GlassSearchField({
  value,
  onChange,
  placeholder,
  searching,
  autoFocus
}: {
  value: string
  onChange: (value: string) => void
  placeholder: string
  searching?: boolean
  autoFocus?: boolean
}): JSX.Element {
  return (
    <div className="glass flex h-[46px] items-center gap-3 rounded-full px-4">
      <SearchIcon className="h-4 w-4 shrink-0 text-white/50" />
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="min-w-0 flex-1 bg-transparent text-[14px] text-white placeholder:text-white/45 focus:outline-none"
        placeholder={placeholder}
        spellCheck={false}
        aria-label={placeholder}
        autoFocus={autoFocus}
      />
      {searching === true && <SpinnerIcon className="h-4 w-4 shrink-0 text-white/70" />}
    </div>
  )
}

/** `GlassListRow` — mỗi dòng danh sách tự là một tấm kính nổi trên nền. */
export function GlassListRow({
  children,
  onClick,
  title,
  index = 0
}: {
  children: ReactNode
  onClick?: () => void
  title?: string
  index?: number
}): JSX.Element {
  const className =
    'glass flex w-full items-center gap-3.5 rounded-2xl px-4 py-3 text-left animate-rise-in ' +
    'transition-transform duration-300 focus-visible:outline-none focus-visible:ring-2 ' +
    'focus-visible:ring-white/50 ' +
    (onClick ? 'hover:-translate-y-0.5' : '')
  const style = { animationDelay: `${Math.min(index, 10) * 35}ms` }

  if (onClick === undefined) {
    return (
      <div className={className} style={style}>
        {children}
      </div>
    )
  }
  return (
    <button className={className} style={style} onClick={onClick} title={title}>
      {children}
    </button>
  )
}

/**
 * Huy hiệu đội hoặc biểu trưng giải.
 *
 * Xét cả hai dạng vì service trả về cả hai: dữ liệu thật từ API-Football cho
 * một địa chỉ ảnh, còn dữ liệu mẫu cho một emoji.
 */
export function Crest({
  emblem,
  size,
  fallback = '⚽'
}: {
  emblem: string
  size: number
  fallback?: string
}): JSX.Element {
  return (
    <span
      className="flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-white/10"
      style={{ width: size, height: size, fontSize: Math.round(size * 0.52) }}
    >
      {isImageEmblem(emblem) ? (
        <img
          src={emblem}
          alt=""
          loading="lazy"
          className="object-contain"
          style={{ width: Math.round(size * 0.76), height: Math.round(size * 0.76) }}
        />
      ) : (
        emblem || fallback
      )}
    </span>
  )
}

/** Tiêu đề màn hình — 28px, đậm, bo tròn, căn giữa. Đúng bản gốc. */
export function ScreenTitle({ children }: { children: ReactNode }): JSX.Element {
  return (
    <h1 className="text-center font-display text-[28px] font-bold tracking-tight text-white drop-shadow-[0_2px_10px_rgba(0,0,0,0.5)]">
      {children}
    </h1>
  )
}

/** Tiêu đề một khối bên trong màn hình — 18px, đậm, căn trái. */
export function SectionTitle({ children }: { children: ReactNode }): JSX.Element {
  return <h2 className="font-display text-[17px] font-bold text-white drop-shadow">{children}</h2>
}

/** Nút quay lại của các màn hình chi tiết — `.buttonStyle(.glass)` bản gốc. */
export function BackButton({ onClick }: { onClick: () => void }): JSX.Element {
  return (
    <button
      onClick={onClick}
      className="glass flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-white/85
                 transition hover:text-white focus-visible:outline-none focus-visible:ring-2
                 focus-visible:ring-white/60"
      aria-label="Quay lại"
      title="Quay lại (Esc)"
    >
      <ChevronLeftIcon className="h-4 w-4" strokeWidth={2.2} />
    </button>
  )
}

/** Vòng quay chờ — `ProgressView().tint(#00C853)`. */
export function LoadingState(): JSX.Element {
  return (
    <div className="flex min-h-[200px] items-center justify-center" role="status">
      <SpinnerIcon className="h-7 w-7" style={{ color: ACCENT }} />
      <span className="sr-only">Đang tải…</span>
    </div>
  )
}

/** `ErrorStateView` — không gọi được service, kèm nút thử lại. */
export function ErrorState({
  message,
  onRetry
}: {
  message: string
  onRetry?: () => void
}): JSX.Element {
  return (
    <div className="flex min-h-[200px] flex-col items-center justify-center px-6 text-center">
      <AlertIcon className="h-9 w-9 text-white/35" />
      <p className="mt-3 text-[13px] leading-relaxed text-white/55">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-3 text-[13.5px] font-semibold transition hover:opacity-80"
          style={{ color: ACCENT }}
        >
          Thử lại
        </button>
      )}
    </div>
  )
}

/** `EmptyStateView` — gọi được, nhưng không có gì để hiện. */
export function EmptyState({ message }: { message: string }): JSX.Element {
  return (
    <div className="flex min-h-[200px] flex-col items-center justify-center px-6 text-center">
      <BallIcon className="h-9 w-9 animate-float text-white/30" />
      <p className="mt-3 text-[13px] leading-relaxed text-white/55">{message}</p>
    </div>
  )
}

/**
 * Không nối được football-service.
 *
 * Nói thẳng câu lệnh để bật nó lên: service nằm trong một profile riêng của
 * docker compose, nên "phần lớn thời gian nó không chạy" là trạng thái bình
 * thường chứ không phải sự cố, và người gặp màn hình này cần một việc để làm
 * chứ không cần một lời xin lỗi.
 */
export function OfflineState(): JSX.Element {
  return (
    <div className="glass mx-auto my-6 flex max-w-[380px] flex-col items-center rounded-3xl px-7 py-10 text-center">
      <span
        className="flex h-14 w-14 items-center justify-center rounded-full"
        style={{ background: 'rgba(255,82,82,0.15)', color: '#FF8A80' }}
      >
        <AlertIcon className="h-7 w-7" />
      </span>
      <p className="mt-4 text-[14px] font-semibold text-white">
        Không kết nối được football-service
      </p>
      <p className="mt-1.5 text-[12.5px] leading-relaxed text-white/60">
        Service chạy ở cổng 8090, nằm trong một profile riêng:
      </p>
      <code className="selectable glass-inner mt-3 rounded-xl px-3 py-2 text-[11.5px] text-white/85">
        docker compose --profile football up -d
      </code>
    </div>
  )
}

/** Băng cảnh báo khi dữ liệu là mẫu hoặc đã quá hạn. */
export function SourceNote({
  source,
  cachedAt
}: {
  source: string
  cachedAt: string
}): JSX.Element | null {
  if (source !== 'unavailable' && source !== 'stale') {
    return null
  }

  const time = cachedAt
    ? new Date(cachedAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
    : ''

  return (
    <div className="glass flex items-start gap-2.5 rounded-2xl px-3.5 py-2.5">
      <AlertIcon className="mt-px h-4 w-4 shrink-0 text-[#FFD54F]" />
      <p className="text-[12px] leading-relaxed text-white/75">
        {source === 'unavailable' ? (
          <>
            Chưa lấy được dữ liệu — kiểm tra <code className="text-white/90">FOOTBALL_API_KEY</code>{' '}
            trong tệp <code className="text-white/90">.env</code>.
          </>
        ) : (
          <>Dữ liệu cũ, cập nhật lúc {time}. Đã hết hạn mức gọi trong ngày.</>
        )}
      </p>
    </div>
  )
}

/**
 * `MatchCard` — thẻ trận đấu của bản iOS.
 *
 * Bố cục ba cột: đội nhà · tỉ số · đội khách, huy hiệu nằm TRÊN tên đội. Khác
 * hẳn kiểu hai dòng xếp chồng của bảng bên, và đó là chủ ý — bảng bên rộng
 * 340px nên phải xếp dọc, còn cột nội dung ở đây rộng 440px thì đủ chỗ cho bố
 * cục đối xứng, vốn là thứ khiến một thẻ tỉ số đọc được chỉ bằng một cái liếc.
 */
export function MatchCard({
  match,
  onOpen,
  index = 0
}: {
  match: FootballMatch
  onOpen: () => void
  index?: number
}): JSX.Element {
  const live = match.status === 'live'
  const finished = match.status === 'finished'

  const kickoff = new Date(match.kickoff)
  const valid = !Number.isNaN(kickoff.getTime())
  const timeLabel = valid
    ? kickoff.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
    : '--:--'
  const dateLabel = valid
    ? kickoff.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })
    : ''

  const badgeColor = live ? ACCENT_LIVE : finished ? 'rgba(255,255,255,0.45)' : ACCENT
  const badgeLabel = live
    ? match.elapsed !== null
      ? `${match.elapsed}'`
      : 'LIVE'
    : finished
      ? 'FT'
      : timeLabel

  return (
    <button
      onClick={onOpen}
      title={`${match.homeTeam.name} vs ${match.awayTeam.name} — mở trang xem`}
      className={
        'block w-full animate-rise-in rounded-[20px] text-left transition-transform duration-300 ' +
        'hover:-translate-y-0.5 focus-visible:outline-none focus-visible:ring-2 ' +
        'focus-visible:ring-white/50 ' +
        (live ? 'glass-tinted' : 'glass')
      }
      style={{ animationDelay: `${Math.min(index, 10) * 35}ms` }}
    >
      <div className="flex items-center gap-2 px-3.5 py-2">
        {isImageEmblem(match.competitionLogo) ? (
          <img
            src={match.competitionLogo}
            alt=""
            className="h-4 w-4 object-contain"
            loading="lazy"
          />
        ) : (
          <BallIcon className="h-3.5 w-3.5 text-white/50" />
        )}
        <span className="min-w-0 flex-1 truncate text-[11px] font-medium text-white/50">
          {match.competition}
          {match.round ? ` · ${match.round}` : ''}
        </span>
        <span
          className="flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-bold"
          style={{
            color: badgeColor,
            background: live ? 'rgba(255,82,82,0.14)' : 'rgba(255,255,255,0.07)'
          }}
        >
          {live && (
            <span className="relative flex h-1.5 w-1.5">
              <span
                className="absolute inline-flex h-full w-full animate-ping rounded-full opacity-75"
                style={{ background: ACCENT_LIVE }}
              />
              <span
                className="relative inline-flex h-1.5 w-1.5 rounded-full"
                style={{ background: ACCENT_LIVE }}
              />
            </span>
          )}
          {badgeLabel}
        </span>
      </div>

      <div className="h-px bg-white/6" />

      <div className="flex items-center py-3.5">
        <TeamColumn team={match.homeTeam} />

        <div className="flex w-[86px] shrink-0 flex-col items-center gap-1">
          {match.homeScore === null || match.awayScore === null ? (
            <>
              <span className="text-[15px] font-medium text-white/45">vs</span>
              <span className="text-[11px] tabular-nums text-white/35">
                {dateLabel} {timeLabel}
              </span>
            </>
          ) : (
            <>
              <span className="whitespace-nowrap font-display text-[22px] font-bold leading-none tabular-nums text-white">
                {match.homeScore} – {match.awayScore}
              </span>
              {live && match.elapsed !== null && (
                <span className="h-[3px] w-12 overflow-hidden rounded-full bg-white/20">
                  <span
                    className="block h-full rounded-full"
                    style={{
                      width: `${Math.min(100, (match.elapsed / 90) * 100)}%`,
                      background: ACCENT
                    }}
                  />
                </span>
              )}
            </>
          )}
        </div>

        <TeamColumn team={match.awayTeam} />
      </div>
    </button>
  )
}

function TeamColumn({ team }: { team: FootballMatch['homeTeam'] }): JSX.Element {
  return (
    <div className="flex min-w-0 flex-1 flex-col items-center gap-2 px-2">
      <Crest emblem={team.emblem} size={44} />
      <span className="line-clamp-2 text-center text-[12.5px] font-semibold leading-tight text-white">
        {team.shortName || team.name}
      </span>
    </div>
  )
}
