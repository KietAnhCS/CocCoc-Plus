import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type JSX,
  type KeyboardEvent as ReactKeyboardEvent
} from 'react'
import AutocompleteDropdown from './AutocompleteDropdown'
import MatchTile from './MatchTile'
import { suggest } from '../lib/searchApi'
import { fetchFeed, type FeedCard } from '../lib/newsApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { useShortcutStore } from '../store/shortcutStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import {
  AlertIcon,
  CloseIcon,
  GlobeIcon,
  MicIcon,
  MoonCloudIcon,
  PlusIcon,
  SearchIcon,
  SpinnerIcon,
  SunCloudIcon,
  VnSearchMark
} from './icons'

const SUGGEST_DEBOUNCE_MS = 200

const SAMPLE_QUERIES = [
  'bóng đá Việt Nam',
  'giá vàng hôm nay',
  'trí tuệ nhân tạo',
  'site:vnexpress.net kinh tế'
]

/** Nhịp gõ của chỗ giữ chỗ động, tính bằng mili-giây cho mỗi ký tự. */
const TYPE_MS = 68
const ERASE_MS = 28
const HOLD_MS = 1700
const SWAP_MS = 320

/**
 * Toạ độ các hạt sáng trong nền hero — CỐ ĐỊNH, không phải `Math.random()`.
 *
 * Vị trí ngẫu nhiên sinh lúc render sẽ đổi mỗi lần React dựng lại khối này,
 * nên mở một thẻ mới rồi quay lại là cả đám hạt nhảy chỗ. Bảng cứng cho ra một
 * bầu trời luôn giống nhau; sự "ngẫu nhiên" mà mắt cần chỉ là các hạt không
 * thẳng hàng và không cùng chu kỳ.
 */
const PARTICLES = [
  { left: '7%', top: '26%', size: 3, delay: '0s', duration: '9s' },
  { left: '15%', top: '62%', size: 2, delay: '1.4s', duration: '11s' },
  { left: '23%', top: '14%', size: 2, delay: '2.9s', duration: '8s' },
  { left: '31%', top: '48%', size: 4, delay: '0.6s', duration: '12s' },
  { left: '39%', top: '73%', size: 2, delay: '3.6s', duration: '10s' },
  { left: '47%', top: '19%', size: 3, delay: '1.9s', duration: '13s' },
  { left: '56%', top: '57%', size: 2, delay: '0.3s', duration: '9s' },
  { left: '63%', top: '31%', size: 3, delay: '2.4s', duration: '11s' },
  { left: '71%', top: '68%', size: 2, delay: '4.1s', duration: '8s' },
  { left: '79%', top: '22%', size: 4, delay: '1.1s', duration: '12s' },
  { left: '87%', top: '52%', size: 2, delay: '3.1s', duration: '10s' },
  { left: '93%', top: '36%', size: 3, delay: '2.1s', duration: '14s' }
]

function NewTabPage(): JSX.Element {
  return (
    <div className="h-full overflow-y-auto overflow-x-hidden bg-surface">
      {/* KHÔNG đặt `overflow-hidden` ở đây: các quầng sáng đã bị cắt bởi chính
          `HeroBackdrop`, còn cắt thêm ở tầng này sẽ xén mất bảng gợi ý khi nó
          đổ xuống quá mép dưới khối hero. */}
      <section className="relative isolate">
        <HeroBackdrop />
        {/* Một cặp đối xứng ở hai góc trên của khối hero: thời tiết bên trái,
            tỉ số bên phải. Cùng độ cao, cùng hình dáng — xem `MatchTile`. */}
        <WeatherOverlay />
        <MatchTile />

        <div className="relative mx-auto flex max-w-3xl flex-col items-center px-8 pb-20 pt-20">
          <div className="flex animate-blur-in items-center gap-3.5">
            {/* Quầng sáng nằm SAU dấu hiệu, không phải `drop-shadow` quanh nó:
                bóng đổ bám theo đúng hình kính lúp nên cái tay cầm mảnh cũng
                kéo theo một vệt sáng gầy, trông như lỗi vẽ. Một vòng tròn mờ
                riêng cho ra quầng sáng tròn đều, đúng như mắt chờ đợi. */}
            <span className="relative flex h-12 w-12 shrink-0 items-center justify-center">
              <span className="absolute h-14 w-14 rounded-full bg-brand/25 blur-xl" />
              <VnSearchMark className="relative h-11 w-11 animate-float text-white" />
            </span>
            <h1 className="font-display text-[44px] font-semibold leading-none tracking-tight text-white drop-shadow-[0_2px_10px_rgba(0,0,0,0.45)]">
              Vn
              {/*
                Vệt sáng chạy qua chữ: nền chuyển sắc rộng gấp đôi khung chữ,
                bị cắt theo hình chữ, rồi trượt ngang mãi. Đây là chỗ duy nhất
                trên trang có chuyển động không ngừng ở tầng chữ — vì nó cũng
                là chữ duy nhất người dùng không cần đọc lại.
              */}
              <span
                className="animate-text-shine bg-clip-text text-transparent"
                style={{
                  backgroundImage:
                    'linear-gradient(100deg, #5eead4 10%, #a3e635 35%, #2dd482 60%, #5eead4 90%)',
                  backgroundSize: '220% auto'
                }}
              >
                Search
              </span>
            </h1>
          </div>

          <ShortcutRow />
          <HeroSearchBox />
        </div>
      </section>

      <HotNews />
    </div>
  )
}

