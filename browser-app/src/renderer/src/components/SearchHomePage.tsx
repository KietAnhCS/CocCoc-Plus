import { useEffect, useRef, useState } from 'react'
import AutocompleteDropdown from './AutocompleteDropdown'
import { suggest } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { BoltIcon, SearchIcon, VnSearchMark } from './icons'
import { siteGradient, siteInitial } from '../lib/site'

/** Sáu báo điện tử là seed của bộ crawl (xem MultiDomainCrawlRunner.java). */
const SITES = [
  { name: 'VnExpress', url: 'https://vnexpress.net/' },
  { name: 'Tuổi Trẻ', url: 'https://tuoitre.vn/' },
  { name: 'Dân Trí', url: 'https://dantri.com.vn/' },
  { name: 'Thanh Niên', url: 'https://thanhnien.vn/' },
  { name: 'VietnamNet', url: 'https://vietnamnet.vn/' },
  { name: 'Nhân Dân', url: 'https://nhandan.vn/' }
]

/** Vài truy vấn mẫu để người xem demo bấm phát là thấy ngay, kể cả cú pháp nâng cao. */
const SAMPLE_QUERIES = ['bóng đá Việt Nam', 'giá vàng hôm nay', 'trí tuệ nhân tạo', 'site:vnexpress.net kinh tế']

/** Con số thật lấy từ corpus hiện tại (README.md) — trang chủ cũng là chỗ khoe số liệu. */
const STATS = [
  { value: '5.011', label: 'trang đã lập chỉ mục' },
  { value: '136.768', label: 'từ khoá phân biệt' },
  { value: '~1,6 ms', label: 'thời gian mỗi truy vấn' }
]

/**
 * Trang chủ mặc định của trình duyệt. Gõ vào ô tìm kiếm -> debounce 200 ms
 * -> gọi /api/suggest -> AutocompleteDropdown. Điều hướng dropdown bằng
 * phím mũi tên, Enter để chọn/tìm; Enter khi không có gợi ý nào được chọn
 * thì chuyển sang SearchResultList qua searchViewStore.
 */
