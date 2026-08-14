/**
 * Máy khách của football-service — microservice Go phục vụ dữ liệu bóng đá.
 *
 * Cổng RIÊNG (8090), không phải 8080 của backend Java, và đó là điểm đáng nhớ
 * nhất ở tệp này: hai máy chủ khác nhau, vòng đời khác nhau. Backend tìm kiếm
 * tắt thì phần tìm kiếm hỏng nhưng bảng thể thao vẫn chạy, và ngược lại. Vì
 * vậy mọi hàm ở đây đều tự lo phần lỗi của mình thay vì để một lỗi mạng lan
 * lên và làm trắng cả cửa sổ.
 */

export const FOOTBALL_API_BASE = 'http://localhost:8090'

const REQUEST_TIMEOUT_MS = 8000

/** Trạng thái trận, đã được service quy về ba giá trị. */
export type MatchStatus = 'scheduled' | 'live' | 'finished'

export interface FootballTeam {
  id: string
  name: string
  shortName: string
  /**
   * Huy hiệu đội. Có thể là một ĐỊA CHỈ ẢNH (dữ liệu thật từ API-Football)
   * hoặc một ký tự emoji (dữ liệu mẫu). Giao diện phải xét cả hai — xem
   * `isImageEmblem`.
   */
  emblem: string
  /**
   * Chỉ có ở kết quả `/teams`. Danh sách trận không mang theo hai trường này
   * vì phản hồi lịch thi đấu của nhà cung cấp không có chúng — nên chúng là
   * tuỳ chọn, chứ không phải "quên điền".
   */
  country?: string
  founded?: number
  /**
   * Giải mà đội đang đá.
   *
   * Phải mang theo vì nhà cung cấp không có endpoint "lịch của một đội": lịch
   * đội được lọc ra từ lịch cả GIẢI, nên thiếu mã giải là không hỏi được gì.
   */
  leagueId?: string
}

/**
 * "2025-2026" — mùa được gọi theo NĂM BẮT ĐẦU, đúng quy ước của bóng đá châu
 * Âu: mùa khai mạc tháng 8/2025 và khép lại tháng 5/2026 là mùa 2025.
 */
export function seasonLabel(season: number): string {
  return `${season}-${season + 1}`
}

/**
 * Mùa mà một danh sách trận thuộc về, suy ra từ trận SỚM NHẤT.
 *
 * <h3>Vì sao suy ra từ dữ liệu thay vì cho người dùng chọn</h3>
 *
 * Bản trước có một ô chọn ba mùa (2022 / 2023 / 2024), bê từ `enum Season` của
 * bản iOS. Nó sai theo hai cách cùng lúc: ba mùa ấy đã cũ, và quan trọng hơn,
 * nhà cung cấp hiện tại **bỏ qua hoàn toàn** tham số mùa — gọi với `season` gì
 * cũng trả về đúng một mùa đang chạy. Một ô chọn bấm vào không đổi được gì là
 * thứ tệ hơn cả không có: người dùng bấm, thấy y nguyên, rồi kết luận dữ liệu
 * hỏng.
 *
 * Nên chỗ ấy giờ là một cái NHÃN nói đúng mùa của dữ liệu đang hiện. Ngày nào
 * nhà cung cấp mở tham số mùa thật, ô chọn quay lại — và khi đó nó sẽ có tác
 * dụng thật.
 */
export function seasonOf(matches: FootballMatch[]): number | null {
  let earliest: Date | null = null

  for (const match of matches) {
    const kickoff = new Date(match.kickoff)
    if (Number.isNaN(kickoff.getTime())) {
      continue
    }
    if (earliest === null || kickoff < earliest) {
      earliest = kickoff
    }
  }
  if (earliest === null) {
    return null
  }

  // Các giải châu Âu khởi tranh khoảng tháng 7-8. Một trận đá tháng 5 thuộc về
  // mùa mang tên NĂM TRƯỚC, nên tháng 1-6 phải lùi một năm.
  const year = earliest.getFullYear()
  return earliest.getMonth() + 1 >= 7 ? year : year - 1
}

export interface FootballMatch {
  id: string
  competition: string
  competitionId: string
  competitionLogo: string
  round: string
  status: MatchStatus
  /** Số phút đã đá; chỉ khác null khi trận đang diễn ra. */
  elapsed: number | null
  kickoff: string
  homeTeam: FootballTeam
  awayTeam: FootballTeam
  homeScore: number | null
  awayScore: number | null
}