/**
 * Nền khối hero — sáu lớp, mỗi lớp lo một việc.
 *
 *   1. dốc màu nền      — chiều sâu tĩnh, quyết định tông xanh lá của cả trang
 *   2. ba quầng cực quang — chuyển động chậm, lệch chu kỳ nhau
 *   3. lưới + hạt sáng   — kết cấu, để mảng gradient không trông "phẳng"
 *   4. hai dải lụa       — chia khối hero với phần tin, và tạo đường chân trời
 *   5. đèn rọi + tối viền — kéo mắt vào giữa, nơi có ô tìm kiếm
 *   6. vệt hoà           — nối khối hero vào nền của phần tin bên dưới
 *
 * <h3>Vì sao là dải lụa cong chứ không phải dãy núi</h3>
 *
 * Bản trước vẽ ba dãy núi bằng đường gấp khúc. Vấn đề không nằm ở ý tưởng mà ở
 * hình học: đường gấp khúc `preserveAspectRatio="none"` bị KÉO NGANG theo bề
 * rộng cửa sổ, nên mọi đỉnh núi méo thành những mũi nhọn xiên, và mỗi lần đổi
 * cỡ cửa sổ lại méo một kiểu khác. Đường cong Bézier bị kéo cùng cách ấy vẫn
 * là đường cong — nó chỉ thoải ra chứ không gãy. Đó là lý do gần như mọi trang
 * dùng hình nền co giãn đều dùng đường cong.
 *
 * Chuyển động chỉ có ở lớp 2 và 3, và đều chậm hơn 8 giây một chu kỳ. Nền
 * chuyển động nhanh sẽ tranh sự chú ý với đúng thứ nằm trên nó — ô tìm kiếm.
 */