function SearchHomePage(): JSX.Element {
  const [text, setText] = useState('')
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const [focused, setFocused] = useState(false)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const navigate = useTabStore((s) => s.navigate)
  const debounceRef = useRef<number | undefined>(undefined)

  useEffect(() => {
    if (!text.trim()) {
      setSuggestions([])
      return undefined
    }
    window.clearTimeout(debounceRef.current)
    debounceRef.current = window.setTimeout(() => {
      suggest(text, 8)
        .then(setSuggestions)
        .catch(() => setSuggestions([]))
    }, 200)
    return () => window.clearTimeout(debounceRef.current)
  }, [text])

  function runSearch(q: string): void {
    const trimmed = q.trim()
    if (!trimmed) {
      return
    }
    setSuggestions([])
    setHighlighted(-1)
    setQuery(trimmed)
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>): void {
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
    <div className="relative flex h-full flex-col items-center overflow-y-auto overflow-x-hidden bg-surface px-6">
      <AuroraBackdrop />

      <div className="relative flex w-full max-w-2xl flex-1 flex-col items-center justify-center py-10">
        <div className="animate-fade-up">
          <div className="flex items-center justify-center gap-3">
            <VnSearchMark className="h-12 w-12 text-ink drop-shadow-sm" />
            <h1 className="font-display text-5xl font-semibold tracking-tight text-ink">
              Vn<span className="bg-gradient-to-r from-rose-500 via-orange-500 to-amber-400 bg-clip-text text-transparent">Search</span>
            </h1>
          </div>
          <p className="mt-3 text-center text-sm text-muted">
            Máy tìm kiếm tiếng Việt tự crawl · tự lập chỉ mục · tự xếp hạng
          </p>
        </div>

        <div className="relative mt-8 w-full animate-fade-up" style={{ animationDelay: '60ms' }}>
          <div
            className={
              'flex items-center gap-3 rounded-[28px] border bg-surface px-5 transition-all duration-200 ' +
              (focused
                ? 'border-brand/40 shadow-pop ring-4 ring-brand/10'
                : 'border-line shadow-omni hover:shadow-pop')
            }
          >
            <SearchIcon className={'h-5 w-5 shrink-0 ' + (focused ? 'text-brand' : 'text-faint')} />
            <input
              value={text}
              onChange={(e) => {
                setText(e.target.value)
                setHighlighted(-1)
              }}
              onKeyDown={handleKeyDown}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              className="min-w-0 flex-1 bg-transparent py-4 text-[17px] text-ink placeholder:text-faint focus:outline-none"
              placeholder="Tìm kiếm trên 5.011 trang báo tiếng Việt…"
              spellCheck={false}
              aria-label="Ô tìm kiếm"
              autoFocus
            />
            {text && (
              <button
                onClick={() => runSearch(text)}
                className="shrink-0 rounded-full bg-brand px-4 py-1.5 text-sm font-medium text-white
                           transition hover:brightness-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
              >
                Tìm
              </button>
            )}
          </div>

          <AutocompleteDropdown
            suggestions={suggestions}
            highlightedIndex={highlighted}
            query={text}
            onSelect={(s) => {
              setText(s)
              runSearch(s)
            }}
            onHighlight={setHighlighted}
          />
        </div>

        <div
          className="mt-5 flex flex-wrap items-center justify-center gap-2 animate-fade-up"
          style={{ animationDelay: '120ms' }}
        >
          {SAMPLE_QUERIES.map((sample) => (
            <button
              key={sample}
              onClick={() => {
                setText(sample)
                runSearch(sample)
              }}
              className="rounded-full border border-line bg-raised/60 px-3.5 py-1.5 text-[13px] text-muted
                         transition hover:border-brand/40 hover:bg-brand-soft hover:text-ink"
            >
              {sample}
            </button>
          ))}
        </div>

        <div
          className="mt-12 grid w-full grid-cols-3 gap-3 sm:grid-cols-6 animate-fade-up"
          style={{ animationDelay: '180ms' }}
        >
          {SITES.map((site) => (
            <button
              key={site.url}
              onClick={() => navigate(site.url)}
              className="group flex flex-col items-center gap-2 rounded-2xl border border-transparent p-3
                         transition hover:border-line hover:bg-raised/70"
              title={site.url}
            >
              <span
                className="flex h-11 w-11 items-center justify-center rounded-2xl text-lg font-bold text-white
                           shadow-card transition-transform duration-200 group-hover:-translate-y-0.5"
                style={{ background: siteGradient(site.url) }}
              >
                {siteInitial(site.url)}
              </span>
              <span className="truncate text-[12px] text-muted group-hover:text-ink">{site.name}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="relative mb-6 flex flex-wrap items-center justify-center gap-x-8 gap-y-2 text-center animate-fade-in">
        {STATS.map((stat) => (
          <div key={stat.label} className="flex items-baseline gap-1.5">
            <span className="font-display text-base font-semibold text-ink">{stat.value}</span>
            <span className="text-[12px] text-faint">{stat.label}</span>
          </div>
        ))}
        <span className="flex items-center gap-1.5 text-[12px] text-faint">
          <BoltIcon className="h-3.5 w-3.5 text-brand" />
          Không dùng Lucene / Elasticsearch
        </span>
      </div>
    </div>
  )
}

/**
 * Vệt màu mờ phía sau. Chỉ là hai khối gradient bị blur mạnh — rẻ hơn nhiều
 * so với ảnh nền, và tự đổi tông theo chế độ sáng/tối vì độ mờ rất thấp.
 */
function AuroraBackdrop(): JSX.Element {
  return (
    <div
      className="pointer-events-none absolute inset-0 overflow-hidden opacity-70 dark:opacity-30"
      aria-hidden="true"
    >
      <div className="absolute -left-24 -top-32 h-[26rem] w-[26rem] animate-aurora rounded-full bg-gradient-to-br from-rose-400/25 to-orange-300/20 blur-3xl" />
      <div
        className="absolute -right-28 top-10 h-[24rem] w-[24rem] animate-aurora rounded-full bg-gradient-to-br from-indigo-400/25 to-sky-300/20 blur-3xl"
        style={{ animationDelay: '-6s' }}
      />
      <div
        className="absolute bottom-[-10rem] left-1/3 h-[22rem] w-[22rem] animate-aurora rounded-full bg-gradient-to-br from-amber-300/20 to-violet-400/20 blur-3xl"
        style={{ animationDelay: '-12s' }}
      />
    </div>
  )
}

export default SearchHomePage
