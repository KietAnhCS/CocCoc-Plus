import { useCallback, useEffect, useRef, useState, type JSX } from 'react'
import { search, type SearchResponseDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, prettyUrl, siteGradient, siteInitial } from '../lib/site'
import ImageResultGrid, { type ImageMeta } from './ImageResultGrid'
import {
  AlertIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  GridAppsIcon,
  SearchIcon,
  SlidersIcon,
  SpinnerIcon
} from './icons'

const PAGE_SIZE = 10

interface SearchOutcome {
  key: string
  response: SearchResponseDto | null
  error: string | null
}

/**
 * Một tab chế độ xem, kiểu gạch chân — quy ước mà Google, Bing và Cốc Cốc
 * đều dùng cho hàng "Tất cả / Hình ảnh / Video".
 *
 * Gạch chân chứ không phải viên thuốc bo tròn: hàng này nằm ngay dưới thanh
 * tìm kiếm, nơi đã có sẵn nút bo tròn "Điểm số". Dùng hai hình dạng khác nhau
 * cho hai loại điều khiển khác nhau giúp mắt phân biệt được "đổi chế độ xem"
 * với "bật một tuỳ chọn".
 */
function ModeTab({
  active,
  onClick,
  icon,
  label
}: {
  active: boolean
  onClick: () => void
  icon: JSX.Element
  label: string
}): JSX.Element {
  return (
    <button
      onClick={onClick}
      // aria-current: trình đọc màn hình cần biết tab nào đang mở. Chỉ đổi màu
      // thì người dùng bàn phím và trình đọc màn hình không nhận ra trạng thái.
      aria-current={active ? 'page' : undefined}
      className={
        'flex items-center gap-1.5 border-b-2 px-3 py-2 text-[13px] transition ' +
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50 ' +
        (active
          ? 'border-brand font-medium text-brand'
          : 'border-transparent text-muted hover:text-ink')
      }
    >
      {icon}
      {label}
    </button>
  )
}

