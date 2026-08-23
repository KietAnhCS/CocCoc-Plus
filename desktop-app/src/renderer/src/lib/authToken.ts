/**
 * Nơi giữ token phiên — <b>một</b> chỗ duy nhất trong cả ứng dụng.
 *
 * VÌ SAO CẦN MODULE NHỎ NÀY. Ba nơi cần biết token: `sessionStore` (ghi nó),
 * `telemetry.ts` (gắn danh tính vào sự kiện), `adminApi.ts` (gọi endpoint quản
 * trị). Nếu hai tệp `lib/` phải `import` từ `store/` thì tầng dưới quay lên
 * phụ thuộc tầng trên — vòng phụ thuộc chỉ chờ ngày xuất hiện. Một module lá
 * nhỏ mà cả ba cùng phụ thuộc vào thì giữ được hướng phụ thuộc một chiều.
 *
 * VÌ SAO TOKEN ĐƯỢC LƯU BỀN, TRONG KHI KHOÁ QUẢN TRỊ THÌ KHÔNG. Đây là điểm
 * dễ tưởng mâu thuẫn, nên nói rõ — hai thứ có mức nguy hiểm khác hẳn nhau:
 *
 *   Khoá quản trị (X-API-Key)      Token phiên
 *   ───────────────────────────    ─────────────────────────────
 *   không bao giờ hết hạn          hết hạn sau 12 giờ
 *   không thu hồi được (phải       thu hồi được tức thì bằng
 *   đổi cấu hình + khởi động lại)  một lần bấm "đăng xuất"
 *   luôn là quyền ADMIN đầy đủ     mang đúng vai trò của tài khoản
 *   dùng chung cho mọi người       gắn với một người cụ thể
 *
 * Một bí mật vĩnh viễn, không thu hồi được, quyền cao nhất thì không đáng nằm
 * lại trên đĩa để đổi lấy việc đỡ gõ. Một token hết hạn và huỷ được thì đáng —
 * và cái giá của việc không lưu nó là bắt người dùng đăng nhập lại mỗi lần mở
 * ứng dụng, điều không ai chịu được ở một trình duyệt.
 *
 * Rủi ro còn lại phải nói thẳng: `localStorage` đọc được bởi mọi mã chạy trong
 * renderer, nên một lỗ hổng XSS sẽ lấy được token. Thứ chặn điều đó ở đây là
 * CSP nghiêm ngặt trong `index.html` cộng với việc renderer không bao giờ nạp
 * mã từ xa — chứ không phải bản thân `localStorage`.
 */

const STORAGE_KEY = 'vnsearch-session-token'

/**
 * Bản sao trong bộ nhớ để không phải chạm `localStorage` ở mỗi request.
 *
 * `undefined` = chưa đọc lần nào; `null` = đã đọc và không có.
 */
let cached: string | null | undefined

function storage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    // Một số môi trường (test chạy trên Node, cửa sổ bị hạn chế) không có.
    return null
  }
}

export function getAuthToken(): string | null {
  if (cached === undefined) {
    cached = storage()?.getItem(STORAGE_KEY) ?? null
  }
  return cached
}

/** `null` để xoá. Ghi cả bộ nhớ lẫn đĩa để hai nơi không bao giờ lệch nhau. */
export function setAuthToken(token: string | null): void {
  cached = token
  const store = storage()
  if (!store) {
    return
  }
  if (token) {
    store.setItem(STORAGE_KEY, token)
  } else {
    store.removeItem(STORAGE_KEY)
  }
}

/**
 * Header xác thực cho một request, hoặc đối tượng rỗng khi chưa đăng nhập.
 *
 * Trả về đối tượng rỗng chứ không phải `undefined` để nơi gọi luôn trải được
 * (`...authHeader()`) mà không cần rẽ nhánh.
 */
export function authHeader(): Record<string, string> {
  const token = getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}
