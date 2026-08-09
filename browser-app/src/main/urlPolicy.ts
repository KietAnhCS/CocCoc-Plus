/**
 * Chính sách điều hướng — quyết định URL nào được phép mở trong một tab.
 *
 * VÌ SAO TÁCH RA MỘT TỆP RIÊNG, KHÔNG NẰM TRONG `tabManager`.
 * Hai lý do, và cả hai đều quan trọng:
 *
 *   1. Đây là một RANH GIỚI BẢO MẬT, nên nó phải kiểm thử được. `tabManager`
 *      import `electron`, mà `electron` không nạp được ngoài tiến trình
 *      Electron — nghĩa là mọi thứ nằm trong đó đều nằm ngoài tầm với của bộ
 *      test. Tệp này không import gì cả, nên `urlPolicy.test.ts` chạy được
 *      trong Vitest thuần.
 *   2. Nó phải là NGUỒN SỰ THẬT DUY NHẤT. Thanh địa chỉ ở renderer cũng có
 *      một phép chuẩn hoá URL, nhưng renderer KHÔNG phải ranh giới bảo mật:
 *      nó chỉ là giao diện, và mọi thứ nó gửi qua IPC đều phải bị coi là
 *      không đáng tin. Phép kiểm tra thật buộc phải nằm ở tiến trình chính.
 *
 * LỖ HỔNG TỆP NÀY VÁ. Bản trước dùng đúng một dòng:
 *
 *     const target = /^[a-z]+:\/\//i.test(url) ? url : `https://${url}`
 *
 * Nó chỉ hỏi "có scheme không", không hỏi "scheme NÀO". Hệ quả là mọi scheme
 * có `://` đều đi thẳng vào `loadURL`:
 *
 *     file:///C:/Users/<tên>/.ssh/id_rsa    -> mở tệp cục bộ trong tab
 *     file://<máy-chủ-mạng>/ổ-chia-sẻ/      -> chạm vào SMB nội bộ
 *
 * Và một trang web bất kỳ kích hoạt được đường đó: `setWindowOpenHandler` gọi
 * `createTab(url)` với URL do TRANG ĐÍCH chỉ định, nên `window.open('file:///…')`
 * là đủ. Đây cùng một loại lỗi với SSRF ở phía backend — tin vào một chuỗi do
 * bên ngoài cung cấp — và được vá bằng cùng một cách: danh sách CHO PHÉP, chứ
 * không phải danh sách chặn.
 */

/** Trang chủ nội bộ. Không phải URL thật — nó được vẽ bởi chính renderer. */
export const HOME_URL = 'vnsearch://home'

/**
 * Danh sách CHO PHÉP. Chỉ hai giao thức mà một trình duyệt web cần đến.
 *
 * Danh sách cho phép chứ không phải danh sách chặn: một danh sách chặn phải
 * đoán trước mọi scheme nguy hiểm (`file:`, `data:`, `blob:`, `javascript:`,
 * `chrome:`, `devtools:`, `ms-msdt:`…) và sẽ luôn thiếu cái tiếp theo. Danh
 * sách cho phép thì mặc định từ chối, nên cái chưa nghĩ tới cũng bị chặn.
 */
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:'])

/** URL tuyệt đối đầy đủ: có scheme VÀ có `//`. */
const ABSOLUTE_URL = /^[a-z][a-z0-9+.-]*:\/\//i

/**
 * Scheme KHÔNG có dấu gạch chéo: `javascript:`, `data:`, `mailto:`.
 *
 * Phần `(?!\d)` là chỗ tinh tế và nó có lý do cụ thể: `localhost:8080/api`
 * cũng khớp mẫu "chữ rồi dấu hai chấm", nhưng nó là <b>host:port</b> chứ
 * không phải scheme. Không có lookahead này thì người dùng gõ `localhost:8080`
 * sẽ bị từ chối — một phép sửa bảo mật làm hỏng ca dùng thường ngày nhất của
 * chính người viết ra nó.
 */
const SCHEME_WITHOUT_SLASHES = /^[a-z][a-z0-9+.-]*:(?!\d)/i

/**
 * Chuẩn hoá một chuỗi người dùng gõ (hoặc một trang web yêu cầu) thành URL mở
 * được, hoặc `null` nếu nó không được phép.
 *
 * <p>Trả `null` thay vì ném ngoại lệ: bên gọi là một trình xử lý IPC, và một
 * ngoại lệ ở đó chỉ thành một promise bị từ chối mà giao diện không hiển thị.
 * Một giá trị trả về buộc bên gọi phải xử lý tường minh.
 *
 * @returns URL đã chuẩn hoá, {@link HOME_URL}, hoặc `null` nếu bị từ chối
 */
export function resolveNavigable(input: string | null | undefined): string | null {
  if (typeof input !== 'string') {
    return null
  }
  const trimmed = input.trim()
  if (!trimmed) {
    return null
  }
  // Trang chủ nội bộ không đi qua mạng nên không cần — và không qua nổi —
  // phép kiểm tra giao thức bên dưới.
  if (trimmed === HOME_URL) {
    return HOME_URL
  }

  // Scheme trần (`javascript:`, `data:`, `mailto:`) bị loại NGAY. Nếu để nó
  // rơi xuống nhánh "thêm https://" bên dưới thì `javascript:alert(1)` biến
  // thành `https://javascript:alert(1)` — một chuỗi vô nghĩa nhưng vẫn phải
  // qua tay `new URL`, và dựa vào việc phép phân giải đó tình cờ thất bại là
  // dựa vào may mắn chứ không phải vào một quy tắc.
  if (!ABSOLUTE_URL.test(trimmed) && SCHEME_WITHOUT_SLASHES.test(trimmed)) {
    return null
  }

  const candidate = ABSOLUTE_URL.test(trimmed) ? trimmed : `https://${trimmed}`

  let parsed: URL
  try {
    parsed = new URL(candidate)
  } catch {
    return null
  }

  if (!ALLOWED_PROTOCOLS.has(parsed.protocol)) {
    return null
  }
  // `http:///đường-dẫn` phân giải được nhưng không có host — không trỏ tới đâu cả.
  if (!parsed.hostname) {
    return null
  }
  return parsed.toString()
}

/** Giới hạn phóng to, khớp với dải mà Chromium chấp nhận. */
export const MIN_ZOOM = 0.25
export const MAX_ZOOM = 5

/**
 * Ép hệ số phóng to về dải hợp lệ.
 *
 * <p>Giá trị này đến từ renderer qua IPC, nên nó có thể là `NaN`, `0`, số âm
 * hay `Infinity`. Chromium xử lý các giá trị đó theo cách không xác định —
 * `0` làm nội dung biến mất hẳn mà không có cách nào phục hồi bằng giao diện.
 */
export function clampZoomFactor(factor: unknown): number {
  const value = typeof factor === 'number' ? factor : Number.NaN
  if (!Number.isFinite(value)) {
    return 1
  }
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, value))
}