function SearchResultList(): JSX.Element | null {
  const query = useSearchViewStore((state) => state.query)
  const mode = useSearchViewStore((state) => state.mode)
  const setMode = useSearchViewStore((state) => state.setMode)
  const clearSearch = useSearchViewStore((state) => state.clear)
  const navigate = useTabStore((state) => state.navigate)

  const [page, setPage] = useState(1)
  const [debugMode, setDebugMode] = useState(false)
  const [outcome, setOutcome] = useState<SearchOutcome | null>(null)
  const [imageMeta, setImageMeta] = useState<ImageMeta | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  const requestKey = `${page}|${query ?? ''}`

  // useCallback: nếu không, mỗi lần render ở đây sinh một hàm mới, React coi
  // đó là prop đã đổi, và tab Hình ảnh sẽ gọi lại API sau mỗi lần render.
  const handleImageMeta = useCallback((meta: ImageMeta) => setImageMeta(meta), [])

  useEffect(() => {
    // Chỉ gọi API tìm kiếm web khi đang ở tab Web. Ở tab Hình ảnh thì
    // ImageResultGrid tự lo phần của nó — gọi cả hai là tốn một vòng mạng cho
    // dữ liệu không ai nhìn.
    if (!query || mode !== 'web') {
      return undefined
    }
    let cancelled = false
    scrollRef.current?.scrollTo({ top: 0 })

    search(query, page, PAGE_SIZE)
      .then((response) => {
        if (!cancelled) {
          setOutcome({ key: requestKey, response, error: null })
        }
      })
      .catch(() => {
        if (!cancelled) {
          setOutcome({
            key: requestKey,
            response: null,
            error:
              'Không thể kết nối tới máy chủ tìm kiếm (http://localhost:8080). Hãy chắc chắn backend đang chạy.'
          })
        }
      })

    return () => {
      cancelled = true
    }
  }, [query, page, requestKey, mode])

  if (!query) {
    return null
  }

  const settled = outcome?.key === requestKey ? outcome : null
  const loading = mode === 'web' ? settled === null : (imageMeta?.loading ?? true)
  const response = outcome?.response ?? null
  const error = settled?.error ?? null
  const totalPages = response ? Math.max(1, Math.ceil(response.totalResults / PAGE_SIZE)) : 1
  const showSkeleton = settled === null && !response

  // Lưới ảnh cần rộng hơn danh sách liên kết: bốn cột ở màn hình lớn thì
  // max-w-3xl (768px) chỉ cho ra những ô bé xíu.
  const containerWidth = mode === 'images' ? 'max-w-6xl' : 'max-w-3xl'

  const metaLine = (): string => {
    if (mode === 'images') {
      if (!imageMeta || imageMeta.loading) return 'Đang tìm ảnh…'
      return `${imageMeta.total.toLocaleString('vi-VN')} ảnh · ${(
        imageMeta.timeTakenMs / 1000
      ).toFixed(3)} giây`
    }
    if (!response) return 'Đang tìm kiếm…'
    return `Khoảng ${response.totalResults.toLocaleString('vi-VN')} kết quả · ${(
      response.timeTakenMs / 1000
    ).toFixed(3)} giây`
  }

  return (
    <div ref={scrollRef} className="h-full overflow-y-auto bg-surface">
      <div className="sticky top-0 z-10 border-b border-line bg-surface/85 backdrop-blur-xl">
        <div className={`mx-auto flex ${containerWidth} items-center gap-3 px-6 pb-1 pt-3`}>
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand">
            {loading ? <SpinnerIcon className="h-4 w-4" /> : <SearchIcon className="h-4 w-4" />}
          </span>
          <div className="min-w-0 flex-1">
            <p className="selectable truncate text-[15px] font-medium text-ink">{query}</p>
            <p className="text-[12px] text-faint">{metaLine()}</p>
          </div>
          {mode === 'web' && (
            <button
              onClick={() => setDebugMode((d) => !d)}
              className={
                'flex shrink-0 items-center gap-1.5 rounded-full border px-3 py-1.5 text-[12px] transition ' +
                (debugMode
                  ? 'border-brand/40 bg-brand-soft text-brand'
                  : 'border-line text-muted hover:bg-raised hover:text-ink')
              }
              title="Hiện điểm BM25 / PageRank của từng kết quả"
            >
              <SlidersIcon className="h-3.5 w-3.5" />
              Điểm số
            </button>
          )}
        </div>

        {/* Hai tab, ngay dưới thanh tìm kiếm — đúng chỗ mọi trình duyệt đặt. */}
        <div className={`mx-auto flex ${containerWidth} items-center gap-1 px-6`}>
          <ModeTab
            active={mode === 'web'}
            onClick={() => setMode('web')}
            icon={<SearchIcon className="h-3.5 w-3.5" />}
            label="Tất cả"
          />
          <ModeTab
            active={mode === 'images'}
            onClick={() => setMode('images')}
            icon={<GridAppsIcon className="h-3.5 w-3.5" />}
            label="Hình ảnh"
          />
        </div>
      </div>

      <div className={`mx-auto ${containerWidth} px-6 pb-16 pt-5`}>
        {mode === 'images' && <ImageResultGrid key={query} onMeta={handleImageMeta} />}
        {mode === 'web' && (
          <>
            {error && (
              <div className="flex items-start gap-3 rounded-2xl border border-danger/25 bg-danger/5 px-4 py-3.5">
                <AlertIcon className="mt-0.5 h-5 w-5 shrink-0 text-danger" />
                <p className="text-sm text-danger">{error}</p>
              </div>
            )}

            {response && response.droppedTerms.length > 0 && (
              <div className="mb-5 flex items-start gap-3 rounded-2xl border border-warn/25 bg-warn/5 px-4 py-3">
                <AlertIcon className="mt-0.5 h-4 w-4 shrink-0 text-warn" />
                <p className="text-[13px] leading-relaxed text-warn">
                  Không có kết quả nào chứa đủ mọi từ khoá. Đã bỏ qua:{' '}
                  <span className="font-medium">
                    {response.droppedTerms.map((term) => term.replace(/_/g, ' ')).join(', ')}
                  </span>
                </p>
              </div>
            )}

            {showSkeleton && <ResultSkeletons />}

            <ul
              className={
                'flex flex-col gap-7 ' +
                (loading && response ? 'opacity-50 transition-opacity' : '')
              }
            >
              {response?.results.map((result, index) => (
                <li
                  key={result.url}
                  className="group animate-fade-up"
                  style={{ animationDelay: `${Math.min(index, 8) * 25}ms` }}
                >
                  <div className="mb-1.5 flex items-center gap-2.5">
                    <span
                      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px] font-bold text-white"
                      style={{ background: siteGradient(result.url) }}
                    >
                      {siteInitial(result.url)}
                    </span>
                    <div className="min-w-0 leading-tight">
                      <div className="truncate text-[13px] text-ink">{hostOf(result.url)}</div>
                      <div className="truncate text-[12px] text-faint">{prettyUrl(result.url)}</div>
                    </div>
                  </div>

                  <button
                    onClick={() => {
                      navigate(result.url)
                      clearSearch()
                    }}
                    className="block max-w-full truncate text-left text-[19px] leading-snug text-link
                           hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
                    title={result.title}
                  >
                    {result.title}
                  </button>

                  <p
                    className="selectable mt-1 text-[14px] leading-relaxed text-muted
                           [&_mark]:rounded [&_mark]:bg-transparent [&_mark]:font-semibold [&_mark]:text-ink"
                    dangerouslySetInnerHTML={{ __html: result.snippet }}
                  />

                  {debugMode && (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      <ScoreChip label="score" value={result.score.toFixed(4)} />
                      <ScoreChip label="pageRank" value={result.pageRankScore.toFixed(6)} />
                      <ScoreChip label="hạng" value={`#${(page - 1) * PAGE_SIZE + index + 1}`} />
                    </div>
                  )}
                </li>
              ))}
            </ul>

            {response && response.results.length === 0 && !loading && !error && (
              <div className="flex flex-col items-center gap-2 py-20 text-center">
                <SearchIcon className="h-9 w-9 text-faint" />
                <p className="text-[15px] text-ink">Không tìm thấy kết quả nào.</p>
                <p className="max-w-sm text-[13px] text-muted">
                  Thử bớt từ khoá, kiểm tra chính tả, hoặc bỏ bộ lọc{' '}
                  <code className="text-brand">site:</code>.
                </p>
              </div>
            )}

            {response && totalPages > 1 && (
              <Pagination page={page} totalPages={totalPages} onChange={setPage} />
            )}
          </>
        )}
      </div>
    </div>
  )
}

