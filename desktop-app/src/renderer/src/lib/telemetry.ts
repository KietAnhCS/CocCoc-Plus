import { API_BASE } from './searchApi'
import { authHeader } from './authToken'

/**
 * Báo lại hành vi sử dụng cho `POST /api/events` — nguồn duy nhất của khối
 * "Lưu lượng" trên bảng điều khiển quản trị.
 *
 * VÌ SAO GIAO DIỆN PHẢI BÁO, THAY VÌ MÁY CHỦ TỰ ĐẾM. Máy chủ đếm được lượt tìm
 * kiếm (mỗi lượt là một request tới `/api/search`), nhưng KHÔNG thấy được thứ
 * đáng giá nhất: người dùng đã bấm vào kết quả nào, ở hạng bao nhiêu. Cú bấm
 * đó không đi qua máy chủ — nó mở thẳng một thẻ mới tới trang đích. Không có
 * lớp này thì cột "liên kết người dùng truy cập" và mọi phép đo chất lượng xếp
 * hạng đều không tồn tại.
 *
 * BẮN RỒI QUÊN. Mọi hàm ở đây đều không trả về gì và không bao giờ ném lỗi.
 * Ghi số liệu là việc PHỤ; một máy chủ đang tắt, một lần 429, hay một lỗi mạng
 * đều không được phép làm hỏng thao tác chính mà người dùng vừa thực hiện. Đây
 * là lý do mọi lời gọi đều kết thúc bằng `.catch(() => {})` — im lặng ở đây là
 * cố ý, không phải nuốt lỗi cho tiện.
 *
 * QUYỀN RIÊNG TƯ. Mã phiên là chuỗi ngẫu nhiên sinh tại máy này, dùng để gom
 * các hành động của một phiên lại với nhau. Nó không gắn với tài khoản, không
 * gửi kèm địa chỉ IP hay bất cứ thứ gì nhận dạng con người, và người dùng xoá
 * được bằng cách xoá dữ liệu ứng dụng.
 */

const SESSION_STORAGE_KEY = 'vnsearch-session-id'

/** Cắt ở phía GỬI, trước cả khi máy chủ cắt lần nữa. Xem `MAX_QUERY_CHARS` phía Java. */
const MAX_QUERY_CHARS = 200
const MAX_URL_CHARS = 500

/**
 * Kho lưu tối thiểu mà `readSessionId` cần.
 *
 * Nhận vào như một tham số thay vì gọi thẳng `localStorage`: môi trường test
 * chạy trên Node, nơi `localStorage` không tồn tại. Một tham số biến hàm này
 * thành hàm thuần kiểm được, thay vì một hàm chỉ chạy được trong trình duyệt.
 */
export interface SessionStore {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export type UsageEvent =
  | { type: 'visit' }
  | { type: 'search'; query: string; resultCount: number; tookMs: number }
  | { type: 'click'; url: string; position: number }

/**
 * Mã phiên, tạo mới ở lần gọi đầu tiên rồi giữ nguyên.
 *
 * Lưu trong `localStorage` chứ không phải biến trong bộ nhớ: mỗi lần khởi động
 * lại ứng dụng mà sinh mã mới thì "số người truy cập" đếm chính một người
 * thành nhiều — con số đầu tiên trên bảng điều khiển sẽ sai theo hướng luôn
 * phóng đại.
 */
export function readSessionId(store: SessionStore, newId: () => string): string {
  const existing = store.getItem(SESSION_STORAGE_KEY)
  if (existing) {
    return existing
  }
  const created = newId()
  store.setItem(SESSION_STORAGE_KEY, created)
  return created
}

/**
 * Thân JSON gửi lên `/api/events`.
 *
 * Tách riêng khỏi phần gọi mạng để kiểm được: đây là chỗ dễ lệch nhất so với
 * lược đồ phía Java, và một trường viết sai tên chỉ lộ ra dưới dạng một con số
 * bằng 0 trên bảng điều khiển vài tuần sau.
 */
export function buildEventBody(event: UsageEvent, sessionId: string): Record<string, unknown> {
  switch (event.type) {
    case 'visit':
      return { type: 'visit', sessionId }
    case 'search':
      return {
        type: 'search',
        sessionId,
        query: event.query.slice(0, MAX_QUERY_CHARS),
        resultCount: event.resultCount,
        tookMs: Math.max(0, Math.round(event.tookMs))
      }
    case 'click':
      return {
        type: 'click',
        sessionId,
        url: event.url.slice(0, MAX_URL_CHARS),
        position: event.position
      }
  }
}

function browserSessionId(): string {
  return readSessionId(window.localStorage, () => crypto.randomUUID())
}

/** Gửi một sự kiện. Không chờ, không ném, không báo lỗi ra giao diện. */
export function track(event: UsageEvent): void {
  // Truy vấn rỗng không phải một lượt tìm kiếm — gửi lên chỉ làm nhiễu bảng.
  if (event.type === 'search' && !event.query.trim()) {
    return
  }
  if (event.type === 'click' && !event.url) {
    return
  }

  try {
    fetch(new URL('/api/events', API_BASE), {
      method: 'POST',
      // Gửi kèm token khi đã đăng nhập. Máy chủ lấy danh tính từ ĐÂY, không
      // phải từ một trường trong thân request — nếu tin lời tự khai thì ai
      // cũng gán được hành vi cho người khác bằng một dòng curl.
      headers: { 'Content-Type': 'application/json', ...authHeader() },
      body: JSON.stringify(buildEventBody(event, browserSessionId())),
      // keepalive: cú bấm vào một kết quả điều hướng cả khung nội dung ngay
      // sau đó. Không có cờ này, trình duyệt được phép huỷ request đang bay khi
      // trang đổi — và mất đúng loại sự kiện quan trọng nhất của cả lớp này.
      keepalive: true
    }).catch(() => {
      /* Xem "BẮN RỒI QUÊN" ở đầu tệp. */
    })
  } catch {
    /* `crypto.randomUUID` hay `localStorage` bị chặn cũng không được làm gãy giao diện. */
  }
}
