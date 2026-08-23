import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  fetchLeagueFixtures,
  fetchPlayer,
  fetchPlayers,
  fetchTeamFixtures,
  fetchTeams,
  isImageEmblem,
  localDateKey,
  rankForSpotlight,
  seasonLabel,
  seasonOf,
  watchUrl,
  type FootballMatch
} from './footballApi'

/**
 * Máy khách của football-service. Cái đáng kiểm ở đây không phải "đường thành
 * công" — mà những chỗ mã CHẶN một request trước khi nó rời máy, vì mỗi lượt
 * gọi lọt qua đều ăn vào hạn mức 100 lượt mỗi ngày của gói miễn phí.
 *
 * `fetch` được thay bằng bản giả: bộ test này không được phép cần một service
 * đang chạy ở cổng 8090.
 */
function mockFetchJson(payload: unknown, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        statusText: 'OK',
        json: async () => Promise.resolve(payload)
      } as Response)
    )
  )
}

function envelope(data: unknown): unknown {
  return { data, meta: { cachedAt: '2026-08-13T15:27:16Z', source: 'cache', stale: false } }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('localDateKey', () => {
  it('lấy ngày theo múi giờ địa phương, không đổi sang UTC', () => {
    // 06:30 sáng ở UTC+7 vẫn còn là hôm QUA theo giờ UTC. Dùng
    // toISOString().slice(0,10) ở đây sẽ hỏi lịch của ngày hôm trước — một lỗi
    // chỉ xuất hiện vào sáng sớm nên rất khó bắt.
    const morning = new Date(2026, 7, 13, 6, 30)
    expect(localDateKey(morning)).toBe('2026-08-13')
  })

  it('đệm số 0 cho tháng và ngày một chữ số', () => {
    expect(localDateKey(new Date(2026, 0, 5))).toBe('2026-01-05')
  })
})

describe('isImageEmblem', () => {
  it('phân biệt địa chỉ ảnh với emoji', () => {
    // Service trả về cả hai dạng: dữ liệu thật cho địa chỉ ảnh, dữ liệu mẫu
    // cho emoji. Giao diện phải chọn đúng cách vẽ.
    expect(isImageEmblem('https://media.api-sports.io/football/teams/42.png')).toBe(true)
    expect(isImageEmblem('🔴')).toBe(false)
    expect(isImageEmblem('')).toBe(false)
  })
})

describe('seasonLabel và seasonOf', () => {
  it('gắn nhãn mùa theo năm bắt đầu', () => {
    expect(seasonLabel(2025)).toBe('2025-2026')
  })

  it('suy ra mùa từ trận sớm nhất, tháng 8 thuộc mùa của chính năm đó', () => {
    const matches = [
      match('sau', 'finished', '2026-05-24T15:00:00Z'),
      match('dau', 'finished', '2025-08-15T19:00:00Z')
    ]
    expect(seasonOf(matches)).toBe(2025)
  })

  it('trận tháng 5 thuộc mùa mang tên NĂM TRƯỚC', () => {
    expect(seasonOf([match('x', 'finished', '2026-05-24T15:00:00Z')])).toBe(2026 - 1)
  })

  it('không có trận nào thì không đoán bừa', () => {
    expect(seasonOf([])).toBeNull()
  })
})

function match(id: string, status: FootballMatch['status'], kickoff: string): FootballMatch {
  return {
    id,
    competition: 'V.League 1',
    competitionId: '340',
    competitionLogo: '',
    round: 'Vòng 12',
    status,
    elapsed: status === 'live' ? 30 : null,
    kickoff,
    homeTeam: { id: 'h', name: 'Hà Nội FC', shortName: 'Hà Nội', emblem: '🔴' },
    awayTeam: { id: 'a', name: 'Bình Định', shortName: 'Bình Định', emblem: '🟢' },
    homeScore: null,
    awayScore: null
  }
}

describe('rankForSpotlight', () => {
  it('xếp trận đang đá trước, rồi trận ĐÃ CÓ KẾT QUẢ, cuối cùng mới tới trận sắp đá', () => {
    // Thứ tự này là điểm mấu chốt: ô tỉ số chỉ có chỗ cho một trận, và thứ
    // người ta mở nó ra để xem là một CON SỐ. Một trận tối nay chưa đá thì
    // không trả lời được câu hỏi nào cả.
    const ranked = rankForSpotlight([
      match('sap', 'scheduled', '2026-08-13T20:00:00Z'),
      match('xong', 'finished', '2026-08-13T10:00:00Z'),
      match('dang', 'live', '2026-08-13T18:00:00Z')
    ])
    expect(ranked.map((item) => item.id)).toEqual(['dang', 'xong', 'sap'])
  })

  it('trận đã xong thì mới nhất trước, trận sắp đá thì gần nhất trước', () => {
    const ranked = rankForSpotlight([
      match('sap-xa', 'scheduled', '2026-08-13T22:00:00Z'),
      match('sap-gan', 'scheduled', '2026-08-13T19:00:00Z'),
      match('xong-cu', 'finished', '2026-08-13T08:00:00Z'),
      match('xong-moi', 'finished', '2026-08-13T14:00:00Z')
    ])
    expect(ranked.map((item) => item.id)).toEqual(['xong-moi', 'xong-cu', 'sap-gan', 'sap-xa'])
  })

  it('không sửa mảng gốc', () => {
    const original = [
      match('xong', 'finished', '2026-08-13T10:00:00Z'),
      match('dang', 'live', '2026-08-13T18:00:00Z')
    ]
    rankForSpotlight(original)
    expect(original.map((item) => item.id)).toEqual(['xong', 'dang'])
  })
})

describe('watchUrl', () => {
  it('dựng một truy vấn tìm kiếm, vì nhà cung cấp không bán liên kết phát sóng', () => {
    const url = new URL(watchUrl(match('x', 'scheduled', '2026-08-13T12:00:00Z')))
    expect(url.searchParams.get('q')).toBe('Hà Nội FC vs Bình Định V.League 1 trực tiếp')
  })
})

describe('fetchPlayers', () => {
  it('không gọi máy chủ khi chưa đủ 3 ký tự', async () => {
    // Ngưỡng của chính nhà cung cấp. Gọi lên chỉ nhận về 400 mà vẫn tốn một
    // vòng mạng, nên chặn ở đây.
    mockFetchJson(envelope([]))
    expect(await fetchPlayers('me')).toEqual([])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('gọi khi đã đủ ký tự, và cắt khoảng trắng thừa', async () => {
    mockFetchJson(envelope([{ id: '1', name: 'Salah' }]))
    const players = await fetchPlayers('  salah  ')

    expect(players).toHaveLength(1)
    const url = vi.mocked(fetch).mock.calls[0][0] as URL
    expect(url.pathname).toBe('/api/v1/players')
    expect(url.searchParams.get('search')).toBe('salah')
  })
})

describe('fetchTeams', () => {
  it('không gọi máy chủ khi thiếu cả tên lẫn giải', async () => {
    // Service trả 400 trong ca này. Chặn sớm để một ô tìm kiếm còn trống không
    // kịp gửi request nào đi.
    mockFetchJson(envelope([]))
    expect(await fetchTeams('', '')).toEqual([])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('gửi league và season khi liệt kê đội của một giải', async () => {
    mockFetchJson(envelope([{ id: '42', name: 'Arsenal' }]))
    await fetchTeams('', '39')

    const url = vi.mocked(fetch).mock.calls[0][0] as URL
    expect(url.searchParams.get('league')).toBe('39')
    expect(url.searchParams.has('search')).toBe(false)
  })

  it('coi data null là danh sách rỗng', async () => {
    // Go mã hoá slice nil thành `null`, không phải `[]`. Không xử lý thì giao
    // diện gọi .map trên null và vỡ giữa lúc render.
    mockFetchJson(envelope(null))
    expect(await fetchTeams('arsenal')).toEqual([])
  })
})

describe('fetchLeagueFixtures và fetchTeamFixtures', () => {
  it('gọi đúng đường dẫn lồng và kèm mùa', async () => {
    mockFetchJson(envelope([]))
    await fetchLeagueFixtures('39')
    let url = vi.mocked(fetch).mock.calls[0][0] as URL
    expect(url.pathname).toBe('/api/v1/leagues/39/fixtures')

    mockFetchJson(envelope([]))
    await fetchTeamFixtures('42')
    url = vi.mocked(fetch).mock.calls[0][0] as URL
    expect(url.pathname).toBe('/api/v1/teams/42/fixtures')
  })

  it('mã đội có ký tự lạ vẫn được mã hoá an toàn vào đường dẫn', async () => {
    // Dữ liệu mẫu dùng chính TÊN đội làm mã, nên khoảng trắng và dấu tiếng
    // Việt đều lọt vào đây.
    mockFetchJson(envelope([]))
    await fetchTeamFixtures('Hà Nội FC')

    const url = vi.mocked(fetch).mock.calls[0][0] as URL
    expect(url.pathname).toBe('/api/v1/teams/' + encodeURIComponent('Hà Nội FC') + '/fixtures')
  })

  it('giữ nguyên meta để giao diện còn nói được dữ liệu đến từ đâu', async () => {
    mockFetchJson(envelope([]))
    const result = await fetchLeagueFixtures('39')
    expect(result.meta.source).toBe('cache')
    expect(result.meta.cachedAt).toBe('2026-08-13T15:27:16Z')
  })
})

describe('fetchPlayer', () => {
  it('trả null khi service báo 404, không ném lỗi', async () => {
    // "Cầu thủ này không có số liệu ở mùa ấy" là một câu trả lời hợp lệ, không
    // phải một sự cố — với gói miễn phí thì đó là chuyện thường gặp.
    mockFetchJson({ error: { code: 'PLAYER_NOT_FOUND' } }, 404)
    expect(await fetchPlayer('276')).toBeNull()
  })

  it('ném lỗi khi service hỏng thật', async () => {
    mockFetchJson({}, 500)
    await expect(fetchPlayer('276')).rejects.toThrow()
  })

  it('bóc lớp vỏ envelope và trả về hồ sơ', async () => {
    mockFetchJson(envelope({ id: '276', name: 'Salah', statistics: [] }))
    const player = await fetchPlayer('276')
    expect(player?.name).toBe('Salah')
  })
})
