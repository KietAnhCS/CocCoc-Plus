import { API_BASE } from './searchApi'

/**
 * Tầng gọi các endpoint QUẢN TRỊ (`/api/admin/**`).
 *
 * TÁCH RIÊNG KHỎI `searchApi.ts` CÓ CHỦ Ý. Hai tệp gọi cùng một máy chủ nhưng ở
 * hai mức quyền khác nhau: `searchApi` không bao giờ được phép gửi khoá quản
 * trị, còn tệp này thì luôn phải gửi. Trộn chung thì tham số `apiKey` trở thành
 * một tuỳ chọn có thể quên, và một lần quên nghĩa là 401 lúc chạy; tách ra thì
 * trình kiểm kiểu bắt buộc mọi lời gọi ở đây phải có khoá.
 *
 * KHOÁ ĐI TRONG HEADER, KHÔNG PHẢI TRONG URL. Tham số truy vấn nằm trong lịch
 * sử, trong log máy chủ và trong `Referer` gửi đi nơi khác; header thì không.
 */

const REQUEST_TIMEOUT_MS = 8000

export const API_KEY_HEADER = 'X-API-Key'

/**
 * Hai cách chứng minh mình có quyền quản trị.
 *
 * Kiểu hợp (union) chứ không phải hai tham số tuỳ chọn: với hai tham số tuỳ
 * chọn thì trạng thái "không có cái nào" và "có cả hai" đều biểu diễn được,
 * và cả hai đều vô nghĩa. Kiểu hợp làm chúng không tồn tại.
 */
export type AdminCredential = { kind: 'session'; token: string } | { kind: 'apiKey'; key: string }

function headersFor(credential: AdminCredential): Record<string, string> {
  return credential.kind === 'session'
    ? { Authorization: `Bearer ${credential.token}` }
    : { [API_KEY_HEADER]: credential.key }
}

/** Số dòng mỗi bảng xếp hạng. Máy chủ chặn trên ở 50. */
const TOP_ROWS = 10

export interface Counted {
  label: string
  count: number
}

export interface LinkCount {
  url: string
  host: string
  count: number
  /** Thứ hạng TRUNG BÌNH lúc được bấm — thước đo chất lượng xếp hạng. */
  position: number
}

export interface HourPoint {
  hour: string
  visitors: number
  searches: number
  clicks: number
}

export interface LatencyBucket {
  label: string
  count: number
}

export interface TrafficDto {
  visitors: number
  /** Số phiên gắn với một tài khoản. Phần chênh so với `visitors` là ẩn danh. */
  signedInVisitors: number
  activeVisitors: number
  activeWindowMinutes: number
  searches: number
  clicks: number
  clickThroughRate: number
  avgLatencyMs: number
  zeroResultSearches: number
  zeroResultRate: number
  avgSessionMinutes: number
  hourly: HourPoint[]
  latency: LatencyBucket[]
  topQueries: Counted[]
  topLinks: LinkCount[]
  topHosts: Counted[]
  /**
   * Tài khoản tìm nhiều nhất — CHỈ tên và số lượt.
   *
   * Cố ý không kèm truy vấn của từng người: bảng điều khiển trả lời "ai dùng
   * nhiều", không trả lời "người này tìm gì". Ranh giới đó là một lựa chọn về
   * quyền riêng tư, không phải một thiếu sót của API.
   */
  topUsers: Counted[]
  /** `true` khi một bảng thống kê đã chạm trần bộ nhớ và số liệu bị thiếu. */
  truncated: boolean
}

export interface DayCount {
  date: string
  count: number
}

export interface CrawlDto {
  documents: number
  distinctHosts: number
  totalOutlinks: number
  distinctLinkTargets: number
  avgOutlinks: number
  danglingDocuments: number
  /** Độ dài tài liệu trung bình tính bằng TOKEN — đơn vị mà BM25 chuẩn hoá theo. */
  avgDocLength: number
  medianDocLength: number
  oldestCrawledAt: string | null
  newestCrawledAt: string | null
  languages: Counted[]
  topHosts: Counted[]
  crawledPerDay: DayCount[]
}