function HeroBackdrop(): JSX.Element {
  return (
    <div className="absolute inset-0 -z-10 overflow-hidden" aria-hidden="true">
      <div
        className="absolute inset-0"
        style={{
          background:
            'radial-gradient(ellipse 130% 100% at 50% -18%, #0f3d29 0%, #082116 44%, #040f0a 100%)'
        }}
      />

      <span className="aurora-blob animate-aurora-a left-[-14%] top-[-30%] h-[78%] w-[56%] bg-[#12b46a]/40" />
      <span className="aurora-blob animate-aurora-b right-[-18%] top-[-16%] h-[88%] w-[52%] bg-[#0d9488]/34" />
      <span className="aurora-blob animate-aurora-c bottom-[-38%] left-[24%] h-[74%] w-[64%] bg-[#65a30d]/20" />

      <div className="grid-veil absolute inset-0" />

      {PARTICLES.map((particle) => (
        <span
          key={particle.left + particle.top}
          className="absolute animate-float rounded-full bg-white/45"
          style={{
            left: particle.left,
            top: particle.top,
            height: particle.size,
            width: particle.size,
            animationDelay: particle.delay,
            animationDuration: particle.duration,
            boxShadow: '0 0 9px rgba(110, 231, 200, 0.75)'
          }}
        />
      ))}

      <svg
        className="absolute inset-x-0 bottom-0 h-[46%] w-full"
        viewBox="0 0 1440 260"
        preserveAspectRatio="none"
      >
        <defs>
          <linearGradient id="ntp-silk-back" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#1d8f66" stopOpacity="0.55" />
            <stop offset="100%" stopColor="#062018" stopOpacity="0.9" />
          </linearGradient>
          <linearGradient id="ntp-silk-front" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#0c5a3f" stopOpacity="0.95" />
            <stop offset="100%" stopColor="#04120d" stopOpacity="1" />
          </linearGradient>
          {/* Đường viền sáng mờ dần về hai mép — sáng đều từ trái sang phải sẽ
              lộ ra rằng đó là một đường vẽ, chứ không phải một mép đón sáng. */}
          <linearGradient id="ntp-silk-edge" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#5eead4" stopOpacity="0" />
            <stop offset="30%" stopColor="#5eead4" stopOpacity="0.55" />
            <stop offset="62%" stopColor="#a3e635" stopOpacity="0.45" />
            <stop offset="100%" stopColor="#a3e635" stopOpacity="0" />
          </linearGradient>
        </defs>

        <path
          d="M0 96 C 210 34, 430 152, 670 110 C 910 68, 1150 162, 1440 92 L1440 260 L0 260 Z"
          fill="url(#ntp-silk-back)"
        />
        <path
          d="M0 160 C 270 106, 480 198, 730 162 C 990 124, 1210 202, 1440 154 L1440 260 L0 260 Z"
          fill="url(#ntp-silk-front)"
        />
        <path
          d="M0 160 C 270 106, 480 198, 730 162 C 990 124, 1210 202, 1440 154"
          fill="none"
          stroke="url(#ntp-silk-edge)"
          strokeWidth="1.6"
          vectorEffect="non-scaling-stroke"
        />
      </svg>

      {/* Đèn rọi mềm ngay sau ô tìm kiếm, rồi làm tối bốn góc. Hai lớp này
          không thêm chi tiết nào — chúng chỉ dựng lại thứ bậc: sáng nhất là
          chỗ cần nhìn, tối dần ra rìa. */}
      <div
        className="absolute inset-0"
        style={{
          background:
            'radial-gradient(ellipse 46% 40% at 50% 64%, rgba(94,234,212,0.16) 0%, rgba(94,234,212,0) 70%)'
        }}
      />
      <div
        className="absolute inset-0"
        style={{
          background:
            'radial-gradient(ellipse 78% 72% at 50% 45%, rgba(0,0,0,0) 55%, rgba(0,0,0,0.45) 100%)'
        }}
      />

      <div className="absolute inset-x-0 bottom-0 h-24 bg-linear-to-b from-transparent to-surface" />
    </div>
  )
}

function WeatherOverlay(): JSX.Element {
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const hour = now.getHours()
  const daytime = hour >= 6 && hour < 18
  const dateLabel = new Intl.DateTimeFormat('vi-VN', {
    weekday: 'long',
    day: 'numeric',
    month: 'long'
  }).format(now)
  const timeLabel = new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(now)

  return (
    <div
      className="absolute left-6 top-5 z-10 flex animate-fade-up items-center gap-3 rounded-2xl
                 border border-white/12 bg-white/8 px-4 py-2.5 text-white shadow-lg
                 backdrop-blur-xl transition-colors duration-300 hover:border-brand/40
                 hover:bg-white/12"
      title="Nhiệt độ là số tượng trưng — ứng dụng chưa nối với dịch vụ thời tiết."
    >
      {daytime ? (
        <SunCloudIcon className="h-7 w-7 animate-float text-lime-300" />
      ) : (
        <MoonCloudIcon className="h-7 w-7 animate-float text-emerald-200" />
      )}
      <div className="leading-tight">
        <p className="text-[17px] font-semibold">
          28°C <span className="font-normal text-white/70">· Hà Nội</span>
        </p>
        <p className="text-[12px] capitalize text-white/65">
          {dateLabel} · {timeLabel}
        </p>
      </div>
    </div>
  )
}

