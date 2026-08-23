import { API_BASE } from './searchApi'
import { authHeader } from './authToken'

/**
 * Cổng ra `/api/auth/**` — đăng ký, đăng nhập, đăng xuất, "tôi là ai".
 *
 * MỌI HÀM Ở ĐÂY ĐỀU CÓ THỂ NÉM. Khác `telemetry.ts` (bắn rồi quên), đây là
 * những thao tác mà người dùng đang chờ kết quả: họ vừa bấm "Đăng nhập" và
 * cần biết thành công hay không, vì sao không. Nuốt lỗi ở tầng này là cách
 * chắc chắn nhất để tạo ra một nút bấm không phản ứng gì.
 */

const REQUEST_TIMEOUT_MS = 10_000

export type RoleName = 'USER' | 'ADMIN'

export interface AccountDto {
  username: string
  role: RoleName
  enabled: boolean
  createdAt: string
  lastLoginAt: string | null
}

export interface LoginResponse {
  token: string
  expiresAt: string
  user: AccountDto
}

/** Máy chủ từ chối: sai thông tin đăng nhập, hoặc phiên đã hết hạn. */
export class AuthError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthError'
  }
}

/**
 * Máy chủ ĐÃ trả lời, nhưng bằng một mã lỗi không phải "sai thông tin đăng nhập".
 *
 * VÌ SAO CẦN LỚP LỖI RIÊNG NÀY. Bản đầu chỉ có `AuthError`, và mọi thứ khác bị
 * tầng store gán chung một câu: *"Không kết nối được tới máy chủ, kiểm tra
 * backend đang chạy"*. Câu đó **nói sai sự thật** khi máy chủ vừa trả về 429
 * hay 500 — nó có chạy, nó vừa trả lời. Người dùng đọc xong sẽ đi khởi động lại
 * backend, tức là bỏ ra mười phút cho một việc không liên quan.
 *
 * Bắt được đúng ca này khi chạy thử ứng dụng thật, không phải khi chạy test.
 */
export class ServerError extends Error {
  constructor(
    readonly status: number,
    message: string
  ) {
    super(message)
    this.name = 'ServerError'
  }
}

/** Câu giải thích cho mã trạng thái, viết cho người dùng chứ không cho lập trình viên. */
function explain(status: number, statusText: string): string {
  if (status === 429) {
    return 'Bạn gửi quá nhiều yêu cầu trong một phút. Chờ khoảng một phút rồi thử lại.'
  }
  if (status >= 500) {
    return `Máy chủ gặp lỗi nội bộ (${status}). Xem log backend để biết chi tiết.`
  }
  return `Máy chủ từ chối yêu cầu (${status} ${statusText}).`
}

/**
 * Đọc thông báo lỗi mà máy chủ gửi kèm.
 *
 * `GlobalExceptionHandler` luôn trả JSON có trường `message` cho lỗi của người
 * gọi — và thông báo đó thường là thứ duy nhất giúp người dùng sửa được ("mật
 * khẩu phải dài ít nhất 8 ký tự"). Thay nó bằng một câu chung chung của giao
 * diện là vứt đi thông tin hữu ích nhất trong cả phản hồi.
 */
async function messageOf(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string }
    return body?.message?.trim() || fallback
  } catch {
    return fallback
  }
}

async function postJson<T>(path: string, body: unknown, fallbackError: string): Promise<T> {
  const response = await fetch(new URL(path, API_BASE), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...authHeader() },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })

  if (response.status === 401 || response.status === 400) {
    throw new AuthError(await messageOf(response, fallbackError))
  }
  if (!response.ok) {
    throw new ServerError(response.status, explain(response.status, response.statusText))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export async function register(username: string, password: string): Promise<AccountDto> {
  return postJson<AccountDto>(
    '/api/auth/register',
    { username, password },
    'Không đăng ký được tài khoản.'
  )
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  return postJson<LoginResponse>(
    '/api/auth/login',
    { username, password },
    'Tên tài khoản hoặc mật khẩu không đúng.'
  )
}

/**
 * Đăng xuất.
 *
 * Không ném khi thất bại: người dùng bấm "đăng xuất" thì việc phải xảy ra là
 * xoá phiên ở MÁY NÀY, và điều đó luôn làm được. Máy chủ không phản hồi chỉ
 * nghĩa là phiên phía nó sẽ hết hạn muộn hơn — không phải lý do để giữ người
 * dùng ở trạng thái đã đăng nhập trái ý họ.
 */
export async function logout(): Promise<void> {
  try {
    await fetch(new URL('/api/auth/logout', API_BASE), {
      method: 'POST',
      headers: { ...authHeader() },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
    })
  } catch {
    /* Xem chú thích trên. */
  }
}

/**
 * Đổi mật khẩu.
 *
 * @returns số phiên KHÁC đã bị đóng — giao diện nói lại cho người dùng biết,
 *          vì đó là hệ quả họ cần thấy: các thiết bị khác vừa bị đăng xuất
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string
): Promise<number> {
  const body = await postJson<{ closedOtherSessions?: number }>(
    '/api/auth/password',
    { currentPassword, newPassword },
    'Không đổi được mật khẩu.'
  )
  return body?.closedOtherSessions ?? 0
}

/** Đăng xuất khỏi mọi thiết bị. @returns số phiên đã đóng. */
export async function logoutEverywhere(): Promise<number> {
  const body = await postJson<{ closedSessions?: number }>(
    '/api/auth/logout-all',
    {},
    'Không đăng xuất được khỏi các thiết bị khác.'
  )
  return body?.closedSessions ?? 0
}

/**
 * "Tôi là ai" theo MÁY CHỦ.
 *
 * @returns `null` khi token không còn hiệu lực — giao diện phải coi đó là chưa
 *          đăng nhập, kể cả khi `localStorage` vẫn còn một token trông hợp lệ
 */
export async function me(): Promise<AccountDto | null> {
  const response = await fetch(new URL('/api/auth/me', API_BASE), {
    headers: { Accept: 'application/json', ...authHeader() },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS)
  })
  if (response.status === 401 || response.status === 403) {
    return null
  }
  if (!response.ok) {
    throw new ServerError(response.status, explain(response.status, response.statusText))
  }
  const body = (await response.json()) as { via?: string; user?: Partial<AccountDto> }
  // Phiên do khoá API cấp không có tài khoản đứng sau. Không dựng một
  // AccountDto giả cho nó: giao diện tài khoản phải hiện "chưa đăng nhập", vì
  // đúng là chưa có ai đăng nhập cả.
  if (body.via !== 'session' || !body.user?.username) {
    return null
  }
  return {
    username: body.user.username,
    role: body.user.role === 'ADMIN' ? 'ADMIN' : 'USER',
    enabled: body.user.enabled ?? true,
    createdAt: body.user.createdAt ?? '',
    lastLoginAt: body.user.lastLoginAt ?? null
  }
}
