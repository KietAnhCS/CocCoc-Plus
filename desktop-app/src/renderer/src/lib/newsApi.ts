import { getJson } from './searchApi'

/**
 * Dòng tin cho trang tab mới.
 *
 * <h3>Vì sao có tệp này thay vì gọi thẳng /api/search</h3>
 *
 * Bản trước dựng dòng tin bằng cách chạy sáu truy vấn theo chủ đề
 * (`thời sự`, `kinh tế`, `thể thao`…) rồi lấy top-2 mỗi chủ đề. Cách đó có ba
 * khiếm khuyết mà không sửa lẻ được:
 *
 *   1. **Sáu vòng mạng** cho một lần mở tab, và mỗi vòng là một lần xếp hạng
 *      đầy đủ ở máy chủ — trong khi ở đây không hề có ý định tìm kiếm gì.
 *   2. **Không có ảnh.** Kết quả tìm kiếm không mang ảnh, nên thẻ tin phải vẽ
 *      một ô gradient kèm chữ cái đầu tên miền thay cho ảnh.
 *   3. **Không cuộn thêm được.** Sáu chủ đề × 2 bài là hết; muốn thêm thì phải
 *      nghĩ ra chủ đề mới, chứ không phải lấy tiếp.
 *
 * `/api/feed` giải cả ba: một vòng mạng mỗi lô, có ảnh, và phân trang được.
 */

/** Một thẻ tin trong dòng tin trang chủ — luôn kèm ảnh. */
export interface FeedCard {
  url: string
  title: string
  snippet: string
  imageUrl: string
  altText: string
  host: string
}

export interface FeedResponse {
  results: FeedCard[]
  page: number
  pageSize: number
  totalResults: number
  hasMore: boolean
  /**
   * Số tài liệu trong chỉ mục.
   *
   * Để giao diện phân biệt hai ca rỗng hoàn toàn khác nhau:
   *
   *   indexedDocuments === 0  → chưa crawl gì cả
   *   indexedDocuments > 0    → có bài, nhưng chưa bài nào được thu thập ảnh
   *                             (thường là vừa khởi động lại backend — kho ảnh
   *                             nằm trong bộ nhớ nên trắng)
   *
   * Hai ca đó cần hai câu hướng dẫn khác hẳn nhau; nói nhầm là đẩy người dùng
   * đi sửa một thứ không hỏng.
   */
  indexedDocuments: number
}

/**
 * Hạt giống xáo trộn, sinh MỘT LẦN cho mỗi lần nạp cửa sổ.
 *
 * Đây là thứ giữ cho dòng tin vừa ngẫu nhiên vừa phân trang được: máy chủ xáo
 * danh sách bằng đúng hạt giống này, nên mọi lô nhìn cùng một hoán vị và lô 2
 * nối khít vào sau lô 1. Không có nó thì mỗi lô xáo lại từ đầu, và bài vừa lặp
 * vừa thiếu khi cuộn — đúng lỗi mà tab Hình ảnh đã vấp một lần.
 *
 * Đặt ở tầng module chứ không phải trong component: mở thêm tab mới trong cùng
 * cửa sổ vẫn giữ nguyên dòng tin. Quay lại một tab cũ mà thấy nội dung nhảy
 * sang bài khác thì rất khó chịu.
 */
const FEED_SEED = Math.floor(Math.random() * 1_000_000)

export async function fetchFeed(page = 1, size = 12): Promise<FeedResponse> {
  const raw = await getJson<Partial<FeedResponse>>('/api/feed', {
    seed: FEED_SEED,
    page,
    size
  })

  // Lọc phòng thủ: máy chủ đã chỉ trả bài có ảnh, nhưng một thẻ thiếu `url`
  // hay `imageUrl` sẽ tạo ra một ô vỡ không bấm được. Rẻ hơn nhiều là bỏ nó ở
  // đây so với việc dò một ô hỏng lẻ trong lưới.
  const results = (raw.results ?? []).filter(
    (card): card is FeedCard => Boolean(card?.url) && Boolean(card?.imageUrl)
  )

  return {
    results,
    page: raw.page ?? page,
    pageSize: raw.pageSize ?? size,
    totalResults: raw.totalResults ?? results.length,
    hasMore: raw.hasMore ?? false,
    indexedDocuments: raw.indexedDocuments ?? 0
  }
}