function ShortcutRow(): JSX.Element {
  const shortcuts = useShortcutStore((s) => s.shortcuts)
  const remove = useShortcutStore((s) => s.remove)
  const navigate = useTabStore((s) => s.navigate)
  const [adding, setAdding] = useState(false)

  return (
    <>
      <div className="mt-10 flex w-full flex-wrap items-start justify-center gap-1">
        <button
          onClick={() => setAdding(true)}
          className="group flex w-[92px] shrink-0 animate-pop-in flex-col items-center gap-2 rounded-2xl
                     p-2 transition-colors hover:bg-white/10 focus-visible:outline-none
                     focus-visible:ring-2 focus-visible:ring-brand/60"
          title="Thêm lối tắt mới"
        >
          <span
            className="flex h-12 w-12 items-center justify-center rounded-2xl border border-dashed
                       border-white/35 text-white/80 transition-all duration-300
                       group-hover:rotate-90 group-hover:border-brand/70 group-hover:text-brand-strong"
          >
            <PlusIcon className="h-5 w-5" />
          </span>
          <span className="w-full truncate text-center text-[12px] text-white/80">Thêm mới</span>
        </button>

        {shortcuts.map((shortcut, index) => (
          <div
            key={shortcut.id}
            className="group relative animate-pop-in"
            // Vào lệch nhau chứ không cùng lúc: cả hàng bật lên một lượt trông
            // như trang bị giật, còn lệch 45ms thì mắt đọc ra thành một chuyển
            // động có hướng. Chặn ở ô thứ 9 để hàng dài không kéo lê.
            style={{ animationDelay: `${Math.min(index + 1, 9) * 45}ms` }}
          >
            <button
              onClick={() => navigate(shortcut.url)}
              className="press flex w-[92px] shrink-0 flex-col items-center gap-2 rounded-2xl p-2
                         transition-colors hover:bg-white/10 focus-visible:outline-none
                         focus-visible:ring-2 focus-visible:ring-brand/60"
              title={shortcut.url}
            >
              {/* Ô đại diện: bốn lớp chồng lên nhau — nền chuyển sắc, vệt sáng
                  mặt trên, viền trong, rồi mới tới chữ. Ba lớp đầu là thứ phân
                  biệt "một ô màu có chữ" với "một biểu tượng": chúng dựng ra
                  một mặt cong đón sáng từ trên xuống. */}
              <span
                className="relative flex h-12 w-12 items-center justify-center overflow-hidden
                           rounded-[14px] text-lg font-bold text-white shadow-card
                           ring-1 ring-inset ring-white/25 transition-all duration-300
                           group-hover:-translate-y-1 group-hover:ring-white/40
                           group-hover:shadow-[0_12px_26px_rgba(45,212,132,0.38)]"
              >
                <span
                  className="absolute inset-0"
                  style={{ background: siteGradient(shortcut.url) }}
                />
                <span className="absolute inset-x-0 top-0 h-1/2 bg-linear-to-b from-white/28 to-transparent" />
                <span className="sheen" />
                <span className="relative drop-shadow-[0_1px_2px_rgba(0,0,0,0.35)]">
                  {siteInitial(shortcut.url)}
                </span>
              </span>
              <span className="w-full truncate text-center text-[12px] text-white/80 transition-colors group-hover:text-white">
                {shortcut.name}
              </span>
            </button>

            <button
              onClick={() => remove(shortcut.id)}
              className="absolute right-1 top-1 hidden h-5 w-5 items-center justify-center rounded-full
                         bg-black/55 text-white/80 backdrop-blur transition hover:scale-110
                         hover:bg-danger/80 hover:text-white focus-visible:outline-none group-hover:flex"
              aria-label={`Bỏ lối tắt ${shortcut.name}`}
              title="Bỏ lối tắt"
            >
              <CloseIcon className="h-2.5 w-2.5" strokeWidth={2.6} />
            </button>
          </div>
        ))}
      </div>

      {adding && <AddShortcutDialog onClose={() => setAdding(false)} />}
    </>
  )
}

