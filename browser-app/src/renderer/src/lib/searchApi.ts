const API_BASE = 'http://localhost:8080'
const REQUEST_TIMEOUT_MS = 8000

export interface SearchResultDto {
  title: string
  url: string
  snippet: string
  score: number
  pageRankScore: number
  crawledAt: string
}

export interface SearchResponseDto {
  query: string
  results: SearchResultDto[]
  totalResults: number
  page: number
  pageSize: number
  timeTakenMs: number
  droppedTerms: string[]
}

async function getJson<T>(path: string, params: Record<string, string | number>): Promise<T> {
  const url = new URL(path, API_BASE)
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, String(value))
  }

  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })

  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return (await response.json()) as T
}

function normalizeResult(raw: Partial<SearchResultDto>): SearchResultDto {
  return {
    title: raw.title ?? raw.url ?? '',
    url: raw.url ?? '',
    snippet: raw.snippet ?? '',
    score: raw.score ?? 0,
    pageRankScore: raw.pageRankScore ?? 0,
    crawledAt: raw.crawledAt ?? ''
  }
}

export async function search(query: string, page = 1, pageSize = 10): Promise<SearchResponseDto> {
  const raw = await getJson<Partial<SearchResponseDto>>('/api/search', {
    q: query,
    page,
    size: pageSize
  })

  const results = (raw.results ?? []).map(normalizeResult)

  return {
    query: raw.query ?? query,
    results,
    totalResults: raw.totalResults ?? results.length,
    page: raw.page ?? page,
    // Máy chủ trả về `pageSize` ĐÃ ÁP DỤNG, có thể khác giá trị vừa gửi lên:
    // một `size` ngoài khoảng 1..100 bị thay bằng mặc định 20. Vì vậy giá trị
    // của máy chủ mới là giá trị đúng, và `?? pageSize` chỉ còn là lối lùi cho
    // máy chủ đời cũ chưa có trường này.
    pageSize: raw.pageSize ?? pageSize,
    timeTakenMs: raw.timeTakenMs ?? 0,
    droppedTerms: raw.droppedTerms ?? []
  }
}

export async function suggest(query: string, limit = 8): Promise<string[]> {
  const trimmed = query.trim()
  if (!trimmed) {
    return []
  }

  try {
    const raw = await getJson<unknown>('/api/suggest', { q: trimmed, limit })
    const list = Array.isArray(raw) ? raw : ((raw as { suggestions?: unknown })?.suggestions ?? [])
    return Array.isArray(list)
      ? list.filter((item): item is string => typeof item === 'string')
      : []
  } catch {
    return []
  }
}
