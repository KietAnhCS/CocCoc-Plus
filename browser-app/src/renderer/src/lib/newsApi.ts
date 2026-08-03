import { search, type SearchResultDto } from './searchApi'

/**
 * Nguồn cho khu "Tin nóng" ở trang chủ.
 *
 * Backend KHÔNG có endpoint bảng tin riêng, nên phần này lấy tin bằng đúng
 * công cụ sẵn có: chạy vài truy vấn theo chuyên mục qua /api/search rồi ghép
 * kết quả lại. Nhờ vậy thẻ tin trên trang chủ là bài THẬT trong chỉ mục —
 * bấm vào mở ra đúng trang đã crawl, không phải dữ liệu bịa.
 */

const TOPICS = [
  { label: 'Thời sự', query: 'thời sự' },
  { label: 'Kinh tế', query: 'kinh tế' },
  { label: 'Thể thao', query: 'thể thao' },
  { label: 'Công nghệ', query: 'công nghệ' },
  { label: 'Giáo dục', query: 'giáo dục' },
  { label: 'Sức khoẻ', query: 'sức khoẻ' }
] as const

export interface NewsCard {
  title: string
  url: string
  snippet: string
  category: string
  crawledAt: string
}

function toCard(result: SearchResultDto, category: string): NewsCard {
  return {
    title: result.title,
    url: result.url,
    snippet: result.snippet,
    category,
    crawledAt: result.crawledAt
  }
}

/**
 * Lấy mỗi chuyên mục `perTopic` bài, rồi XEN KẼ theo chuyên mục để lưới thẻ
 * không bị dồn một cục toàn "Thời sự" ở đầu.
 *
 * Dùng `allSettled`: một chuyên mục không ra kết quả (hoặc backend lỗi giữa
 * chừng) thì các chuyên mục còn lại vẫn hiện, trang chủ không trắng xoá.
 */
export async function fetchHotNews(perTopic = 2): Promise<NewsCard[]> {
  const settled = await Promise.allSettled(
    TOPICS.map((topic) => search(topic.query, 1, perTopic))
  )

  const byTopic: NewsCard[][] = settled.map((outcome, i) =>
    outcome.status === 'fulfilled'
      ? outcome.value.results.map((r) => toCard(r, TOPICS[i].label))
      : []
  )

  const seen = new Set<string>()
  const out: NewsCard[] = []
  for (let rank = 0; rank < perTopic; rank++) {
    for (const cards of byTopic) {
      const card = cards[rank]
      // Cùng một bài có thể khớp nhiều chuyên mục -> giữ lần xuất hiện đầu.
      if (card && !seen.has(card.url)) {
        seen.add(card.url)
        out.push(card)
      }
    }
  }
  return out
}