function ScoreChip({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <span className="rounded-md bg-raised px-2 py-0.5 font-mono text-[11px] text-muted">
      {label} <span className="text-ink">{value}</span>
    </span>
  )
}

function ResultSkeletons(): JSX.Element {
  return (
    <div className="flex flex-col gap-7" aria-hidden="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="flex flex-col gap-2">
          <div className="flex items-center gap-2.5">
            <div className="skeleton h-6 w-6 rounded-full" />
            <div className="skeleton h-3 w-40" />
          </div>
          <div className="skeleton h-5 w-3/4 rounded-md" />
          <div className="skeleton h-3.5 w-full" />
          <div className="skeleton h-3.5 w-5/6" />
        </div>
      ))}
    </div>
  )
}

function Pagination({
  page,
  totalPages,
  onChange
}: {
  page: number
  totalPages: number
  onChange: (page: number) => void
}): JSX.Element {
  const start = Math.max(1, Math.min(page - 2, totalPages - 4))
  const end = Math.min(totalPages, start + 4)
  const pages: number[] = []
  for (let p = start; p <= end; p++) {
    pages.push(p)
  }

  const arrowClass =
    'flex h-9 items-center gap-1 rounded-full px-3 text-[13px] text-muted transition ' +
    'hover:bg-raised hover:text-ink disabled:pointer-events-none disabled:opacity-40'

  return (
    <nav className="mt-12 flex items-center justify-center gap-1" aria-label="Phân trang">
      <button disabled={page <= 1} onClick={() => onChange(page - 1)} className={arrowClass}>
        <ChevronLeftIcon className="h-4 w-4" />
        Trước
      </button>

      {pages.map((p) => (
        <button
          key={p}
          onClick={() => onChange(p)}
          aria-current={p === page ? 'page' : undefined}
          className={
            'flex h-9 w-9 items-center justify-center rounded-full text-[13px] transition ' +
            (p === page
              ? 'bg-brand font-semibold text-white'
              : 'text-muted hover:bg-raised hover:text-ink')
          }
        >
          {p}
        </button>
      ))}

      <button
        disabled={page >= totalPages}
        onClick={() => onChange(page + 1)}
        className={arrowClass}
      >
        Sau
        <ChevronRightIcon className="h-4 w-4" />
      </button>
    </nav>
  )
}

export default SearchResultList
