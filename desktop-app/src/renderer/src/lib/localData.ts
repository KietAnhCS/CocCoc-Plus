/**
 * Dữ liệu cá nhân của trình duyệt — lịch sử duyệt web và lịch sử tìm kiếm — lưu NGAY TRÊN MÁY bằng localStorage.
 *
 * <p>Backend chỉ còn là search engine, không có tài khoản hay dịch vụ lưu trữ
 * nào, nên mọi thứ thuộc về người dùng nằm ở đây. Giao diện (tên hàm, kiểu trả
 * về) giữ giống bản gọi máy chủ cũ để các store không phải đổi cách dùng.
 */

export interface VisitDto {
  id: string
  url: string
  title: string
  host: string
  visitedAt: string
  visitCount: number
}

export interface SearchQueryDto {
  id: string
  query: string
  resultCount: number
  searchedAt: string
}

export interface PageDto<T> {
  content: T[]
  totalElements: number
  number: number
  size: number
}

const VISITS_KEY = 'vnsearch-visits'
const SEARCHES_KEY = 'vnsearch-searches'
/** Giữ tối đa bấy nhiêu mục để localStorage không phình mãi. */
const MAX_ITEMS = 2000

function load<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Hết dung lượng hoặc bị chặn — bỏ qua, lịch sử không phải dữ liệu sống còn.
  }
}

function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return ''
  }
}

function newId(): string {
  return crypto.randomUUID()
}

function page<T>(items: T[], pageNo: number, size: number): PageDto<T> {
  return {
    content: items.slice(pageNo * size, pageNo * size + size),
    totalElements: items.length,
    number: pageNo,
    size
  }
}

export const historyApi = {
  recordVisit: async (url: string, title: string): Promise<void> => {
    const visits = load<VisitDto[]>(VISITS_KEY, [])
    const now = new Date().toISOString()
    const existing = visits.findIndex((v) => v.url === url)
    if (existing >= 0) {
      const [old] = visits.splice(existing, 1)
      visits.unshift({
        ...old,
        title: title || old.title,
        visitedAt: now,
        visitCount: old.visitCount + 1
      })
    } else {
      visits.unshift({ id: newId(), url, title, host: hostOf(url), visitedAt: now, visitCount: 1 })
    }
    save(VISITS_KEY, visits.slice(0, MAX_ITEMS))
  },

  recordSearch: async (query: string, resultCount: number): Promise<void> => {
    const searches = load<SearchQueryDto[]>(SEARCHES_KEY, []).filter((s) => s.query !== query)
    searches.unshift({ id: newId(), query, resultCount, searchedAt: new Date().toISOString() })
    save(SEARCHES_KEY, searches.slice(0, MAX_ITEMS))
  },

  visitHistory: async (params: {
    q?: string
    page?: number
    size?: number
  }): Promise<PageDto<VisitDto>> => {
    const q = params.q?.trim().toLowerCase()
    const visits = load<VisitDto[]>(VISITS_KEY, []).filter(
      (v) => !q || v.title.toLowerCase().includes(q) || v.url.toLowerCase().includes(q)
    )
    return page(visits, params.page ?? 0, params.size ?? 100)
  },

  suggest: async (prefix: string, limit = 8): Promise<SearchQueryDto[]> => {
    const p = prefix.trim().toLowerCase()
    return load<SearchQueryDto[]>(SEARCHES_KEY, [])
      .filter((s) => s.query.toLowerCase().startsWith(p))
      .slice(0, limit)
  },

  deleteVisit: async (id: string): Promise<void> => {
    save(
      VISITS_KEY,
      load<VisitDto[]>(VISITS_KEY, []).filter((v) => v.id !== id)
    )
  },

  /** Xoá mọi lượt ghé thăm từ mốc `from` (ISO) tới nay; `undefined` = xoá hết. */
  deleteVisitRange: async (from?: string): Promise<{ deleted: number }> => {
    const visits = load<VisitDto[]>(VISITS_KEY, [])
    const kept = from ? visits.filter((v) => v.visitedAt < from) : []
    save(VISITS_KEY, kept)
    return { deleted: visits.length - kept.length }
  }
}