export interface FootballLeague {
  id: string
  name: string
  country: string
  icon: string
  flag: string
  status: string
}

/**
 * Xuất xứ của dữ liệu, do service khai báo.
 *
 * Giao diện PHẢI hiện thứ này ra chứ không được nuốt đi:
 *
 *   live   — vừa hỏi nhà cung cấp xong
 *   cache  — lấy từ đệm, còn hạn (tối đa 15 phút)
 *   stale  — đệm đã quá hạn, không làm mới được (hết hạn mức hoặc API lỗi)
 *   unavailable — không lấy được gì: chưa có khoá, hoặc nhà cung cấp lỗi và
 *                 trong đệm cũng không có bản cũ nào
 *
 * Hai giá trị cuối là lý do tồn tại của trường này. Một bảng tỉ số không nói
 * rõ mình đang hiển thị dữ liệu cũ — hay không có dữ liệu nào cả — là một bảng
 * tỉ số nói dối.
 */
export type FootballSource = 'live' | 'cache' | 'stale' | 'unavailable'

export interface FootballEnvelope<T> {
  data: T
  meta: {
    cachedAt: string
    source: FootballSource
    stale: boolean
  }
}

async function getEnvelope<T>(
  path: string,
  params: Record<string, string> = {}
): Promise<FootballEnvelope<T>> {
  const url = new URL(path, FOOTBALL_API_BASE)
  for (const [key, value] of Object.entries(params)) {
    if (value !== '') {
      url.searchParams.set(key, value)
    }
  }

  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })

  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return (await response.json()) as FootballEnvelope<T>
}

/**
 * Ngày theo múi giờ ĐỊA PHƯƠNG, định dạng YYYY-MM-DD.
 *
 * Không dùng `toISOString().slice(0, 10)`: hàm đó đổi sang UTC trước, nên với
 * người ở Việt Nam (UTC+7), mọi lúc trước 07:00 sáng sẽ hỏi lịch của NGÀY HÔM
 * QUA. Người mở trình duyệt lúc 6 giờ sáng sẽ thấy "hôm nay" là ngày cũ, và
 * đó là loại lỗi chỉ xuất hiện vào sáng sớm nên rất khó bắt.
 */
