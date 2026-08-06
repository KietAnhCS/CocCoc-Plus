import {
  useEffect,
  useState,
  type FormEvent,
  type JSX,
  type KeyboardEvent as ReactKeyboardEvent
} from 'react'
import AutocompleteDropdown from './AutocompleteDropdown'
import { suggest } from '../lib/searchApi'
import { fetchHotNews, type NewsCard } from '../lib/newsApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { useShortcutStore } from '../store/shortcutStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import { CloseIcon, MicIcon, MoonCloudIcon, PlusIcon, SunCloudIcon, VnSearchMark } from './icons'

const SUGGEST_DEBOUNCE_MS = 200

interface NewsOutcome {
  attempt: number
  cards: NewsCard[] | null
}

const SAMPLE_QUERIES = [
  'bóng đá Việt Nam',
  'giá vàng hôm nay',
  'trí tuệ nhân tạo',
  'site:vnexpress.net kinh tế'
]

function NewTabPage(): JSX.Element {
  return (
    <div className="h-full overflow-y-auto overflow-x-hidden bg-surface">
      <section className="relative isolate">
        <HeroBackdrop />
        <WeatherOverlay />

        <div className="relative mx-auto flex max-w-3xl flex-col items-center px-8 pb-16 pt-20">
          <div className="flex items-center gap-3 animate-fade-up">
            <VnSearchMark className="h-11 w-11 text-white drop-shadow" />
            <h1 className="font-display text-[42px] font-semibold leading-none tracking-tight text-white drop-shadow">
              Vn
              <span className="bg-gradient-to-r from-rose-300 via-orange-200 to-amber-200 bg-clip-text text-transparent">
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

function HeroBackdrop(): JSX.Element {
  return (
    <div className="absolute inset-0 -z-10 overflow-hidden" aria-hidden="true">
      <svg className="h-full w-full" viewBox="0 0 1440 520" preserveAspectRatio="xMidYMid slice">
        <defs>
          <linearGradient id="ntp-sky" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#131a3a" />
            <stop offset="45%" stopColor="#3b2a63" />
            <stop offset="75%" stopColor="#a4468a" />
            <stop offset="100%" stopColor="#f0864f" />
          </linearGradient>
          <radialGradient id="ntp-sun" cx="0.72" cy="0.86" r="0.42">
            <stop offset="0%" stopColor="#ffd9a0" stopOpacity="0.95" />
            <stop offset="55%" stopColor="#ff9d5c" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#ff9d5c" stopOpacity="0" />
          </radialGradient>
          <linearGradient id="ntp-hill-far" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#5b3a72" />
            <stop offset="100%" stopColor="#3d2857" />
          </linearGradient>
          <linearGradient id="ntp-hill-mid" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#33224b" />
            <stop offset="100%" stopColor="#241a39" />
          </linearGradient>
          <linearGradient id="ntp-fade" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#0f1220" stopOpacity="0" />
            <stop offset="100%" stopColor="#0f1220" stopOpacity="0.85" />
          </linearGradient>
        </defs>

        <rect width="1440" height="520" fill="url(#ntp-sky)" />
        <rect width="1440" height="520" fill="url(#ntp-sun)" />

        <g fill="#ffffff" opacity="0.10">
          <ellipse cx="250" cy="120" rx="150" ry="12" />
          <ellipse cx="380" cy="160" rx="200" ry="10" />
          <ellipse cx="1120" cy="105" rx="175" ry="11" />
          <ellipse cx="980" cy="150" rx="120" ry="8" />
        </g>

        <path
          d="M0 392 L150 330 L280 380 L430 300 L560 366 L700 322 L860 384 L1010 316 L1160 372 L1300 328 L1440 386 L1440 520 L0 520 Z"
          fill="url(#ntp-hill-far)"
        />
        <path
          d="M0 448 L180 400 L330 444 L500 388 L660 440 L820 402 L980 448 L1140 404 L1300 446 L1440 412 L1440 520 L0 520 Z"
          fill="url(#ntp-hill-mid)"
        />
        <path
          d="M0 486 L220 458 L420 490 L640 462 L880 492 L1120 464 L1330 494 L1440 476 L1440 520 L0 520 Z"
          fill="#181228"
        />

        <rect width="1440" height="520" fill="url(#ntp-fade)" />
      </svg>
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
      className="absolute left-6 top-5 z-10 flex items-center gap-3 rounded-2xl border border-white/15
                 bg-black/30 px-4 py-2.5 text-white backdrop-blur-md"
      title="Nhiệt độ là số tượng trưng — ứng dụng chưa nối với dịch vụ thời tiết."
    >
      {daytime ? (
        <SunCloudIcon className="h-7 w-7 text-amber-300" />
      ) : (
        <MoonCloudIcon className="h-7 w-7 text-sky-200" />
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
      <div className="mt-10 flex w-full flex-wrap items-start justify-center gap-1 animate-fade-up">
        <button
          onClick={() => setAdding(true)}
          className="group flex w-[92px] shrink-0 flex-col items-center gap-2 rounded-2xl p-2
                     transition hover:bg-white/10 focus-visible:outline-none
                     focus-visible:ring-2 focus-visible:ring-white/50"
          title="Thêm lối tắt mới"
        >
          <span
            className="flex h-12 w-12 items-center justify-center rounded-2xl border border-dashed
                       border-white/35 text-white/80 transition group-hover:border-white/60
                       group-hover:text-white"
          >
            <PlusIcon className="h-5 w-5" />
          </span>
          <span className="w-full truncate text-center text-[12px] text-white/80">Thêm mới</span>
        </button>

        {shortcuts.map((shortcut) => (
          <div key={shortcut.id} className="group relative">
            <button
              onClick={() => navigate(shortcut.url)}
              className="flex w-[92px] shrink-0 flex-col items-center gap-2 rounded-2xl p-2
                         transition hover:bg-white/10 focus-visible:outline-none
                         focus-visible:ring-2 focus-visible:ring-white/50"
              title={shortcut.url}
            >
              <span
                className="flex h-12 w-12 items-center justify-center rounded-2xl text-lg font-bold
                           text-white shadow-card transition-transform duration-200
                           group-hover:-translate-y-0.5"
                style={{ background: siteGradient(shortcut.url) }}
              >
                {siteInitial(shortcut.url)}
              </span>
              <span className="w-full truncate text-center text-[12px] text-white/80">
                {shortcut.name}
              </span>
            </button>

            <button
              onClick={() => remove(shortcut.id)}
              className="absolute right-1 top-1 hidden h-5 w-5 items-center justify-center rounded-full
                         bg-black/55 text-white/80 backdrop-blur transition hover:bg-black/75
                         hover:text-white focus-visible:outline-none group-hover:flex"
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

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-6">
      <div className="absolute inset-0" onMouseDown={onClose} aria-hidden="true" />
      <form
        onSubmit={submit}
        className="relative w-[380px] animate-scale-in rounded-2xl border border-line bg-surface p-5 shadow-pop"
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
          className="mt-1 h-9 w-full rounded-lg border border-line bg-omni px-3 text-[13px] text-ink
                     placeholder:text-faint focus:border-brand/50 focus:outline-none
                     focus:ring-2 focus:ring-brand/15"
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
          className="mt-1 h-9 w-full rounded-lg border border-line bg-omni px-3 text-[13px] text-ink
                     placeholder:text-faint focus:border-brand/50 focus:outline-none
                     focus:ring-2 focus:ring-brand/15"
          placeholder="vnexpress.net"
          spellCheck={false}
        />

        {error && <p className="mt-2 text-[12px] text-danger">{error}</p>}

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
            className="rounded-lg px-3.5 py-2 text-[13px] font-medium transition
                       enabled:bg-brand enabled:text-white enabled:hover:brightness-110
                       disabled:cursor-not-allowed disabled:bg-raised disabled:text-faint"
          >
            Thêm
          </button>
        </div>
      </form>
    </div>
  )
}

function HeroSearchBox(): JSX.Element {
  const [text, setText] = useState('')
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const [focused, setFocused] = useState(false)
  const [micNote, setMicNote] = useState(false)
  const runQuery = useSearchViewStore((state) => state.runSearch)

  const suggestible = text.trim().length > 0

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
    <div className="mt-9 w-full animate-fade-up" style={{ animationDelay: '60ms' }}>
      <div className="relative">
        <div
          className={
            'flex items-center gap-3 rounded-full border px-5 backdrop-blur-md transition-all duration-200 ' +
            (focused
              ? 'border-white/45 bg-black/70 shadow-pop'
              : 'border-white/15 bg-black/50 hover:border-white/30')
          }
        >
          <VnSearchMark className="h-6 w-6 shrink-0 text-white" />

          <input
            value={text}
            onChange={(e) => {
              setText(e.target.value)
              setHighlighted(-1)
            }}
            onKeyDown={handleKeyDown}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            className="min-w-0 flex-1 bg-transparent py-4 text-[16px] text-white
                       placeholder:text-white/55 focus:outline-none"
            placeholder="Tìm kiếm với VnSearch"
            spellCheck={false}
            aria-label="Ô tìm kiếm"
            autoFocus
          />

          <button
            onClick={() => setMicNote((v) => !v)}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-white/75
                       transition hover:bg-white/15 hover:text-white focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-white/50"
            aria-label="Tìm kiếm bằng giọng nói"
            title="Tìm kiếm bằng giọng nói"
          >
            <MicIcon className="h-[19px] w-[19px]" />
          </button>
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
        <p className="mt-2 text-center text-[12px] text-white/70">
          Tìm bằng giọng nói chưa khả dụng — ứng dụng chưa xin quyền dùng micrô.
        </p>
      )}

      <div className="mt-5 flex flex-wrap items-center justify-center gap-2">
        {SAMPLE_QUERIES.map((sample) => (
          <button
            key={sample}
            onClick={() => {
              setText(sample)
              runSearch(sample)
            }}
            className="rounded-full border border-white/20 bg-white/10 px-3.5 py-1.5 text-[12.5px]
                       text-white/85 backdrop-blur transition hover:border-white/40 hover:bg-white/20
                       hover:text-white focus-visible:outline-none focus-visible:ring-2
                       focus-visible:ring-white/50"
          >
            {sample}
          </button>
        ))}
      </div>
    </div>
  )
}

function HotNews(): JSX.Element {
  const [attempt, setAttempt] = useState(0)
  const [outcome, setOutcome] = useState<NewsOutcome | null>(null)
  const navigate = useTabStore((state) => state.navigate)

  useEffect(() => {
    let cancelled = false

    fetchHotNews(2)
      .then((cards) => {
        if (!cancelled) {
          setOutcome({ attempt, cards })
        }
      })
      .catch(() => {
        if (!cancelled) {
          setOutcome({ attempt, cards: null })
        }
      })

    return () => {
      cancelled = true
    }
  }, [attempt])

  const settled = outcome?.attempt === attempt ? outcome : null
  const cards = settled ? settled.cards : null
  const failed = settled !== null && settled.cards === null

  return (
    <section className="mx-auto max-w-6xl px-8 pb-14 pt-10">
      <div className="mb-5 flex items-baseline gap-3">
        <h2 className="font-display text-[19px] font-semibold text-ink">Tin nóng</h2>
        <span className="text-[12px] text-faint">Lấy từ chỉ mục VnSearch</span>
      </div>

      {failed ? (
        <div className="rounded-2xl border border-line bg-raised/40 px-6 py-10 text-center">
          <p className="text-[13px] text-muted">Không lấy được tin từ backend.</p>
          <p className="mt-1 text-[12px] text-faint">
            Kiểm tra <code className="text-muted">http://localhost:8080</code> — chạy{' '}
            <code className="text-muted">docker compose up -d --build</code> ở thư mục gốc.
          </p>
          <button
            onClick={() => setAttempt((value) => value + 1)}
            className="mt-4 rounded-full border border-line px-4 py-1.5 text-[12.5px] text-muted
                       transition hover:border-brand/40 hover:text-ink"
          >
            Thử lại
          </button>
        </div>
      ) : cards === null ? (
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
      ) : cards.length === 0 ? (
        <p className="rounded-2xl border border-line bg-raised/40 px-6 py-10 text-center text-[13px] text-muted">
          Chỉ mục chưa có bài nào khớp các chuyên mục.
        </p>
      ) : (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          {cards.map((card) => (
            <button
              key={card.url}
              onClick={() => navigate(card.url)}
              className="group flex flex-col overflow-hidden rounded-2xl border border-line bg-surface
                         text-left transition hover:-translate-y-0.5 hover:border-brand/35
                         hover:shadow-card focus-visible:outline-none focus-visible:ring-2
                         focus-visible:ring-brand/50"
              title={card.url}
            >
              <span
                className="flex h-28 w-full items-center justify-center text-3xl font-bold text-white/90"
                style={{ background: siteGradient(card.url) }}
              >
                {siteInitial(card.url)}
              </span>

              <span className="flex min-w-0 flex-1 flex-col gap-2 p-3">
                <span className="flex items-center gap-2">
                  <span className="rounded-full bg-brand-soft px-2 py-0.5 text-[10.5px] font-medium text-brand">
                    {card.category}
                  </span>
                  <span className="truncate text-[11px] text-faint">{hostOf(card.url)}</span>
                </span>
                <span className="line-clamp-2 text-[13px] font-medium leading-snug text-ink group-hover:text-brand">
                  {card.title}
                </span>
              </span>
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

export default NewTabPage
