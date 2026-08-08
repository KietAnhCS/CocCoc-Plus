import { useEffect, useState, type JSX } from 'react'
import { searchImages, type ImageResponseDto, type ImageResultDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import { AlertIcon, GlobeIcon, SearchIcon } from './icons'

const IMAGE_COUNT = 40

interface ImageOutcome {
  key: string
  response: ImageResponseDto | null
  error: string | null
}

export interface ImageMeta {
  loading: boolean
  total: number
  timeTakenMs: number
}

interface Props {
  /** Báo số liệu ngược lên thanh tiêu đề, để nó khỏi phải tự gọi API lần nữa. */
  onMeta?: (meta: ImageMeta) => void
}

/**
 * Tab "Hình ảnh" — lưới ảnh, mỗi ảnh kèm tiêu đề và liên kết tới trang chứa nó.
 *
 * <h3>Vì sao mỗi ảnh phải có tiêu đề và nguồn</h3>
 *
 * Đây là thứ phân biệt một kết quả ảnh dùng được với một lưới ảnh vô danh.
 * Người dùng bấm vào ảnh là để **tới trang chứa nó**, không phải để xem riêng
 * bức ảnh — đúng cách Cốc Cốc, Google Images và Bing đều làm. Nên mỗi ô mang
 * theo tiêu đề trang, tên miền, và bấm vào là điều hướng tới trang đó.
 *
 * <h3>Bố cục kiểu masonry, không dùng thư viện</h3>
 *
 * Ảnh có tỷ lệ khung hình rất khác nhau và phần lớn trang **không khai báo**
 * `width`/`height` trong HTML — đo trên trang thật: vnexpress.net 0%,
 * tuoitre.vn 13%. Nên không thể biết trước kích thước để dựng một lưới đều.
 *
 * `columns-*` của CSS giải đúng bài toán đó: mỗi cột tự xếp chồng, chiều cao
 * tuỳ ảnh, không cần JavaScript đo đạc và không cần thư viện masonry.
 *
 * <h3>Ảnh hỏng là chuyện BÌNH THƯỜNG ở đây</h3>
 *
 * Hệ thống mặc định **không tải nội dung ảnh** — nó chỉ lưu địa chỉ. Nên thẻ
 * `<img>` trỏ thẳng về máy chủ gốc, và một phần sẽ trả 403 vì chống hotlink,
 * hoặc 404 vì trang đã đổi.
 *
 * Vì vậy `onError` không phải trường hợp ngoại lệ mà là một **trạng thái được
 * thiết kế sẵn**: ô đó chuyển thành khối giữ chỗ có văn bản thay thế, và vẫn
 * bấm được để tới trang. Xoá hẳn ô đi thì lưới nhảy chỗ trong lúc người dùng
 * đang nhìn — khó chịu hơn nhiều so với một ô xám.
 */
function ImageResultGrid({ onMeta }: Props): JSX.Element {
  const query = useSearchViewStore((state) => state.query)
  const clearSearch = useSearchViewStore((state) => state.clear)
  const navigate = useTabStore((state) => state.navigate)

  const [outcome, setOutcome] = useState<ImageOutcome | null>(null)
  const [broken, setBroken] = useState<Set<string>>(new Set())

  const requestKey = query ?? ''

  useEffect(() => {
    if (!query) {
      return undefined
    }
    let cancelled = false
    // KHÔNG reset `broken` ở đây. Gọi setState đồng bộ trong effect làm React
    // render lại thêm một lượt ngay lập tức (cascading render), và ESLint chặn
    // đúng vì lý do đó.
    //
    // Thay vào đó, component cha truyền `key={query}` — mỗi truy vấn mới là
    // một lần gắn lại component, nên MỌI state cục bộ tự về giá trị đầu.
    // React đã có sẵn cơ chế cho việc này; viết tay lại chỉ là một bản kém hơn.
    onMeta?.({ loading: true, total: 0, timeTakenMs: 0 })

    searchImages(query, IMAGE_COUNT)
      .then((response) => {
        if (cancelled) return
        setOutcome({ key: requestKey, response, error: null })
        onMeta?.({
          loading: false,
          total: response.totalResults,
          timeTakenMs: response.timeTakenMs
        })
      })
      .catch(() => {
        if (cancelled) return
        setOutcome({
          key: requestKey,
          response: null,
          error:
            'Không thể kết nối tới máy chủ tìm kiếm (http://localhost:8080). Hãy chắc chắn backend đang chạy.'
        })
        onMeta?.({ loading: false, total: 0, timeTakenMs: 0 })
      })

    return () => {
      cancelled = true
    }
    // onMeta cố ý KHÔNG nằm trong danh sách phụ thuộc: nó là một hàm mới ở mỗi
    // lần render cha, nên đưa vào sẽ tạo vòng lặp gọi API vô tận.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, requestKey])

  const settled = outcome?.key === requestKey ? outcome : null
  const loading = settled === null
  const response = settled?.response ?? null
  const error = settled?.error ?? null

  const open = (image: ImageResultDto): void => {
    navigate(image.pageUrl)
    clearSearch()
  }

  if (loading) {
    return <GridSkeleton />
  }

  if (error) {
    return (
      <div className="flex items-start gap-3 rounded-2xl border border-danger/25 bg-danger/5 px-4 py-3.5">
        <AlertIcon className="mt-0.5 h-5 w-5 shrink-0 text-danger" />
        <p className="text-sm text-danger">{error}</p>
      </div>
    )
  }

  if (!response || response.results.length === 0) {
    return <EmptyState pagesScanned={response?.pagesScanned ?? 0} />
  }

  return (
    <div className="columns-2 gap-4 md:columns-3 xl:columns-4">
      {response.results.map((image, index) => {
        const isBroken = broken.has(image.imageUrl)
        return (
          <figure
            key={image.imageUrl}
            className="animate-fade-up mb-4 break-inside-avoid"
            style={{ animationDelay: `${Math.min(index, 12) * 20}ms` }}
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
                    loading="lazy"
                    decoding="async"
                    // Máy chủ gốc thấy được địa chỉ trang giới thiệu nếu không
                    // đặt referrerPolicy. Với một trình duyệt hướng riêng tư
                    // thì không nên rò rỉ điều đó — và nhiều CDN vẫn phục vụ
                    // ảnh bình thường khi không có Referer.
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
  )
}

/**
 * Hai ca rỗng HOÀN TOÀN khác nhau, và nói nhầm thì người dùng đi sửa sai chỗ.
 *
 * `pagesScanned === 0` — truy vấn không khớp trang nào. Lỗi ở truy vấn.
 * `pagesScanned > 0`   — có trang khớp, nhưng chưa trang nào được Image
 *                        Download Service xử lý. Lỗi ở dữ liệu, không phải
 *                        ở truy vấn. Bảo họ "thử từ khoá khác" lúc này là
 *                        đẩy họ đi sửa một thứ không hỏng.
 */
function EmptyState({ pagesScanned }: { pagesScanned: number }): JSX.Element {
  if (pagesScanned > 0) {
    return (
      <div className="flex flex-col items-center gap-2 py-20 text-center">
        <AlertIcon className="h-9 w-9 text-warn" />
        <p className="text-[15px] text-ink">
          Tìm thấy {pagesScanned.toLocaleString('vi-VN')} trang, nhưng chưa trang nào có ảnh được
          thu thập.
        </p>
        <p className="max-w-md text-[13px] text-muted">
          Ảnh chỉ được ghi nhận trong lúc crawl. Hãy chạy một phiên crawl mới — kết quả sẽ xuất hiện
          ở đây mà không cần lập chỉ mục lại.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col items-center gap-2 py-20 text-center">
      <SearchIcon className="h-9 w-9 text-faint" />
      <p className="text-[15px] text-ink">Không tìm thấy ảnh nào.</p>
      <p className="max-w-sm text-[13px] text-muted">Thử bớt từ khoá hoặc kiểm tra chính tả.</p>
    </div>
  )
}

/** Khung xương giữ đúng bố cục masonry, để lưới không nhảy khi ảnh về. */
function GridSkeleton(): JSX.Element {
  const heights = [180, 130, 220, 160, 200, 140, 190, 150, 210, 170, 145, 195]
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