export function localDateKey(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** Huy hiệu là ảnh hay là emoji. */
export function isImageEmblem(emblem: string): boolean {
  return /^https?:\/\//i.test(emblem)
}

/**
 * Địa chỉ "xem trận này".
 *
 * API-Football KHÔNG trả về đường dẫn xem trực tiếp — họ bán dữ liệu, không
 * bán bản quyền phát sóng. Nên chỗ này dựng một truy vấn tìm kiếm, vốn là cách
 * người ta thật sự tìm chỗ xem một trận cụ thể.
 *
 * Đặt ở đây chứ không ở component vì HAI nơi cùng dùng: ô tỉ số trên trang chủ
 * và bảng bên Thể thao. Nếu sau này có nguồn liên kết phát sóng thật, đây là
 * hàm DUY NHẤT phải sửa.
 */
export function watchUrl(match: FootballMatch): string {
  const query = `${match.homeTeam.name} vs ${match.awayTeam.name} ${match.competition} trực tiếp`
  return `https://www.google.com/search?q=${encodeURIComponent(query)}`
}

/**
 * Xếp lại một danh sách trận theo thứ tự ĐÁNG HIỆN NHẤT:
 * đang đá → đã có kết quả (mới nhất trước) → sắp đá (gần nhất trước).
 *
 * <h3>Vì sao trận đã xong đứng TRƯỚC trận sắp đá</h3>
 *
 * Ô tỉ số chỉ có chỗ cho một trận, và thứ người ta mở nó ra để xem là một CON
 * SỐ. Bản trước xếp trận sắp đá lên trước, nên gần như lúc nào nó cũng hiện
 * "– –" kèm một giờ bóng lăn — tức là một lời hẹn, không phải một kết quả. Một
 * trận đã đá xong tối qua vẫn trả lời được câu hỏi "đội tôi thắng chưa"; một
 * trận tối nay thì không.
 */
export function rankForSpotlight(matches: FootballMatch[]): FootballMatch[] {
  const weight = (status: MatchStatus): number =>
    status === 'live' ? 0 : status === 'finished' ? 1 : 2

  return [...matches].sort((a, b) => {
    const byStatus = weight(a.status) - weight(b.status)
    if (byStatus !== 0) {
      return byStatus
    }
    // Đã xong: mới nhất trước. Sắp đá: gần nhất trước.
    return a.status === 'finished'
      ? b.kickoff.localeCompare(a.kickoff)
      : a.kickoff.localeCompare(b.kickoff)
  })
}

/** Số ngày lùi lại tối đa khi đi tìm kết quả — xem `fetchRecentResults`. */
const RESULT_LOOKBACK_DAYS = 4

/**
 * Lịch thi đấu CÓ KẾT QUẢ, lùi dần từ hôm nay.
 *
 * Hôm nay thường chỉ có những trận chưa đá — nhất là khi mở máy vào buổi sáng.
 * Hàm này lùi lại từng ngày cho tới khi gặp ngày đã có tỉ số, nên ô tỉ số luôn
 * hiện được một kết quả thật thay vì một dãy gạch ngang.
 *
 * Lùi tối đa bốn ngày, và điều đó gần như không tốn hạn mức: service giữ lịch
 * của NGÀY ĐÃ QUA trong bảy ngày (kết quả đã cố định thì hỏi lại làm gì), nên
 * từ lần thứ hai trở đi mọi ngày cũ đều lấy từ đệm.
 */
export async function fetchRecentResults(): Promise<FootballMatch[]> {
  const today = new Date()
  let firstError: unknown = null

  for (let back = 0; back < RESULT_LOOKBACK_DAYS; back++) {
    const day = new Date(today)
    day.setDate(day.getDate() - back)

    try {
      const envelope = await fetchFixtures(localDateKey(day))
      const useful = envelope.data.filter(
        (match) => match.status === 'live' || match.homeScore !== null
      )

      // Ưu tiên trận thuộc giải CÓ TÊN.
      //
      // Nhà cung cấp chỉ gắn mã giải vào mỗi trận, và bảng tra tên chỉ phủ vài
      // trăm giải lớn — hàng trăm giải nhỏ khắp thế giới không tra ra tên, mà
      // cũng không có endpoint nào tra được. Một thẻ tỉ số ghi "Portland
      // Timbers 3 – 1 Tijuana" mà không nói đó là giải gì thì gần như vô
      // dụng, trong khi cùng ngày ấy vẫn có những trận ở giải tra được tên.
      const named = useful.filter((match) => match.competition !== '')
      if (named.length > 0) {
        return rankForSpotlight(named)
      }
      if (useful.length > 0) {
        return rankForSpotlight(useful)
      }
    } catch (cause) {
      // Ngày đầu hỏng thì có thể cả service đang tắt; nhưng cũng có thể chỉ
      // một ngày lỗi. Thử tiếp, và chỉ ném lại nếu KHÔNG ngày nào chạy được.
      firstError ??= cause
    }
  }

  if (firstError !== null) {
    throw firstError
  }
  return []
}

export async function fetchFixtures(
  date: string,
  leagueId = ''
): Promise<FootballEnvelope<FootballMatch[]>> {
  const envelope = await getEnvelope<FootballMatch[] | null>('/api/v1/fixtures', {
    date,
    league: leagueId
  })
  return { ...envelope, data: envelope.data ?? [] }
}

export async function fetchLeagues(): Promise<FootballEnvelope<FootballLeague[]>> {
  const envelope = await getEnvelope<FootballLeague[] | null>('/api/v1/leagues', {})
  return { ...envelope, data: envelope.data ?? [] }
}

/**
 * Lịch CẢ MÙA của một giải.
 *
 * Khác hẳn `fetchFixtures`, vốn là ảnh chụp một ngày. Service để hai thứ này ở
 * hai đường dẫn riêng vì chúng có vòng đời khác nhau — lịch một ngày làm mới
 * mỗi 15 phút, lịch cả mùa mỗi ngày một lần.
 */
export async function fetchLeagueFixtures(
  leagueId: string
): Promise<FootballEnvelope<FootballMatch[]>> {
  const envelope = await getEnvelope<FootballMatch[] | null>(
    `/api/v1/leagues/${encodeURIComponent(leagueId)}/fixtures`,
    {}
  )
  return { ...envelope, data: envelope.data ?? [] }
}

/**
 * Tìm đội theo tên, hoặc liệt kê đội của một giải.
 *
 * Phải có ít nhất một trong hai — service trả 400 nếu không, để không tiêu mất
 * một lượt hạn mức cho một request mà nhà cung cấp chắc chắn từ chối. Chặn
 * luôn ở đây để một ô tìm kiếm còn trống không kịp gửi request nào đi.
 */
export async function fetchTeams(search: string, leagueId = ''): Promise<FootballTeam[]> {
  const trimmed = search.trim()
  if (trimmed === '' && leagueId === '') {
    return []
  }
  const envelope = await getEnvelope<FootballTeam[] | null>('/api/v1/teams', {
    search: trimmed,
    league: leagueId
  })
  return envelope.data ?? []
}

/** Lịch cả mùa của một đội. */
export async function fetchTeamFixtures(
  teamId: string,
  leagueId = ''
): Promise<FootballEnvelope<FootballMatch[]>> {
  const envelope = await getEnvelope<FootballMatch[] | null>(
    `/api/v1/teams/${encodeURIComponent(teamId)}/fixtures`,
    { league: leagueId }
  )
  return { ...envelope, data: envelope.data ?? [] }
}

export interface FootballPlayerStat {
  teamName: string
  teamLogo: string
  leagueName: string
  season: number | null
  position: string
  appearances: number
  minutesPlayed: number
  rating: number | null
  goals: number
  assists: number
  yellowCards: number
  redCards: number
}

export interface FootballPlayer {
  id: string
  name: string
  age: number | null
  nationality: string
  height: string
  weight: string
  photo: string
  injured: boolean
  statistics: FootballPlayerStat[]
}

/**
 * Tìm cầu thủ theo tên.
 *
 * Dưới 3 ký tự thì KHÔNG gọi máy chủ — chính nhà cung cấp đặt ngưỡng đó, nên
 * gọi lên chỉ nhận về lỗi 400 mà vẫn tốn một vòng mạng. Trả mảng rỗng để giao
 * diện hiểu là "chưa gõ đủ" chứ không phải "không có ai".
 */
export async function fetchPlayers(search: string): Promise<FootballPlayer[]> {
  if (search.trim().length < 3) {
    return []
  }
  const envelope = await getEnvelope<FootballPlayer[] | null>('/api/v1/players', {
    search: search.trim()
  })
  return envelope.data ?? []
}

/**
 * Hồ sơ đầy đủ của một cầu thủ trong một mùa.
 *
 * Trả `null` khi service báo 404 thay vì ném lỗi: "cầu thủ này không có số
 * liệu ở mùa 2022" là một câu trả lời hợp lệ, không phải một sự cố — và với
 * gói miễn phí thì đó là chuyện thường gặp, vì không phải mùa nào cũng mở.
 */
export async function fetchPlayer(playerId: string): Promise<FootballPlayer | null> {
  const url = new URL(`/api/v1/players/${encodeURIComponent(playerId)}`, FOOTBALL_API_BASE)

  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })

  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }

  const envelope = (await response.json()) as FootballEnvelope<FootballPlayer | null>
  return envelope.data
}

