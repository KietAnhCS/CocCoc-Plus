/**
 * Ham goi REST API cua backend search-engine (http://localhost:8080).
 * Xem hop dong day du o docs/api-examples.http.
 */

const BASE_URL = 'http://localhost:8080'

export interface SearchResultDto {
  title: string
  url: string
  snippet: string
  score: number
  tfidfScore: number
  pageRankScore: number
  crawledAt: string
}

export interface SearchResponseDto {
  query: string
  totalResults: number
  page: number
  timeTakenMs: number
  results: SearchResultDto[]
}

export async function search(query: string, page = 1, size = 10): Promise<SearchResponseDto> {
  const url = new URL(`${BASE_URL}/api/search`)
  url.searchParams.set('q', query)
  url.searchParams.set('page', String(page))
  url.searchParams.set('size', String(size))

  const response = await fetch(url.toString())
  if (!response.ok) {
    throw new Error(`Tim kiem that bai (HTTP ${response.status})`)
  }
  return response.json()
}

export async function suggest(prefix: string, limit = 10): Promise<string[]> {
  const url = new URL(`${BASE_URL}/api/suggest`)
  url.searchParams.set('prefix', prefix)
  url.searchParams.set('limit', String(limit))

  const response = await fetch(url.toString())
  if (!response.ok) {
    return []
  }
  const data = (await response.json()) as { suggestions?: string[] }
  return data.suggestions ?? []
}
