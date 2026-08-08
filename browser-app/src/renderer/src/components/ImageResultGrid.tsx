import { useCallback, useEffect, useRef, useState, type JSX } from 'react'
import { searchImages, type ImageResultDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import { AlertIcon, GlobeIcon, SearchIcon, SpinnerIcon } from './icons'

/**
 * Số ảnh mỗi lô.
 *
 * Nhỏ có chủ ý: lô đầu về nhanh nên lưới hiện gần như tức thì, phần còn lại
 * tải dần khi người dùng cuộn tới. Đây là cách Google Images và Cốc Cốc làm —
 * tải hết vài trăm ảnh ngay từ đầu nghĩa là mở hàng trăm kết nối cho những ảnh
 * người dùng có thể không bao giờ cuộn tới.
 *
 * Đổi một chỗ duy nhất ở đây là đổi cả hành vi.
 */
const BATCH_SIZE = 8

/**
 * Nạp trước khi ô canh còn cách đáy 400px.
 *
 * Bằng 0 thì người dùng phải cuộn CHẠM đáy mới thấy vòng quay, rồi đứng chờ.
 * Nạp sớm 400px khiến ảnh mới thường đã sẵn sàng trước khi họ cuộn tới đó, nên
 * cảm giác là một dải liên tục chứ không phải từng nhịp dừng.
 */
const PREFETCH_MARGIN = '400px'

interface Props {
  onMeta?: (meta: ImageMeta) => void
}

export interface ImageMeta {
  /**
   * Truy vấn mà số liệu này thuộc về.
   *
   * Nhờ trường này mà component cha SUY RA được trạng thái "đang tải" thay vì
   * phải được BÁO: `meta.query !== query` nghĩa là số liệu còn của truy vấn
   * cũ, tức lô đầu chưa về.
   *
   * Vì sao phải làm vậy: báo bằng một lời gọi `onMeta({loading:true})` ngay
   * trong effect chính là gọi setState của cha một cách ĐỒNG BỘ, khiến React
   * render thêm một lượt ngay lập tức (cascading render) — ESLint chặn đúng ở
   * chỗ đó. Suy ra từ dữ liệu sẵn có thì không cần lượt render nào.
   */
  query: string
  total: number
  timeTakenMs: number
}

/**
 * Tab "Hình ảnh" — lưới ảnh, cuộn tới đâu tải tới đó.
 *
 * <h3>Ảnh bám chặt thứ hạng của trang</h3>
 *
 * Không có mô hình xếp hạng riêng cho ảnh. Máy chủ xếp hạng TRANG bằng đúng
 * TF-IDF/BM25/PageRank của tab "Tất cả", rồi lấy ảnh theo thứ tự ấy. Nên nếu
 * truy vấn khớp 3 trang thì ảnh chỉ đến từ 3 trang đó, và ảnh của trang liên
 * quan nhất hiện trước.
 *
 * <h3>Cuộn vô hạn, không phải nút "Xem thêm"</h3>
 *
 * `IntersectionObserver` theo dõi một ô canh vô hình ở cuối lưới. Ô canh lọt
 * vào tầm nhìn nghĩa là người dùng đã cuộn gần tới đáy — lúc đó mới gọi lô
 * tiếp theo.
 *
 * Vì sao dùng nó thay vì nghe sự kiện `scroll`: sự kiện scroll bắn hàng chục
 * lần mỗi giây và mỗi lần đều phải đo lại vị trí phần tử, tức là ép trình
 * duyệt tính lại bố cục ngay giữa lúc đang cuộn — đúng thứ gây giật.
 * `IntersectionObserver` chạy ngoài luồng chính và chỉ báo khi trạng thái
 * thật sự đổi.
 *
 * <h3>Ảnh hỏng là trạng thái được thiết kế sẵn</h3>
 *
 * Hệ thống mặc định không tải nội dung ảnh, nên thẻ `<img>` trỏ thẳng về máy
 * chủ gốc và một phần sẽ trả 403 (chống hotlink) hoặc 404. `onError` chuyển ô
 * đó thành khối giữ chỗ có văn bản thay thế, vẫn bấm được để tới trang. Xoá
 * hẳn ô đi thì lưới nhảy chỗ ngay trước mắt người đang đọc.
 */
function ImageResultGrid({ onMeta }: Props): JSX.Element {
  const query = useSearchViewStore((state) => state.query)
  const clearSearch = useSearchViewStore((state) => state.clear)
  const navigate = useTabStore((state) => state.navigate)

  const [images, setImages] = useState<ImageResultDto[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [broken, setBroken] = useState<Set<string>>(new Set())

  const sentinelRef = useRef<HTMLDivElement>(null)

  // Chốt chống gọi chồng. `loading` là state nên trong cùng một lượt render nó
  // vẫn mang giá trị cũ — IntersectionObserver có thể bắn hai lần trước khi
  // React kịp render lại, và thế là hai request cho cùng một lô. Ref cập nhật
  // ĐỒNG BỘ nên nó chặn được ca đó.
  const inFlight = useRef(false)

  const loadNext = useCallback(async () => {
    if (!query || inFlight.current || !hasMore) {
      return
    }
    inFlight.current = true
    setLoading(true)

    const next = page + 1
    try {
      const response = await searchImages(query, next, BATCH_SIZE)
      setImages((prev) => {
        // Khử trùng phòng thủ. Máy chủ đã cắt lát trên một danh sách ổn định
        // nên về lý thuyết không có trùng — nhưng nếu một phiên crawl chèn ảnh
        // mới vào giữa hai lần cuộn thì ranh giới lát có thể xê dịch. React sẽ
        // cảnh báo trùng `key` và hiện ảnh hai lần; rẻ hơn nhiều là chặn ở đây.
        const seen = new Set(prev.map((image) => image.imageUrl))
        return [...prev, ...response.results.filter((image) => !seen.has(image.imageUrl))]
      })
      setHasMore(response.hasMore)
      setPage(next)
      setError(null)
      onMeta?.({ query, total: response.totalResults, timeTakenMs: response.timeTakenMs })
    } catch {
      setError(
        'Không thể kết nối tới máy chủ tìm kiếm (http://localhost:8080). Hãy chắc chắn backend đang chạy.'
      )
      setHasMore(false)
      onMeta?.({ query, total: 0, timeTakenMs: 0 })
    } finally {
      inFlight.current = false
      setLoading(false)
    }
    // onMeta không nằm trong deps: nó là hàm mới ở mỗi lần render cha, đưa vào
    // sẽ dựng lại observer liên tục.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, page, hasMore])

  // MỘT cơ chế cho MỌI lô, kể cả lô đầu.
  //
  // Không có effect riêng để nạp lô đầu, và đó là chủ ý kép:
  //
  //   1. Gọi loadNext() thẳng trong effect nghĩa là gọi setState đồng bộ
  //      trong effect — React render thêm một lượt ngay lập tức, và ESLint
  //      chặn đúng ở đó.
  //   2. Quan trọng hơn: ô canh nằm ở đáy một lưới RỖNG thì vốn đã nằm trong
  //      tầm nhìn, nên observer tự bắn ngay khi gắn. Lô đầu và lô thứ mười đi
  //      qua đúng một đường mã — không có nhánh "lần đầu" nào để hỏng riêng.
  //
  // Điều kiện bắt buộc: ô canh phải LUÔN được render, kể cả lúc đang hiện
  // khung xương hay trạng thái rỗng. Trả về sớm mà bỏ nó đi thì observer
  // không có gì để theo dõi và lưới đứng im mãi mãi.
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
      { rootMargin: PREFETCH_MARGIN }
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [loadNext, hasMore])

  const open = (image: ImageResultDto): void => {
    navigate(image.pageUrl)
    clearSearch()
  }

  const empty = images.length === 0
  const firstLoad = empty && loading
  const nothingFound = empty && !loading && !hasMore && !error

  return (
    <>
      {empty && error && (
        <div className="flex items-start gap-3 rounded-2xl border border-danger/25 bg-danger/5 px-4 py-3.5">
          <AlertIcon className="mt-0.5 h-5 w-5 shrink-0 text-danger" />
          <p className="text-sm text-danger">{error}</p>
        </div>
      )}

      {firstLoad && <GridSkeleton />}
      {nothingFound && <EmptyState />}

      <div className="columns-2 gap-4 md:columns-3 xl:columns-4">
        {images.map((image, index) => {
          const isBroken = broken.has(image.imageUrl)
          return (
            <figure
              key={image.imageUrl}
              className="animate-fade-up mb-4 break-inside-avoid"
              // Chỉ làm hiệu ứng cho lô mới nhất: `index % BATCH_SIZE` khiến độ
              // trễ luôn nằm trong khoảng 0–7 nhịp, thay vì lớn dần theo tổng
              // số ảnh và làm ảnh thứ 200 chờ vài giây mới hiện.
              style={{ animationDelay: `${(index % BATCH_SIZE) * 25}ms` }}
            >
              <button
                onClick={() => open(image)}
                title={`${image.pageTitle}\n${image.pageUrl}`}
                className="group block w-full overflow-hidden rounded-xl border border-line bg-raised
                           text-left transition hover:border-brand/40
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
              >
                <div className="relative">
                  {isBroken ? (
                    <div className="flex h-32 flex-col items-center justify-center gap-1.5 px-3 text-center">
                      <GlobeIcon className="h-5 w-5 text-faint" />
                      <span className="line-clamp-2 text-[11px] leading-snug text-faint">
                        {image.altText || 'Không tải được ảnh'}
                      </span>
                    </div>
                  ) : (
                    <img
                      src={image.imageUrl}
                      alt={image.altText}
                      // Trình duyệt tự hoãn tải ảnh ngoài tầm nhìn. Cộng với
                      // cuộn vô hạn: lô về từ máy chủ nhưng ảnh chỉ thật sự
                      // được tải khi sắp hiện ra.
                      loading="lazy"
                      decoding="async"
                      referrerPolicy="no-referrer"
                      onError={() =>
                        setBroken((prev) => {
                          const next = new Set(prev)
                          next.add(image.imageUrl)
                          return next
                        })
                      }
                      className="w-full bg-raised object-cover transition duration-300 group-hover:scale-[1.02]"
                    />
                  )}

                  {image.missingAlt && (
                    <span
                      title="Ảnh này không có văn bản thay thế (alt) — trình đọc màn hình sẽ bỏ qua"
                      className="absolute right-2 top-2 rounded-full bg-danger/90 px-2 py-0.5
                                 text-[10px] font-semibold text-white shadow-sm"
                    >
                      thiếu alt
                    </span>
                  )}
                </div>
              </button>

              <figcaption className="mt-2 px-0.5">
                <button
                  onClick={() => open(image)}
                  className="block w-full text-left text-[13px] leading-snug text-link
                             hover:underline focus-visible:outline-none"
                >
                  <span className="line-clamp-2">{image.pageTitle}</span>
                </button>

                <div className="mt-1.5 flex items-center gap-1.5">
                  <span
                    className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[8px] font-bold text-white"
                    style={{ background: siteGradient(image.pageUrl) }}
                  >
                    {siteInitial(image.pageUrl)}
                  </span>
                  <span className="truncate text-[11px] text-faint">
                    {image.host || hostOf(image.pageUrl)}
                  </span>
                  {image.width > 0 && image.height > 0 && (
                    <span className="ml-auto shrink-0 text-[10px] tabular-nums text-faint">
                      {image.width}×{image.height}
                    </span>
                  )}
                </div>
              </figcaption>
            </figure>
          )
        })}
      </div>

      {/* Ô canh vô hình: lọt vào tầm nhìn = đã cuộn gần tới đáy = nạp lô sau. */}
      <div ref={sentinelRef} aria-hidden className="h-1" />

      {loading && (
        <div className="flex items-center justify-center gap-2 py-6 text-[13px] text-muted">
          <SpinnerIcon className="h-4 w-4" />
          Đang tải thêm ảnh…
        </div>
      )}

      {!hasMore && !loading && images.length > 0 && (
        <p className="py-6 text-center text-[12px] text-faint">
          Đã hiện hết {images.length.toLocaleString('vi-VN')} ảnh tìm được.
        </p>
      )}

      {/* Lỗi ở một lô SAU: giữ nguyên ảnh đã có, chỉ báo ở cuối. Xoá cả lưới
          vì một lô hỏng là mất luôn thứ người dùng đang xem. */}
      {error && images.length > 0 && (
        <div className="flex items-center justify-center gap-2 py-4 text-[12px] text-danger">
          <AlertIcon className="h-4 w-4" />
          Không tải được thêm ảnh.
        </div>
      )}
    </>
  )
}

function EmptyState(): JSX.Element {
  return (
    <div className="flex flex-col items-center gap-2 py-20 text-center">
      <SearchIcon className="h-9 w-9 text-faint" />
      <p className="text-[15px] text-ink">Không tìm thấy ảnh nào.</p>
      <p className="max-w-md text-[13px] text-muted">
        Ảnh chỉ được ghi nhận trong lúc crawl. Nếu tab &ldquo;Tất cả&rdquo; có kết quả mà đây thì
        không, nghĩa là những trang đó chưa được thu thập ảnh — hãy chạy một phiên crawl mới.
      </p>
    </div>
  )
}

/** Khung xương giữ đúng bố cục masonry, để lưới không nhảy khi ảnh về. */
function GridSkeleton(): JSX.Element {
  const heights = [180, 130, 220, 160, 200, 140, 190, 150]
  return (
    <div className="columns-2 gap-4 md:columns-3 xl:columns-4">
      {heights.map((height, index) => (
        <div key={index} className="mb-4 break-inside-avoid">
          <div className="animate-pulse rounded-xl bg-raised" style={{ height }} />
          <div className="mt-2 h-3 w-4/5 animate-pulse rounded bg-raised" />
          <div className="mt-1.5 h-2.5 w-2/5 animate-pulse rounded bg-raised" />
        </div>
      ))}
    </div>
  )
}

export default ImageResultGrid
