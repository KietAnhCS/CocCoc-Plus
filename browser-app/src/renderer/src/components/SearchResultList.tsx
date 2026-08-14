import { useCallback, useEffect, useRef, useState, type JSX } from 'react'
import { search, type SearchResponseDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, prettyUrl, siteGradient, siteInitial } from '../lib/site'
import { track } from '../lib/telemetry'
import ImageResultGrid, { type ImageMeta } from './ImageResultGrid'
import SearchMatchCard from './SearchMatchCard'
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
 *
 * Gạch chân là một phần tử RIÊNG được co giãn theo trục ngang, không phải
 * `border-bottom` bật/tắt. Viền thì chỉ có hai trạng thái, còn phần tử riêng
 * thì chuyển được — nó mọc ra từ giữa tab vừa chọn, nên mắt bám theo được
 * việc "chế độ xem vừa đổi" thay vì phải phát hiện ra màu đã khác.
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
        'relative flex items-center gap-1.5 px-3 pb-2.5 pt-2 text-[13px] transition-colors ' +
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50 ' +
        (active ? 'font-medium text-brand' : 'text-muted hover:text-ink')
      }
    >
      <span
        className={
          'transition-transform duration-300 ' + (active ? 'scale-110' : 'group-hover:scale-105')
        }
      >
        {icon}
      </span>
      {label}
      <span
        aria-hidden="true"
        className={
          'absolute inset-x-1.5 bottom-0 h-[2px] rounded-full bg-linear-to-r from-brand to-accent ' +
          'transition-transform duration-300 ease-out ' +
          (active ? 'scale-x-100' : 'scale-x-0')
        }
      />
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
          // Chỉ ghi nhận TRANG ĐẦU: bấm sang trang 2 vẫn là cùng một lượt tìm
          // kiếm, đếm thêm sẽ biến một người kiên nhẫn thành nhiều lượt tìm và
          // làm hỏng cả tỉ lệ bấm lẫn số "lượt tìm mỗi người".
          if (page === 1) {
            track({
              type: 'search',
              query,
              resultCount: response.totalResults,
              tookMs: response.timeTakenMs
            })
          }
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
  // Số liệu ảnh chỉ hợp lệ khi nó thuộc về ĐÚNG truy vấn đang xem. Khác truy
  // vấn = lô đầu chưa về = đang tải. Suy ra như vậy để component con không
  // phải gọi setState của lớp cha ngay trong effect.
  const imageSettled = imageMeta?.query === query ? imageMeta : null
  const loading = mode === 'web' ? settled === null : imageSettled === null
  const response = outcome?.response ?? null
  const error = settled?.error ?? null
  const totalPages = response ? Math.max(1, Math.ceil(response.totalResults / PAGE_SIZE)) : 1
  const showSkeleton = settled === null && !response

  // Lưới ảnh cần rộng hơn danh sách liên kết: bốn cột ở màn hình lớn thì
  // max-w-3xl (768px) chỉ cho ra những ô bé xíu.
  const containerWidth = mode === 'images' ? 'max-w-6xl' : 'max-w-3xl'

  const metaLine = (): string => {
    if (mode === 'images') {
      if (!imageSettled) return 'Đang tìm ảnh…'
      return `${imageSettled.total.toLocaleString('vi-VN')} ảnh · ${(
        imageSettled.timeTakenMs / 1000
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
          <span
            className={
              'flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-soft ' +
              'text-brand transition-shadow duration-300 ' +
              (loading ? 'shadow-[0_0_0_4px_rgba(45,212,132,0.12)]' : '')
            }
          >
            {loading ? <SpinnerIcon className="h-4 w-4" /> : <SearchIcon className="h-4 w-4" />}
          </span>
          <div className="min-w-0 flex-1">
            <p className="selectable truncate text-[15px] font-medium text-ink">{query}</p>
            <p className="text-[12px] text-faint">{metaLine()}</p>
          </div>
          {mode === 'web' && (
            <button
              onClick={() => setDebugMode((d) => !d)}
              aria-pressed={debugMode}
              className={
                'press flex shrink-0 items-center gap-1.5 rounded-full border px-3 py-1.5 text-[12px] ' +
                'transition-all duration-200 ' +
                (debugMode
                  ? 'border-brand/45 bg-brand-soft text-brand shadow-[0_0_14px_rgba(45,212,132,0.25)]'
                  : 'border-line text-muted hover:border-brand/35 hover:bg-raised hover:text-ink')
              }
              title="Hiện điểm BM25 / PageRank của từng kết quả"
            >
              <SlidersIcon
                className={
                  'h-3.5 w-3.5 transition-transform duration-300 ' + (debugMode ? 'rotate-90' : '')
                }
              />
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

        {/*
          Thanh tiến trình VÔ ĐỊNH, nằm đúng trên đường viền dưới của thanh
          tiêu đề. Không hứa hẹn phần trăm nào vì máy chủ cũng không báo tiến
          độ — nó chỉ trả lời một câu: "còn đang chạy". Đặt ở đây thay vì trên
          đỉnh trang để nó dính liền vào khối đang thay đổi nội dung.
        */}
        <div className="absolute inset-x-0 -bottom-px h-[2px] overflow-hidden">
          {loading && (
            <div className="h-full w-1/3 animate-progress rounded-full bg-linear-to-r from-transparent via-brand to-accent" />
          )}
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

            {/* Thẻ tỉ số nằm TRÊN danh sách kết quả, giống thẻ thể thao của
                các máy tìm kiếm lớn: người gõ "arsenal" hay "tỉ số" muốn con
                số ngay, không muốn mở thêm một trang nữa. Tự ẩn khi truy vấn
                không nói về bóng đá — xem `SearchMatchCard`. */}
            <SearchMatchCard query={query} />

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
                  className="group relative -mx-3 rounded-xl px-3 py-2 animate-rise-in
                             transition-colors duration-200 hover:bg-raised/45"
                  style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}
                >
                  {/* Vạch xanh mọc dọc mép trái khi rê chuột — nó thay cho một
                      khung viền, vốn sẽ cắt vụn danh sách thành các hộp rời. */}
                  <span
                    aria-hidden="true"
                    className="absolute inset-y-2 left-0 w-[3px] origin-center scale-y-0 rounded-full
                               bg-linear-to-b from-brand to-accent transition-transform duration-300
                               group-hover:scale-y-100"
                  />

                  <div className="mb-1.5 flex items-center gap-2.5">
                    <span
                      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px]
                                 font-bold text-white shadow-sm ring-1 ring-inset ring-white/25
                                 transition-transform duration-300 group-hover:scale-110"
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
                      // Ghi nhận TRƯỚC khi điều hướng: `navigate` thay cả
                      // khung nội dung, và thứ tự ngược lại sẽ để sự kiện phải
                      // đua với việc trang bị thay (đó cũng là lý do lời gọi
                      // dùng cờ `keepalive`).
                      track({
                        type: 'click',
                        url: result.url,
                        // Hạng TUYỆT ĐỐI trong toàn bộ kết quả, không phải vị
                        // trí trong trang: bấm mục đầu của trang 3 là hạng 21,
                        // và đó mới là con số nói lên chất lượng xếp hạng.
                        position: (page - 1) * PAGE_SIZE + index + 1
                      })
                      navigate(result.url)
                      clearSearch()
                    }}
                    // Gạch chân TRƯỢT RA thay vì bật lên: nền chuyển sắc cao
                    // 2px, rộng 0% lúc nghỉ, và chỉ chiều rộng được chuyển
                    // động. `text-decoration` không chuyển động được, nên
                    // muốn có nhịp trượt thì phải vẽ gạch chân bằng nền.
                    style={{
                      backgroundImage: 'linear-gradient(var(--color-brand), var(--color-brand))',
                      backgroundRepeat: 'no-repeat',
                      backgroundPosition: '0 100%'
                    }}
                    className="block max-w-full truncate bg-[length:0%_2px] text-left text-[19px] leading-snug
                           text-link transition-[background-size] duration-300 ease-out
                           group-hover:bg-[length:100%_2px] focus-visible:outline-none
                           focus-visible:ring-2 focus-visible:ring-brand/50"
                    title={result.title}
                  >
                    {result.title}
                  </button>

                  <p
                    // Từ khớp được tô nền xanh rất nhạt chứ không chỉ in đậm:
                    // in đậm nói "từ này quan trọng", còn nền màu nói "đây
                    // chính là từ bạn vừa gõ" — hai câu khác nhau, và câu thứ
                    // hai mới là thứ đoạn trích cần trả lời.
                    className="selectable mt-1 text-[14px] leading-relaxed text-muted
                           [&_mark]:rounded [&_mark]:bg-brand/12 [&_mark]:px-0.5 [&_mark]:font-semibold
                           [&_mark]:text-ink"
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
              <div className="flex animate-blur-in flex-col items-center gap-2 py-20 text-center">
                <span className="mb-2 flex h-16 w-16 animate-float items-center justify-center rounded-full bg-brand-soft">
                  <SearchIcon className="h-8 w-8 text-brand" />
                </span>
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
    <span
      className="animate-scale-in rounded-md border border-brand/20 bg-brand-soft/60 px-2 py-0.5
                 font-mono text-[11px] text-muted"
    >
      {label} <span className="text-brand">{value}</span>
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
    'group press flex h-9 items-center gap-1 rounded-full px-3 text-[13px] text-muted transition-all ' +
    'hover:bg-raised hover:text-brand disabled:pointer-events-none disabled:opacity-40'

  return (
    <nav className="mt-12 flex items-center justify-center gap-1" aria-label="Phân trang">
      <button disabled={page <= 1} onClick={() => onChange(page - 1)} className={arrowClass}>
        <ChevronLeftIcon className="h-4 w-4 transition-transform duration-200 group-hover:-translate-x-0.5" />
        Trước
      </button>

      {pages.map((p) => (
        <button
          key={p}
          onClick={() => onChange(p)}
          aria-current={p === page ? 'page' : undefined}
          className={
            'press flex h-9 w-9 items-center justify-center rounded-full text-[13px] transition-all duration-200 ' +
            (p === page
              ? 'scale-105 bg-brand font-semibold text-white shadow-glow'
              : 'text-muted hover:bg-raised hover:text-brand')
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
        <ChevronRightIcon className="h-4 w-4 transition-transform duration-200 group-hover:translate-x-0.5" />
      </button>
    </nav>
  )
}

export default SearchResultList