function AddShortcutDialog({ onClose }: { onClose: () => void }): JSX.Element {
  const add = useShortcutStore((s) => s.add)
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  function submit(e: FormEvent): void {
    e.preventDefault()
    if (add(name, url)) {
      onClose()
    } else {
      setError('Địa chỉ không hợp lệ hoặc lối tắt đã có sẵn.')
    }
  }

  const fieldClass =
    'mt-1 h-9 w-full rounded-lg border border-line bg-omni px-3 text-[13px] text-ink ' +
    'transition-all duration-200 placeholder:text-faint focus:border-brand/60 ' +
    'focus:outline-none focus:ring-4 focus:ring-brand/15'

  return (
    <div className="fixed inset-0 z-50 flex animate-[fade-up_0.18s_ease-out] items-center justify-center bg-black/55 p-6 backdrop-blur-sm">
      <div className="absolute inset-0" onMouseDown={onClose} aria-hidden="true" />
      <form
        onSubmit={submit}
        className="relative w-[380px] animate-pop-in rounded-2xl border border-line bg-surface p-5 shadow-pop"
        role="dialog"
        aria-label="Thêm lối tắt"
      >
        <h2 className="text-[15px] font-semibold text-ink">Thêm lối tắt</h2>

        <label className="mt-4 block text-[12px] text-muted" htmlFor="shortcut-name">
          Tên
        </label>
        <input
          id="shortcut-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          className={fieldClass}
          placeholder="Để trống thì lấy theo tên miền"
          spellCheck={false}
          autoFocus
        />

        <label className="mt-3 block text-[12px] text-muted" htmlFor="shortcut-url">
          Địa chỉ
        </label>
        <input
          id="shortcut-url"
          value={url}
          onChange={(e) => {
            setUrl(e.target.value)
            setError('')
          }}
          className={fieldClass}
          placeholder="vnexpress.net"
          spellCheck={false}
        />

        {error && <p className="mt-2 animate-fade-up text-[12px] text-danger">{error}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-3.5 py-2 text-[13px] text-muted transition hover:bg-raised hover:text-ink"
          >
            Huỷ
          </button>
          <button
            type="submit"
            disabled={!url.trim()}
            className="press rounded-lg px-3.5 py-2 text-[13px] font-medium transition
                       enabled:bg-brand enabled:text-white enabled:shadow-glow enabled:hover:brightness-110
                       disabled:cursor-not-allowed disabled:bg-raised disabled:text-faint"
          >
            Thêm
          </button>
        </div>
      </form>
    </div>
  )
}

/**
 * Chỗ giữ chỗ tự gõ — gõ ra một truy vấn mẫu, giữ một nhịp, xoá đi, sang câu
 * kế tiếp.
 *
 * Ba lý do dùng nó thay cho một dòng chữ tĩnh: nó dạy cú pháp `site:` mà không
 * tốn thêm một dòng hướng dẫn; nó cho biết ứng dụng đang sống; và nó là chuyển
 * động DUY NHẤT bên trong ô tìm kiếm, nên không tranh chấp sự chú ý với chính
 * nó. Khi người dùng bắt đầu gõ, `active` thành `false` và mọi hẹn giờ dừng —
 * không có chuyện chữ giả chạy dưới chữ thật.
 */
function useTypedPlaceholder(active: boolean): string {
  const [index, setIndex] = useState(0)
  const [length, setLength] = useState(0)
  const [erasing, setErasing] = useState(false)

  useEffect(() => {
    if (!active) {
      return undefined
    }
    const word = SAMPLE_QUERIES[index]

    if (!erasing && length >= word.length) {
      const timer = window.setTimeout(() => setErasing(true), HOLD_MS)
      return () => window.clearTimeout(timer)
    }

    if (erasing && length <= 0) {
      const timer = window.setTimeout(() => {
        setErasing(false)
        setIndex((current) => (current + 1) % SAMPLE_QUERIES.length)
      }, SWAP_MS)
      return () => window.clearTimeout(timer)
    }

    const timer = window.setTimeout(
      () => setLength((current) => current + (erasing ? -1 : 1)),
      erasing ? ERASE_MS : TYPE_MS
    )
    return () => window.clearTimeout(timer)
  }, [active, index, length, erasing])

  return SAMPLE_QUERIES[index].slice(0, Math.max(0, length))
}

function HeroSearchBox(): JSX.Element {
  const [text, setText] = useState('')
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const [focused, setFocused] = useState(false)
  const [micNote, setMicNote] = useState(false)
  const runQuery = useSearchViewStore((state) => state.runSearch)

  const suggestible = text.trim().length > 0
  const typed = useTypedPlaceholder(text.length === 0)

  useEffect(() => {
    if (!suggestible) {
      return undefined
    }
    const timer = window.setTimeout(() => {
      suggest(text, 8).then(setSuggestions)
    }, SUGGEST_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [text, suggestible])

  function runSearch(q: string): void {
    const trimmed = q.trim()
    if (!trimmed) {
      return
    }
    setSuggestions([])
    setHighlighted(-1)
    runQuery(trimmed)
  }

  function handleKeyDown(e: ReactKeyboardEvent<HTMLInputElement>): void {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setHighlighted((h) => Math.min(h + 1, suggestions.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHighlighted((h) => Math.max(h - 1, -1))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      runSearch(highlighted >= 0 ? suggestions[highlighted] : text)
    } else if (e.key === 'Escape') {
      setSuggestions([])
      setHighlighted(-1)
    }
  }

  return (
    <div className="mt-9 w-full animate-blur-in" style={{ animationDelay: '90ms' }}>
      <div className="relative">
        <div className="glow-ring rounded-full" data-lit={focused}>
          <div
            className={
              'group relative flex items-center gap-3 overflow-hidden rounded-full border pl-5 pr-2 ' +
              'backdrop-blur-xl transition-all duration-300 ' +
              (focused
                ? 'scale-[1.015] border-brand/45 bg-black/72 shadow-pop'
                : 'border-white/15 bg-black/50 hover:border-white/30')
            }
          >
            <span className="sheen" />

            <VnSearchMark
              className={
                'h-6 w-6 shrink-0 text-white transition-transform duration-300 ' +
                (focused ? 'scale-110' : '')
              }
            />

            <div className="relative min-w-0 flex-1">
              <input
                value={text}
                onChange={(e) => {
                  setText(e.target.value)
                  setHighlighted(-1)
                }}
                onKeyDown={handleKeyDown}
                onFocus={() => setFocused(true)}
                onBlur={() => setFocused(false)}
                className="w-full bg-transparent py-4 text-[16px] text-white focus:outline-none"
                spellCheck={false}
                aria-label="Ô tìm kiếm"
                autoFocus
              />

              {/*
                Chỗ giữ chỗ tự vẽ, không dùng thuộc tính `placeholder`: thuộc
                tính đó chỉ nhận chuỗi thuần, mà con trỏ nhấp nháy thì phải là
                một phần tử riêng. Có `aria-hidden` vì tên gọi của ô đã nằm ở
                `aria-label` — trình đọc màn hình không cần nghe câu mẫu đang
                được gõ dở.
              */}
              {text.length === 0 && (
                <span
                  className="pointer-events-none absolute inset-0 flex items-center truncate
                             text-[16px] text-white/55"
                  aria-hidden="true"
                >
                  {typed || 'Tìm kiếm với VnSearch'}
                  <span className="ml-[3px] inline-block h-[1.05em] w-[2px] animate-caret bg-brand/90" />
                </span>
              )}
            </div>

            <button
              onClick={() => setMicNote((v) => !v)}
              className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-full
                         text-white/75 transition hover:bg-white/15 hover:text-white
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
              aria-label="Tìm kiếm bằng giọng nói"
              aria-pressed={micNote}
              title="Tìm kiếm bằng giọng nói"
            >
              {/* Vòng sóng chỉ chạy khi nút đang bật — chuyển động = trạng thái. */}
              {micNote && (
                <span className="absolute inset-0 animate-pulse-ring rounded-full bg-brand/40" />
              )}
              <MicIcon className={'h-[19px] w-[19px] ' + (micNote ? 'text-brand-strong' : '')} />
            </button>

            <button
              onClick={() => runSearch(text)}
              disabled={!suggestible}
              className="press flex h-9 w-9 shrink-0 items-center justify-center rounded-full
                         transition-all duration-200 enabled:bg-brand enabled:text-white
                         enabled:hover:brightness-110 enabled:hover:shadow-[0_0_20px_rgba(45,212,132,0.55)]
                         disabled:bg-white/10 disabled:text-white/35
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
              aria-label="Tìm kiếm"
              title="Tìm kiếm"
            >
              <SearchIcon className="h-[18px] w-[18px]" />
            </button>
          </div>
        </div>

        <AutocompleteDropdown
          suggestions={suggestible ? suggestions : []}
          highlightedIndex={highlighted}
          query={text}
          onSelect={(s) => {
            setText(s)
            runSearch(s)
          }}
          onHighlight={setHighlighted}
        />
      </div>

      {micNote && (
        <p className="mt-2 animate-fade-up text-center text-[12px] text-white/70">
          Tìm bằng giọng nói chưa khả dụng — ứng dụng chưa xin quyền dùng micrô.
        </p>
      )}

      <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
        {SAMPLE_QUERIES.map((sample, index) => (
          <button
            key={sample}
            onClick={() => {
              setText(sample)
              runSearch(sample)
            }}
            className="press animate-pop-in rounded-full border border-white/18 bg-white/8 px-3.5 py-1.5
                       text-[12.5px] text-white/85 backdrop-blur transition-all duration-300
                       hover:-translate-y-0.5 hover:border-brand/50 hover:bg-brand/20 hover:text-white
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
            style={{ animationDelay: `${180 + index * 60}ms` }}
          >
            {sample}
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * Dòng tin trang chủ — thẻ tin CÓ ẢNH, cuộn xuống tải thêm.
 *
 * Trước đây khối này vẽ một ô gradient kèm chữ cái đầu của tên miền thay cho
 * ảnh, đơn giản vì hệ thống chưa hề thu thập ảnh. Nay `ImageStore` đã có, nên
 * thẻ tin mang ảnh thật — và CHỈ những bài có ảnh mới được đưa vào, vì một
 * dòng tin đầy ô xám trông như đang hỏng.
 *
 * Ngẫu nhiên nhưng phân trang được: máy chủ xáo trộn bằng một hạt giống do
 * giao diện sinh một lần cho mỗi lần mở cửa sổ. Cùng hạt giống thì mọi lô nhìn
 * cùng một hoán vị, nên lô 2 nối khít vào sau lô 1 — xem `FeedController`.
 */
function HotNews(): JSX.Element {
  const navigate = useTabStore((state) => state.navigate)

  const [cards, setCards] = useState<FeedCard[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [indexed, setIndexed] = useState<number | null>(null)
  const [page, setPage] = useState(0)
  const [broken, setBroken] = useState<Set<string>>(new Set())

  const sentinelRef = useRef<HTMLDivElement>(null)
  // Chốt ĐỒNG BỘ: IntersectionObserver có thể bắn hai lần trước khi React kịp
  // render lại, và `loading` là state nên lúc đó nó vẫn mang giá trị cũ — hai
  // request cho cùng một lô. Ref cập nhật ngay nên nó chặn được ca đó.
  const inFlight = useRef(false)

  const loadNext = useCallback(async () => {
    if (inFlight.current || !hasMore) {
      return
    }
    inFlight.current = true
    setLoading(true)
    const next = page + 1
    try {
      const response = await fetchFeed(next, 12)
      setCards((prev) => {
        const seen = new Set(prev.map((card) => card.url))
        return [...prev, ...response.results.filter((card) => !seen.has(card.url))]
      })
      setHasMore(response.hasMore)
      setIndexed(response.indexedDocuments)
      setPage(next)
      setFailed(false)
    } catch {
      setFailed(true)
      setHasMore(false)
    } finally {
      inFlight.current = false
      setLoading(false)
    }
  }, [page, hasMore])

  // MỘT cơ chế cho mọi lô, kể cả lô đầu: ô canh nằm dưới một lưới rỗng thì vốn
  // đã nằm trong tầm nhìn, nên observer tự bắn ngay khi gắn. Không có nhánh
  // "lần đầu" riêng để hỏng riêng, và cũng không có setState đồng bộ trong
  // effect (thứ mà ESLint chặn vì gây thêm một lượt render).
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || !hasMore) {
      return undefined
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          void loadNext()
        }
      },
      { rootMargin: '400px' }
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [loadNext, hasMore])

  const empty = cards.length === 0

  return (
    <section className="mx-auto max-w-6xl px-8 pb-14 pt-10">
      <div className="mb-5 flex items-center gap-3">
        <span className="h-5 w-[3px] rounded-full bg-linear-to-b from-brand to-accent" />
        <h2 className="font-display text-[19px] font-semibold text-ink">Tin nóng</h2>
        <span className="text-[12px] text-faint">
          {cards.length > 0 ? `${cards.length} bài · cuộn để xem thêm` : 'Lấy từ chỉ mục VnSearch'}
        </span>
      </div>

      {empty && failed && (
        <div className="animate-fade-up rounded-2xl border border-line bg-raised/40 px-6 py-10 text-center">
          <p className="text-[13px] text-muted">Không lấy được tin từ backend.</p>
          <p className="mt-1 text-[12px] text-faint">
            Kiểm tra <code className="text-muted">http://localhost:8080</code> — chạy{' '}
            <code className="text-muted">docker compose up -d --build</code> ở thư mục gốc.
          </p>
        </div>
      )}

      {empty && loading && (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="overflow-hidden rounded-2xl border border-line">
              <div className="skeleton h-28 w-full rounded-none" />
              <div className="space-y-2 p-3">
                <div className="skeleton h-3 w-full" />
                <div className="skeleton h-3 w-3/4" />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* HAI ca rỗng khác hẳn nhau — nói nhầm thì người dùng đi sửa sai chỗ. */}
      {empty && !loading && !failed && !hasMore && (
        <div className="animate-fade-up rounded-2xl border border-line bg-raised/40 px-6 py-10 text-center">
          {indexed && indexed > 0 ? (
            <>
              <p className="text-[13px] text-muted">
                Chỉ mục có {indexed.toLocaleString('vi-VN')} bài, nhưng chưa bài nào được thu thập
                ảnh.
              </p>
              <p className="mt-1 text-[12px] text-faint">
                Kho ảnh nằm trong bộ nhớ tiến trình — khởi động lại backend là mất. Hãy chạy một
                phiên crawl mới.
              </p>
            </>
          ) : (
            <p className="text-[13px] text-muted">Chỉ mục chưa có bài nào. Hãy chạy crawl trước.</p>
          )}
        </div>
      )}

      {cards.length > 0 && (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          {cards.map((card, index) => {
            const isBroken = broken.has(card.imageUrl)
            return (
              <button
                key={card.url}
                onClick={() => navigate(card.url)}
                className="group relative flex animate-rise-in flex-col overflow-hidden rounded-2xl
                           border border-line bg-surface text-left transition-all duration-300
                           hover:-translate-y-1.5 hover:border-brand/45
                           hover:shadow-[0_14px_34px_rgba(0,0,0,0.16),0_0_0_1px_rgba(45,212,132,0.18)]
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
                // Lô nào cũng 12 thẻ, nên `% 12` cho ra đúng thứ tự trong lô
                // vừa về. Dùng chỉ số tuyệt đối thì thẻ thứ 40 phải đợi gần
                // hai giây mới hiện — hiệu ứng thành ra chờ đợi.
                style={{ animationDelay: `${(index % 12) * 45}ms` }}
                title={card.url}
              >
                <span className="relative block h-28 w-full overflow-hidden">
                  {isBroken ? (
                    <span
                      className="flex h-full w-full items-center justify-center text-3xl font-bold text-white/90"
                      style={{ background: siteGradient(card.url) }}
                    >
                      {siteInitial(card.url)}
                    </span>
                  ) : (
                    <img
                      src={card.imageUrl}
                      alt={card.altText}
                      loading="lazy"
                      decoding="async"
                      referrerPolicy="no-referrer"
                      onError={() =>
                        setBroken((prev) => {
                          const next = new Set(prev)
                          next.add(card.imageUrl)
                          return next
                        })
                      }
                      className="h-full w-full bg-raised object-cover transition-transform duration-500
                                 ease-out group-hover:scale-110"
                    />
                  )}
                  {/* Màn xanh mờ dần khi rê chuột — buộc ảnh vào tông của trang. */}
                  <span
                    className="pointer-events-none absolute inset-0 bg-linear-to-t from-brand/35
                               to-transparent opacity-0 transition-opacity duration-300
                               group-hover:opacity-100"
                  />
                </span>

                <span className="flex min-w-0 flex-1 flex-col gap-2 p-3">
                  <span className="flex items-center gap-1.5">
                    <GlobeIcon className="h-3 w-3 shrink-0 text-faint transition-colors group-hover:text-brand" />
                    <span className="truncate text-[11px] text-faint">
                      {card.host || hostOf(card.url)}
                    </span>
                  </span>
                  <span className="line-clamp-2 text-[13px] font-medium leading-snug text-ink transition-colors group-hover:text-brand">
                    {card.title}
                  </span>
                  {card.snippet && (
                    <span className="line-clamp-2 text-[11.5px] leading-relaxed text-muted">
                      {card.snippet}
                    </span>
                  )}
                </span>
              </button>
            )
          })}
        </div>
      )}

      {/* Ô canh vô hình: lọt vào tầm nhìn = đã cuộn gần đáy = nạp lô sau. */}
      <div ref={sentinelRef} aria-hidden className="h-1" />

      {loading && cards.length > 0 && (
        <div className="flex items-center justify-center gap-2 py-6 text-[13px] text-muted">
          <SpinnerIcon className="h-4 w-4 text-brand" />
          Đang tải thêm tin…
        </div>
      )}

      {!hasMore && !loading && cards.length > 0 && (
        <p className="py-6 text-center text-[12px] text-faint">
          Đã hiện hết {cards.length.toLocaleString('vi-VN')} bài trong chỉ mục.
        </p>
      )}

      {failed && cards.length > 0 && (
        <div className="flex animate-fade-up items-center justify-center gap-2 py-4 text-[12px] text-danger">
          <AlertIcon className="h-4 w-4" />
          Không tải được thêm tin.
        </div>
      )}
    </section>
  )
}

export default NewTabPage