export interface FootballStatus {
  used: number
  budget: number
  remaining: number
  sampleOnly: boolean
}

/**
 * Dán khoá API-Football vào service.
 *
 * Service KIỂM TRA khoá trước khi nhận, nên lỗi trả về ở đây là câu từ chối
 * thật của nhà cung cấp — ném nguyên văn ra để giao diện hiện lại. "You are
 * not subscribed to this API" nói chính xác phải bấm gì tiếp theo; một câu
 * "khoá không hợp lệ" tự chế thì không.
 */
export async function saveApiKey(key: string): Promise<void> {
  const url = new URL('/api/v1/config/api-key', FOOTBALL_API_BASE)
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ key: key.trim() }),
    // Kiểm tra khoá là một vòng đi ra ngoài Internet, chậm hơn hẳn mọi lời gọi
    // khác trong tệp này — cho nó gấp đôi thời gian chờ.
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS * 2)
  })

  if (response.ok) {
    return
  }

  const body = (await response.json().catch(() => null)) as {
    error?: { message?: string }
  } | null
  throw new Error(body?.error?.message ?? `${response.status} ${response.statusText}`)
}

export async function fetchStatus(): Promise<FootballStatus> {
  const url = new URL('/api/v1/status', FOOTBALL_API_BASE)
  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return (await response.json()) as FootballStatus
}
