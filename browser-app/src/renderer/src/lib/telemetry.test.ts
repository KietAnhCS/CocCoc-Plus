import { describe, it, expect } from 'vitest'
import { buildEventBody, readSessionId, type SessionStore } from './telemetry'

/** Kho lưu giả — đủ cho `readSessionId`, không cần cả `localStorage`. */
function fakeStore(
  initial: Record<string, string> = {}
): SessionStore & { data: Record<string, string> } {
  const data = { ...initial }
  return {
    data,
    getItem: (key) => data[key] ?? null,
    setItem: (key, value) => {
      data[key] = value
    }
  }
}

describe('readSessionId', () => {
  it('sinh mã mới và ghi lại ở lần gọi đầu', () => {
    const store = fakeStore()

    const id = readSessionId(store, () => 'ma-moi')

    expect(id).toBe('ma-moi')
    expect(store.getItem('vnsearch-session-id')).toBe('ma-moi')
  })

  it('dùng lại mã đã lưu — nếu không thì mỗi lần mở app là một "người" mới', () => {
    const store = fakeStore({ 'vnsearch-session-id': 'ma-cu' })
    let generated = 0

    const id = readSessionId(store, () => {
      generated++
      return 'khong-duoc-dung'
    })

    expect(id).toBe('ma-cu')
    expect(generated).toBe(0)
  })
})

describe('buildEventBody', () => {
  it('sự kiện mở ứng dụng chỉ mang mã phiên', () => {
    expect(buildEventBody({ type: 'visit' }, 'p1')).toEqual({ type: 'visit', sessionId: 'p1' })
  })

  it('sự kiện tìm kiếm mang đủ truy vấn, số kết quả và độ trễ', () => {
    const body = buildEventBody(
      { type: 'search', query: 'hà nội', resultCount: 12, tookMs: 18.6 },
      'p1'
    )

    expect(body).toEqual({
      type: 'search',
      sessionId: 'p1',
      query: 'hà nội',
      resultCount: 12,
      tookMs: 19
    })
  })

  it('không bao giờ gửi độ trễ âm', () => {
    const body = buildEventBody({ type: 'search', query: 'a', resultCount: 0, tookMs: -5 }, 'p1')
    expect(body.tookMs).toBe(0)
  })

  it('cắt truy vấn quá dài ngay tại phía gửi', () => {
    const body = buildEventBody(
      { type: 'search', query: 'x'.repeat(500), resultCount: 1, tookMs: 1 },
      'p1'
    )
    expect(String(body.query)).toHaveLength(200)
  })

  it('cắt URL quá dài', () => {
    const body = buildEventBody(
      { type: 'click', url: `https://a.vn/${'x'.repeat(900)}`, position: 3 },
      'p1'
    )
    expect(String(body.url)).toHaveLength(500)
  })

  it('sự kiện bấm giữ nguyên thứ hạng — con số đo chất lượng xếp hạng', () => {
    expect(buildEventBody({ type: 'click', url: 'https://a.vn/1', position: 21 }, 'p1')).toEqual({
      type: 'click',
      sessionId: 'p1',
      url: 'https://a.vn/1',
      position: 21
    })
  })
})
