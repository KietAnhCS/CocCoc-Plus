import { search, type SearchResultDto } from './searchApi'

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

export async function fetchHotNews(perTopic = 2): Promise<NewsCard[]> {
  const settled = await Promise.allSettled(TOPICS.map((topic) => search(topic.query, 1, perTopic)))

  const byTopic = settled.map((outcome, index) =>
    outcome.status === 'fulfilled'
      ? outcome.value.results.map((result) => toCard(result, TOPICS[index].label))
      : []
  )

  if (byTopic.every((cards) => cards.length === 0)) {
    throw new Error('Không lấy được tin từ backend')
  }

  const seen = new Set<string>()
  const cards: NewsCard[] = []
  for (let rank = 0; rank < perTopic; rank++) {
    for (const topicCards of byTopic) {
      const card = topicCards[rank]
      if (card && !seen.has(card.url)) {
        seen.add(card.url)
        cards.push(card)
      }
    }
  }
  return cards
}
