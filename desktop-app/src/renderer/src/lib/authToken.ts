/**
 * Nơi giữ token phiên — <b>một</b> chỗ duy nhất trong cả ứng dụng.
 *
 * VÌ SAO CẦN MODULE NHỎ NÀY. Nhiều nơi cần biết token: `sessionStore` (ghi
 * nó), `telemetry.ts` (gắn danh tính vào sự kiện), `adminApi.ts`, và ba client
 * mới (`historyApi`, `downloadsApi`, `settingsApi`). Nếu các tệp `lib/` phải
 * `import` từ `store/` thì tầng dưới quay lên phụ thuộc tầng trên — vòng phụ
 * thuộc chỉ chờ ngày xuất hiện. Một module lá nhỏ mà tất cả cùng phụ thuộc vào
 * thì giữ được hướng phụ thuộc một chiều.
 *
 * ---------------------------------------------------------------------------
 * HAI TOKEN, HAI VÒNG ĐỜI — thay đổi lớn nhất so với bản trước
 * ---------------------------------------------------------------------------
 *
 * Backend đã chuyển từ token phiên mờ (sống 12 giờ) sang OAuth2/JWT:
 *
 *   access token    JWT, sống 15 PHÚT, đi kèm mọi request
 *   refresh token   chuỗi mờ, sống 30 ngày, CHỈ đi tới /api/auth/refresh
 *
 * Người dùng không được thấy sự khác biệt: 15 phút một lần, `ensureFreshToken`
 * âm thầm đổi refresh token lấy cặp token mới.
 *
 * VÌ SAO CHIA LÀM HAI. Access token tự chứng thực nên không xoá được ở máy
 * chủ; thời gian sống của nó chính là cửa sổ thiệt hại tối đa khi bị đánh cắp.
 * 15 phút đủ ngắn để giới hạn thiệt hại, và refresh token gánh phần "đừng bắt
 * tôi gõ mật khẩu lại".
 *
 * ---------------------------------------------------------------------------
 * VÌ SAO TOKEN ĐƯỢC LƯU BỀN, TRONG KHI KHOÁ QUẢN TRỊ THÌ KHÔNG
 * ---------------------------------------------------------------------------
 *
 *   Khoá quản trị (X-API-Key)      Token phiên
 *   ───────────────────────────    ─────────────────────────────
 *   không bao giờ hết hạn          access 15 phút, refresh 30 ngày
 *   không thu hồi được (phải       thu hồi được tức thì: đăng xuất đưa
 *   đổi cấu hình + khởi động lại)  jti vào denylist trên Redis
 *   luôn là quyền ADMIN đầy đủ     mang đúng vai trò của tài khoản
 *   dùng chung cho mọi người       gắn với một người cụ thể
 *
 * Rủi ro còn lại phải nói thẳng: `localStorage` đọc được bởi mọi mã chạy trong
 * renderer, nên một lỗ hổng XSS sẽ lấy được cả hai token. Thứ chặn điều đó là
 * CSP nghiêm ngặt trong `index.html` cộng với việc renderer không bao giờ nạp
 * mã từ xa — chứ không phải bản thân `localStorage`.
 */

const STORAGE_KEY = 'vnsearch-session-token'
const REFRESH_KEY = 'vnsearch-refresh-token'
const EXPIRY_KEY = 'vnsearch-token-expiry'

/**
 * Gia hạn trước hạn bao lâu.
 *
 * <p>Không chờ tới đúng lúc hết hạn: đồng hồ máy khách và máy chủ luôn lệch
 * nhau vài giây, và một request khởi hành lúc còn 200 ms sẽ tới nơi khi token
 * đã chết. 60 giây là khoảng đệm rộng rãi mà vẫn chỉ tốn thêm một lần gia hạn
 * mỗi 14 phút.
 */
const REFRESH_TRUOC_MS = 60_000

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

export function getRefreshToken(): string | null {
  return storage()?.getItem(REFRESH_KEY) ?? null
}

/** Thời điểm access token hết hạn, tính bằng ms kể từ epoch. `0` = không rõ. */
export function getTokenExpiry(): number {
  const raw = storage()?.getItem(EXPIRY_KEY)
  const value = raw ? Number(raw) : 0
  return Number.isFinite(value) ? value : 0
}

/**
 * Ghi cả cặp token.
 *
 * <p>Truyền `null` cho `token` để xoá SẠCH cả ba khoá — dùng khi đăng xuất.
 * Xoá từng khoá một là chỗ dễ sót: bỏ lại refresh token nghĩa là lần khởi động
 * sau ứng dụng tự đăng nhập lại đúng cái tài khoản người dùng vừa thoát.
 */