export interface IndexDto {
  documents: number
  terms: number
  sizeBytes: number
  cacheHitRate: number
  scorer: string
  bloomFilterBits: number
}

export interface AccountStatsDto {
  total: number
  admins: number
  disabled: number
  /** Số phiên ĐĂNG NHẬP còn hiệu lực — khác `traffic.signedInVisitors`. */
  activeSessions: number
}

export interface DashboardDto {
  generatedAt: string
  traffic: TrafficDto
  crawl: CrawlDto
  index: IndexDto
  accounts: AccountStatsDto
}

/**
 * Máy chủ đã TỪ CHỐI quyền — khoá sai hoặc thiếu.
 *
 * Là một lớp lỗi riêng chứ không phải một chuỗi thông báo, vì hai loại hỏng
 * dẫn tới hai xử lý hoàn toàn khác nhau: 401 phải đưa người dùng về màn hình
 * nhập khoá và thu hồi vai trò hiện có, còn một lỗi mạng thì chỉ nên hiện
 * "không kết nối được" và GIỮ NGUYÊN phiên đăng nhập — đăng xuất người ta chỉ
 * vì backend vừa khởi động lại là một hành vi khó chịu và không có lý do.
 */
export class AdminAuthError extends Error {
  constructor(message = 'Khoá quản trị không đúng hoặc đã bị thu hồi.') {
    super(message)
    this.name = 'AdminAuthError'
  }
}

/**
 * Máy chủ ĐÃ trả lời, nhưng bằng một mã lỗi khác.
 *
 * Cùng lý do với `ServerError` trong `authApi`: gộp mọi thứ không phải 401 vào
 * câu "máy chủ có thể đang tắt" là **nói sai sự thật** khi máy chủ vừa trả về
 * 429 — nó có chạy, nó vừa trả lời. Người đọc sẽ đi khởi động lại backend cho
 * một việc không liên quan.
 */
export class AdminServerError extends Error {
  constructor(
    readonly status: number,
    message: string
  ) {
    super(message)
    this.name = 'AdminServerError'
  }
}

function explain(status: number, statusText: string): string {
  if (status === 429) {
    return 'Bảng điều khiển gửi quá nhiều yêu cầu (giới hạn 120/phút). Tắt "Tự làm mới" hoặc chờ một phút.'
  }
  if (status >= 500) {
    return `Máy chủ gặp lỗi nội bộ (${status}). Xem log backend để biết chi tiết.`
  }
  return `Máy chủ từ chối yêu cầu (${status} ${statusText}).`
}

/**
 * Tải toàn bộ số liệu cho bảng điều khiển.
 *
 * @param signal huỷ khi bảng đóng hoặc khi một lần làm mới mới bắt đầu — nếu
 *               không, hai phản hồi về không đúng thứ tự sẽ khiến bảng nhấp
 *               nháy giữa số liệu cũ và mới
 */
export async function fetchDashboard(
  credential: AdminCredential,
  signal?: AbortSignal
): Promise<DashboardDto> {
  const url = new URL('/api/admin/analytics', API_BASE)
  url.searchParams.set('top', String(TOP_ROWS))

  const response = await fetch(url, {
    headers: { Accept: 'application/json', ...headersFor(credential) },
    signal: signal ?? AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })

  if (response.status === 401 || response.status === 403) {
    // Gộp 401 và 403 làm một ở ĐÂY là đúng, dù chúng khác nhau ở tầng HTTP:
    // với bảng điều khiển, "tôi không biết anh là ai" và "tôi biết, và anh
    // không đủ quyền" đều dẫn tới cùng một việc — không vẽ số liệu, quay về
    // cửa xác thực.
    throw new AdminAuthError()
  }
  if (!response.ok) {
    throw new AdminServerError(response.status, explain(response.status, response.statusText))
  }
  return (await response.json()) as DashboardDto
}

