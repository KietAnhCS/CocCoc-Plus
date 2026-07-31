import { useEffect, useRef, useState } from 'react'
import { search, type SearchResponseDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, prettyUrl, siteGradient, siteInitial } from '../lib/site'
import {
  AlertIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  SearchIcon,
  SlidersIcon,
  SpinnerIcon
} from './icons'

const PAGE_SIZE = 10

/**
 * Trang kết quả. Ngoài title/url/snippet (có <mark>) còn có dòng đầu
 * "Khoảng N kết quả (t giây)", phân trang, và chế độ debug hiện điểm
 * BM25/PageRank của từng kết quả — rất hữu ích khi demo cho giảng viên.
 */
function SearchResultList(): JSX.Element | null {
  const query = useSearchViewStore((s) => s.query)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const navigate = useTabStore((s) => s.navigate)

  const [page, setPage] = useState(1)
  const [response, setResponse] = useState<SearchResponseDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [debugMode, setDebugMode] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setPage(1)
  }, [query])

  useEffect(() => {
    if (!query) {
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    // Sang trang mới mà vẫn ở giữa danh sách cũ thì rất mất phương hướng.
    scrollRef.current?.scrollTo({ top: 0 })
    search(query, page, PAGE_SIZE)
      .then((res) => {
        if (!cancelled) {
          setResponse(res)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError(
            'Không thể kết nối tới máy chủ tìm kiếm (http://localhost:8080). Hãy chắc chắn backend đang chạy.'
          )
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [query, page])

  if (!query) {
    return null
  }

  const totalPages = response ? Math.max(1, Math.ceil(response.totalResults / PAGE_SIZE)) : 1
  const showSkeleton = loading && !response

  return (
    <div ref={scrollRef} className="h-full overflow-y-auto bg-surface">
      {/* Dải tiêu đề dính trên đỉnh: luôn biết đang tìm gì, tìm được bao nhiêu. */}
      <div className="sticky top-0 z-10 border-b border-line bg-surface/85 backdrop-blur-xl">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-6 py-3">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand">
            {loading ? <SpinnerIcon className="h-4 w-4" /> : <SearchIcon className="h-4 w-4" />}
          </span>
          <div className="min-w-0 flex-1">
            <p className="selectable truncate text-[15px] font-medium text-ink">{query}</p>
            <p className="text-[12px] text-faint">
              {response
                ? `Khoảng ${response.totalResults.toLocaleString('vi-VN')} kết quả · ${(
                    response.timeTakenMs / 1000
                  ).toFixed(3)} giây`
                : 'Đang tìm kiếm…'}
            </p>
          </div>
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
        </div>
      </div>

      <div className="mx-auto max-w-3xl px-6 pb-16 pt-5">
        {error && (
          <div className="flex items-start gap-3 rounded-2xl border border-danger/25 bg-danger/5 px-4 py-3.5">
            <AlertIcon className="mt-0.5 h-5 w-5 shrink-0 text-danger" />
            <p className="text-sm text-danger">{error}</p>
          </div>
        )}

        {/* Truy vấn đầy đủ không khớp tài liệu nào nên backend đã tự nới lỏng.
            Nói ra thay vì im lặng: kết quả bên dưới ứng với một truy vấn hẹp hơn
            truy vấn người dùng vừa gõ. */}
        {response && response.droppedTerms?.length > 0 && (
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

        <ul className={'flex flex-col gap-7 ' + (loading && response ? 'opacity-50 transition-opacity' : '')}>
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
                  setQuery(null)
                }}
                className="block max-w-full truncate text-left text-[19px] leading-snug text-link
                           hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
                title={result.title}
              >
                {result.title}
              </button>

              {/* eslint-disable-next-line react/no-danger -- snippet chứa thẻ <mark> do backend sinh (an toàn, không lấy từ dữ liệu người dùng nhập) */}
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
              Thử bớt từ khoá, kiểm tra chính tả, hoặc bỏ bộ lọc <code className="text-brand">site:</code>.
            </p>
          </div>
        )}

        {response && totalPages > 1 && (
          <Pagination page={page} totalPages={totalPages} onChange={setPage} />
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

/** Khung xám nhấp nháy trong lúc chờ lượt tìm đầu tiên — đỡ giật hơn là màn hình trắng. */
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

/** Phân trang kiểu Google: các số quanh trang hiện tại, tối đa 5 ô. */
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

      <button disabled={page >= totalPages} onClick={() => onChange(page + 1)} className={arrowClass}>
        Sau
        <ChevronRightIcon className="h-4 w-4" />
      </button>
    </nav>
  )
}

export default SearchResultList