export function setAuthToken(
  token: string | null,
  refreshToken?: string | null,
  expiresAt?: string | number | null
): void {
  cached = token
  const store = storage()
  if (!store) {
    return
  }
  if (!token) {
    store.removeItem(STORAGE_KEY)
    store.removeItem(REFRESH_KEY)
    store.removeItem(EXPIRY_KEY)
    return
  }

  store.setItem(STORAGE_KEY, token)

  // `undefined` = "không nhắc tới" nên giữ nguyên giá trị cũ; `null` = "xoá".
  // Phân biệt hai thứ này cho phép cập nhật riêng access token mà không đụng
  // tới refresh token — đúng thứ `ensureFreshToken` cần khi máy chủ chỉ trả
  // về access token mới.
  if (refreshToken !== undefined) {
    if (refreshToken) {
      store.setItem(REFRESH_KEY, refreshToken)
    } else {
      store.removeItem(REFRESH_KEY)
    }
  }
  if (expiresAt !== undefined && expiresAt !== null) {
    const millis = typeof expiresAt === 'number' ? expiresAt : Date.parse(expiresAt)
    if (Number.isFinite(millis)) {
      store.setItem(EXPIRY_KEY, String(millis))
    }
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

/**
 * Lời hứa gia hạn ĐANG CHẠY, nếu có.
 *
 * <p>Đây là phần quan trọng nhất của module này. Khi access token hết hạn,
 * thường có nhiều request cùng phát hiện điều đó trong cùng một khoảnh khắc —
 * trang lịch sử, thanh tải xuống, và một truy vấn tìm kiếm chẳng hạn. Không có
 * biến này thì cả ba cùng gọi `/api/auth/refresh` với CÙNG một refresh token.
 *
 * <p>Và hậu quả không chỉ là ba lượt gọi thừa: backend <b>xoay vòng</b> refresh
 * token, nên lượt đầu tiêu thụ nó, hai lượt sau bị coi là DÙNG LẠI — và máy
 * chủ phản ứng bằng cách huỷ toàn bộ chuỗi token, tức là đá người dùng ra
 * ngoài. Một cơ chế chống đánh cắp lại tự bắn vào chân mình.
 *
 * <p>Gom về một lời hứa dùng chung khiến ba nơi cùng chờ đúng một lượt gọi.
 */
let dangGiaHan: Promise<boolean> | null = null

/**
 * Bảo đảm access token còn hạn, gia hạn nếu sắp hết.
 *
 * <p>Gọi TRƯỚC mỗi request cần xác thực. Trả về `false` khi không còn phiên
 * nào dùng được — nơi gọi nên coi đó là "chưa đăng nhập".
 *
 * @param refreshFn hàm thật sự gọi mạng, truyền vào để module này không phải
 *                  phụ thuộc `authApi` (và tạo vòng nhập, vì `authApi` phụ
 *                  thuộc ngược lại vào đây)
 */
export async function ensureFreshToken(
  refreshFn: (refreshToken: string) => Promise<{
    token: string
    refreshToken: string
    expiresAt: string
  }>
): Promise<boolean> {
  if (!getAuthToken()) {
    return false
  }
  const expiry = getTokenExpiry()
  // expiry = 0 nghĩa là không rõ hạn (token lưu từ phiên bản cũ). Coi như còn
  // hạn: gia hạn bừa sẽ tiêu một refresh token mà chưa cần.
  if (expiry === 0 || Date.now() < expiry - REFRESH_TRUOC_MS) {
    return true
  }

  if (dangGiaHan) {
    return dangGiaHan
  }

  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    // Có access token nhưng không có refresh token: phiên này không gia hạn
    // được nữa. Xoá sạch để giao diện hiện màn hình đăng nhập ngay, thay vì
    // để người dùng gặp 401 ở thao tác tiếp theo mà không hiểu vì sao.
    setAuthToken(null)
    return false
  }

  dangGiaHan = refreshFn(refreshToken)
    .then((response) => {
      setAuthToken(response.token, response.refreshToken, response.expiresAt)
      return true
    })
    .catch(() => {
      // Gia hạn thất bại = refresh token hết hạn, bị thu hồi, hoặc đã bị dùng
      // ở nơi khác. Cả ba đều dẫn tới cùng một kết luận: phiên đã chết.
      setAuthToken(null)
      return false
    })
    .finally(() => {
      dangGiaHan = null
    })

  return dangGiaHan
}