/** Xoá sạch số liệu lưu lượng. Không đụng tới chỉ mục hay corpus. */
export async function resetTraffic(credential: AdminCredential): Promise<void> {
  const response = await fetch(new URL('/api/admin/analytics/reset', API_BASE), {
    method: 'POST',
    headers: headersFor(credential),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })
  if (response.status === 401 || response.status === 403) {
    throw new AdminAuthError()
  }
  if (!response.ok) {
    throw new AdminServerError(response.status, explain(response.status, response.statusText))
  }
}

export interface ManagedAccount {
  username: string
  role: 'USER' | 'ADMIN'
  enabled: boolean
  createdAt: string
  lastLoginAt: string | null
}

/** Danh sách tài khoản. Máy chủ không bao giờ kèm hash mật khẩu trong phản hồi. */
export async function fetchAccounts(credential: AdminCredential): Promise<ManagedAccount[]> {
  const response = await fetch(new URL('/api/admin/users', API_BASE), {
    headers: { Accept: 'application/json', ...headersFor(credential) },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })
  if (response.status === 401 || response.status === 403) {
    throw new AdminAuthError()
  }
  if (!response.ok) {
    throw new AdminServerError(response.status, explain(response.status, response.statusText))
  }
  return (await response.json()) as ManagedAccount[]
}

/**
 * Đổi vai trò một tài khoản.
 *
 * Máy chủ đóng mọi phiên của người bị đổi, nên họ phải đăng nhập lại. Đó là
 * chủ ý: một phiên đang mở mang vai trò CŨ, và để nó sống tiếp nghĩa là quyền
 * bị thu hồi trên giấy nhưng còn hiệu lực thêm nhiều giờ.
 */
export async function changeRole(
  credential: AdminCredential,
  username: string,
  role: 'USER' | 'ADMIN'
): Promise<ManagedAccount> {
  const response = await fetch(
    new URL(`/api/admin/users/${encodeURIComponent(username)}/role`, API_BASE),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headersFor(credential) },
      body: JSON.stringify({ role }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
    }
  )
  if (response.status === 401 || response.status === 403) {
    throw new AdminAuthError()
  }
  if (response.status === 400) {
    // Máy chủ chặn việc tự hạ quyền chính mình — nếu không, người quản trị
    // cuối cùng có thể khoá cả hệ thống khỏi tay chính họ.
    throw new Error('Không thể tự hạ vai trò của chính tài khoản đang đăng nhập.')
  }
  if (!response.ok) {
    throw new AdminServerError(response.status, explain(response.status, response.statusText))
  }
  return (await response.json()) as ManagedAccount
}

/**
 * Xoá hẳn một tài khoản.
 *
 * Khác vô hiệu hoá: không hồi lại được, và tên được giải phóng cho người khác
 * đăng ký. Máy chủ chặn tự xoá chính mình (400) và trả 404 nếu tài khoản đã
 * biến mất — gọi hai lần không gây thêm hậu quả nào.
 */
export async function deleteAccount(credential: AdminCredential, username: string): Promise<void> {
  const response = await fetch(
    new URL(`/api/admin/users/${encodeURIComponent(username)}`, API_BASE),
    {
      method: 'DELETE',
      headers: headersFor(credential),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
    }
  )
  if (response.status === 401 || response.status === 403) {
    throw new AdminAuthError()
  }
  if (response.status === 400) {
    throw new Error('Không thể tự xoá tài khoản đang đăng nhập.')
  }
  if (response.status === 404) {
    throw new Error('Tài khoản này không còn tồn tại.')
  }
  if (!response.ok) {
    throw new AdminServerError(response.status, explain(response.status, response.statusText))
  }
}
