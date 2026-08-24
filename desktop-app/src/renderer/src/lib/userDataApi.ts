import { API_BASE } from './searchApi'
import { authHeader, ensureFreshToken, getAuthToken } from './authToken'

/**
 * Cổng ra ba service dữ liệu cá nhân: lịch sử, tải xuống, tuỳ chọn.
 *
 * <p>Gộp vào một tệp vì cả ba đi qua đúng một quy trình — gia hạn token, gắn
 * header, dịch lỗi — và ba bản sao của quy trình đó sẽ lệch nhau. Riêng phần
 * kiểu dữ liệu thì tách rõ theo từng service ở dưới.
 *
 * <h2>Mọi request đều đi qua {@link request}</h2>
 *
 * <p>Không endpoint nào tự gọi `fetch`. Đó là cách duy nhất bảo đảm bốn việc
 * xảy ra ở MỌI lượt gọi:
 *
 * <ol>
 *   <li>gia hạn access token nếu sắp hết hạn (im lặng, người dùng không thấy);</li>
 *   <li>gắn header {@code Authorization};</li>
 *   <li>bỏ cuộc sau {@link TIMEOUT_MS} thay vì treo mãi;</li>
 *   <li>coi 401 là "chưa đăng nhập" chứ không phải một lỗi cần hiện lên.</li>
 * </ol>
 *
 * <h2>Vì sao ĐA SỐ hàm ở đây nuốt lỗi</h2>
 *
 * <p>Ghi một lượt ghé thăm hay một truy vấn tìm kiếm là việc chạy NỀN: người
 * dùng không chờ nó, và họ cũng không làm gì được nếu nó hỏng. Hiện một thông
 * báo lỗi vì không đồng bộ được lịch sử là làm phiền vì một chuyện không liên
 * quan tới thứ họ đang làm.
 *
 * <p>Ngược lại, những hàm mà người dùng ĐANG CHỜ kết quả — đọc danh sách lịch
 * sử, xoá dữ liệu — thì ném ra ngoài, vì màn hình phải nói được là nó trống
 * hay là nó hỏng.
 */

const TIMEOUT_MS = 8000

/** Ứng dụng đang chạy mà không có backend — dừng thử để đỡ tốn. */
export class NotAuthenticated extends Error {
  constructor() {
    super('Chưa đăng nhập.')
    this.name = 'NotAuthenticated'
  }
}

/**
 * Gia hạn token. Định nghĩa ở đây thay vì trong `authApi` để tránh vòng nhập:
 * `authToken` không được phép phụ thuộc `authApi`, và `authApi` thì phụ thuộc
 * `authToken`.
 */
async function refreshSession(refreshToken: string): Promise<{
  token: string
  refreshToken: string
  expiresAt: string
}> {
  const response = await fetch(new URL('/api/auth/refresh', API_BASE), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
    signal: AbortSignal.timeout(TIMEOUT_MS)
  })
  if (!response.ok) {
    throw new Error(`Gia hạn thất bại: ${response.status}`)
  }
  return response.json()
}

interface RequestOptions {
  method?: string
  body?: unknown
  params?: Record<string, string | number | undefined>
  /** Header phụ, ví dụ `X-Device-Id` hoặc `If-Match`. */
  headers?: Record<string, string>
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!getAuthToken()) {
    throw new NotAuthenticated()
  }
  if (!(await ensureFreshToken(refreshSession))) {
    throw new NotAuthenticated()
  }

  const url = new URL(path, API_BASE)
  for (const [key, value] of Object.entries(options.params ?? {})) {
    if (value !== undefined && value !== '') {
      url.searchParams.set(key, String(value))
    }
  }

  const response = await fetch(url, {
    method: options.method ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...authHeader(),
      ...options.headers
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(TIMEOUT_MS)
  })

  if (response.status === 401) {
    // Token vừa bị thu hồi ở nơi khác (đăng xuất mọi thiết bị, đổi mật khẩu,
    // tài khoản bị khoá). Dọn phía máy này để giao diện về đúng trạng thái.
    throw new NotAuthenticated()
  }
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  // 204 No Content không có thân. `response.json()` trên một thân rỗng ném lỗi
  // cú pháp — một lỗi trông như "máy chủ trả về rác" nhưng thực ra là máy chủ
  // trả lời hoàn toàn đúng.
  return response.status === 204 ? (undefined as T) : response.json()
}

/** Chạy nền: hỏng thì im lặng. Xem Javadoc đầu tệp. */
async function sendAndForget(path: string, options: RequestOptions): Promise<void> {
  try {
    await request(path, options)
  } catch {
    // Có chủ ý.
  }
}

// ===========================================================================
// LỊCH SỬ  —  history-service :8085
// ===========================================================================

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

export const historyApi = {
  /**
   * Ghi một lượt ghé thăm.
   *
   * <p>KHÔNG gọi hàm này cho thẻ ẩn danh. Chế độ ẩn danh nghĩa là <b>không gửi
   * gì</b> lên máy chủ — nhờ máy chủ "đừng lưu" thì lời hứa riêng tư phụ thuộc
   * vào việc máy chủ có làm đúng hay không, và người dùng không kiểm chứng
   * được. Nơi quyết định là `browsingHistoryStore`.
   */
  recordVisit: (url: string, title: string): Promise<void> =>
    sendAndForget('/api/history/visits', { method: 'POST', body: { url, title } }),

  recordSearch: (query: string, resultCount: number): Promise<void> =>
    sendAndForget('/api/history/searches', { method: 'POST', body: { query, resultCount } }),

  visitHistory: (params: {
    q?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }): Promise<PageDto<VisitDto>> => request('/api/history/visits', { params }),

  searchHistory: (page = 0, size = 50): Promise<PageDto<SearchQueryDto>> =>
    request('/api/history/searches', { params: { page, size } }),

  suggest: (prefix: string, limit = 8): Promise<SearchQueryDto[]> =>
    request('/api/history/searches/suggest', { params: { prefix, limit } }),

  deleteVisit: (id: string): Promise<void> =>
    request(`/api/history/visits/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /** Bỏ trống cả hai mốc = xoá sạch. Giao diện PHẢI hỏi lại trước khi gọi. */
  deleteVisitRange: (from?: string, to?: string): Promise<{ deleted: number }> =>
    request('/api/history/visits', { method: 'DELETE', params: { from, to } }),

  summary: (): Promise<{ visits: number }> => request('/api/history/summary')
}

// ===========================================================================
// TẢI XUỐNG  —  downloads-service :8086
// ===========================================================================

export type DownloadState = 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'CANCELLED' | 'INTERRUPTED'

export interface DownloadDto {
  id: string
  sourceUrl: string
  fileName: string
  mimeType: string | null
  totalBytes: number | null
  receivedBytes: number
  percent: number | null
  state: DownloadState
  onThisDevice: boolean
  startedAt: string
  finishedAt: string | null
}

/**
 * Định danh máy này, sinh MỘT LẦN rồi giữ lại.
 *
 * <p>Không phải cơ chế bảo mật và không được dùng như thế: máy khách tự sinh
 * và tự khai. Nó chỉ để máy chủ trả về `onThisDevice`, tức là để giao diện
 * biết nên hiện nút "Mở tệp" hay dòng chữ "Đã tải trên máy khác" — sai thì
 * hiện nhầm một dòng chữ, không ai đọc được dữ liệu của ai.
 */
function deviceId(): string {
  const KEY = 'vnsearch-device-id'
  try {
    let id = window.localStorage.getItem(KEY)
    if (!id) {
      id = crypto.randomUUID()
      window.localStorage.setItem(KEY, id)
    }
    return id
  } catch {
    // Không lưu được thì mỗi lần mở là một "thiết bị" mới. Hậu quả duy nhất là
    // giao diện luôn coi tệp nằm ở máy khác — chấp nhận được.
    return crypto.randomUUID()
  }
}

const deviceHeader = (): Record<string, string> => ({ 'X-Device-Id': deviceId() })

export const downloadsApi = {
  /**
   * Bắt đầu theo dõi. IDEMPOTENT: gọi lại với cùng `id` sau khi mất mạng sẽ
   * không tạo bản ghi thứ hai — `id` do tiến trình chính sinh, xem
   * `downloadManager.ts`.
   */
  start: (item: {
    id: string
    sourceUrl: string
    fileName: string
    mimeType?: string
    totalBytes?: number | null
    localPath?: string
  }): Promise<void> =>
    sendAndForget('/api/downloads', { method: 'POST', body: item, headers: deviceHeader() }),

  update: (
    id: string,
    patch: { receivedBytes?: number; state?: DownloadState; localPath?: string }
  ): Promise<void> =>
    sendAndForget(`/api/downloads/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      body: patch,
      headers: deviceHeader()
    }),

  list: (page = 0, size = 50): Promise<DownloadDto[]> =>
    request('/api/downloads', { params: { page, size }, headers: deviceHeader() }),

  remove: (id: string): Promise<void> =>
    request(`/api/downloads/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  clear: (): Promise<{ deleted: number }> => request('/api/downloads', { method: 'DELETE' })
}

// ===========================================================================
// TUỲ CHỌN  —  settings-service :8087
// ===========================================================================

export interface SettingsBlock {
  settings: Record<string, unknown>
  version: number
  updatedAt?: string
}

export const settingsApi = {
  read: (): Promise<SettingsBlock> => request('/api/settings'),

  /**
   * GỘP: chỉ gửi những khoá thay đổi.
   *
   * <p>`If-Match` mang số phiên bản đang giữ. Máy chủ trả <b>409</b> nếu thiết
   * bị khác đã sửa trong lúc đó — và kèm theo khối hiện tại, để nơi gọi gộp
   * lại rồi thử tiếp mà không cần một lượt GET nữa.
   *
   * <p>Không gửi `If-Match` nghĩa là "ghi đè, tôi biết mình đang làm gì" —
   * đúng cho lần đồng bộ đầu tiên của một máy mới, sai cho mọi lúc khác.
   */
  merge: (patch: Record<string, unknown>, version?: number): Promise<SettingsBlock> =>
    request('/api/settings', {
      method: 'PATCH',
      body: patch,
      headers: version === undefined ? {} : { 'If-Match': String(version) }
    }),

  replace: (settings: Record<string, unknown>, version?: number): Promise<SettingsBlock> =>
    request('/api/settings', {
      method: 'PUT',
      body: settings,
      headers: version === undefined ? {} : { 'If-Match': String(version) }
    }),

  resetToDefaults: (): Promise<void> => request('/api/settings', { method: 'DELETE' })
}
