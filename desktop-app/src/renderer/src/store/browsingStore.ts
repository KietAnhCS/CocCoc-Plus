import { create } from 'zustand'
import { historyApi, NotAuthenticated, type VisitDto } from '../lib/userDataApi'

/**
 * Lịch sử duyệt web LƯU TRÊN MÁY CHỦ — khác hẳn `historyStore`.
 *
 * <pre>
 *   historyStore   hai chồng back/forward CỦA TỪNG THẺ, trong bộ nhớ,
 *                  mất khi đóng ứng dụng, phục vụ hai nút mũi tên
 *   browsingStore  lịch sử THẬT, lưu ở history-service (MongoDB), sống 90
 *                  ngày, đồng bộ giữa các máy, phục vụ trang "Nhật ký"
 * </pre>
 *
 * <p>Hai thứ trùng tên nhưng trả lời hai câu hỏi khác nhau: "quay lại trang
 * trước" và "tuần trước tôi đọc bài gì". Gộp chúng lại từng là ý định đầu, và
 * nó sai ngay ở điểm đầu tiên — chồng back/forward phải theo THẺ, còn lịch sử
 * thì theo NGƯỜI.
 */

export type Khoang = 'gio-qua' | 'hom-nay' | 'tuan-qua' | 'tat-ca'

interface BrowsingState {
  items: VisitDto[]
  tuKhoa: string
  tong: number
  dangTai: boolean
  loi: string | null
  chuaDangNhap: boolean

  /** Nạp trang đầu, hoặc nạp lại sau khi xoá. */
  nap: (tuKhoa?: string) => Promise<void>
  /** Ghi một lượt ghé thăm. Bỏ qua nếu thẻ đang ẩn danh. */
  ghi: (url: string, title: string, incognito: boolean) => void
  deleteVisit: (id: string) => Promise<void>
  deleteVisitRange: (khoang: Khoang) => Promise<number>
}

/**
 * Những URL nội bộ KHÔNG được ghi vào lịch sử.
 *
 * <p>`vnsearch://home` là trang chủ của chính trình duyệt. Ghi nó vào lịch sử
 * thì mỗi lần mở thẻ mới lại thêm một dòng, và sau một buổi làm việc thì lịch
 * sử toàn là trang chủ — che mất đúng những trang mà người dùng muốn tìm lại.
 */
const KHONG_GHI = ['vnsearch://', 'about:', 'chrome://', 'devtools://']

function boQua(url: string): boolean {
  return !url || KHONG_GHI.some((prefix) => url.startsWith(prefix))
}

/** Mốc bắt đầu của một khoảng, dạng ISO. `undefined` = từ đầu. */
function mocBatDau(khoang: Khoang): string | undefined {
  const bayGio = new Date()
  switch (khoang) {
    case 'gio-qua':
      return new Date(bayGio.getTime() - 3600_000).toISOString()
    case 'hom-nay': {
      const dauNgay = new Date(bayGio)
      dauNgay.setHours(0, 0, 0, 0)
      return dauNgay.toISOString()
    }
    case 'tuan-qua':
      return new Date(bayGio.getTime() - 7 * 86_400_000).toISOString()
    case 'tat-ca':
      return undefined
  }
}

/**
 * Chống ghi trùng liên tiếp.
 *
 * <p>Một lần điều hướng bắn nhiều sự kiện `did-navigate` /
 * `did-navigate-in-page` (chuyển hướng, neo trong trang, thay đổi lịch sử bằng
 * JavaScript). Không lọc thì một lần bấm liên kết tạo ra ba, bốn lượt gọi
 * mạng — và máy chủ tuy có gộp theo URL, nhưng ba request thừa vẫn là ba
 * request thừa.
 */
let urlVuaGhi = ''
let lucVuaGhi = 0
const KHOANG_CHONG_TRUNG_MS = 1000

export const useBrowsingStore = create<BrowsingState>((set, get) => ({
  items: [],
  tuKhoa: '',
  tong: 0,
  dangTai: false,
  loi: null,
  chuaDangNhap: false,

  nap: async (tuKhoa) => {
    const khoa = tuKhoa ?? get().tuKhoa
    set({ dangTai: true, loi: null, tuKhoa: khoa })
    try {
      const trang = await historyApi.visitHistory({ q: khoa || undefined, page: 0, size: 100 })
      set({
        items: trang.content,
        tong: trang.totalElements,
        dangTai: false,
        chuaDangNhap: false
      })
    } catch (error) {
      if (error instanceof NotAuthenticated) {
        // KHÔNG phải lỗi. Lịch sử là dữ liệu cá nhân, nên chưa đăng nhập thì
        // không có gì để hiện — và màn hình phải mời đăng nhập chứ không báo
        // hỏng.
        set({ items: [], tong: 0, dangTai: false, chuaDangNhap: true, loi: null })
        return
      }
      set({
        dangTai: false,
        loi: 'Không kết nối được history-service. Kiểm tra backend đang chạy.'
      })
    }
  },

  ghi: (url, title, incognito) => {
    // LUẬT ĐẦU TIÊN, không có ngoại lệ: ẩn danh thì không gửi gì cả. Xem
    // Javadoc của HistoryController về việc vì sao lọc ở MÁY KHÁCH chứ không
    // nhờ máy chủ "đừng lưu".
    if (incognito || boQua(url)) {
      return
    }
    const bayGio = Date.now()
    if (url === urlVuaGhi && bayGio - lucVuaGhi < KHOANG_CHONG_TRUNG_MS) {
      return
    }
    urlVuaGhi = url
    lucVuaGhi = bayGio
    void historyApi.recordVisit(url, title)
  },

  deleteVisit: async (id) => {
    // Bỏ khỏi danh sách NGAY, không chờ máy chủ: người dùng vừa bấm xoá và
    // mục phải biến mất tức thì. Nếu lượt gọi hỏng thì lần nạp lại sau sẽ
    // hiện lại nó — sai lệch tạm thời, chấp nhận được, đổi lấy giao diện
    // không giật.
    set((state) => ({
      items: state.items.filter((item) => item.id !== id),
      tong: Math.max(0, state.tong - 1)
    }))
    try {
      await historyApi.deleteVisit(id)
    } catch {
      await get().nap()
    }
  },

  deleteVisitRange: async (khoang) => {
    const ketQua = await historyApi.deleteVisitRange(mocBatDau(khoang))
    await get().nap()
    return ketQua.deleted
  }
}))

/**
 * Nhóm các mục theo NGÀY, giữ nguyên thứ tự mới nhất trước.
 *
 * <p>Nhóm ở phía máy khách chứ không nhờ máy chủ: ranh giới "hôm nay" phụ
 * thuộc múi giờ của NGƯỜI DÙNG, và máy chủ không biết múi giờ đó. Nhóm ở máy
 * chủ sẽ khiến một người ở Việt Nam thấy các mục lúc 7 giờ sáng nằm dưới nhãn
 * "hôm qua" nếu máy chủ chạy giờ UTC.
 */
export function nhomTheoNgay(items: VisitDto[]): Array<[string, VisitDto[]]> {
  const nhom = new Map<string, VisitDto[]>()
  for (const item of items) {
    const nhan = nhanNgay(new Date(item.visitedAt))
    const list = nhom.get(nhan)
    if (list) {
      list.push(item)
    } else {
      nhom.set(nhan, [item])
    }
  }
  return [...nhom.entries()]
}

function nhanNgay(ngay: Date): string {
  const homNay = new Date()
  homNay.setHours(0, 0, 0, 0)
  const homQua = new Date(homNay.getTime() - 86_400_000)

  if (ngay >= homNay) {
    return 'Hôm nay'
  }
  if (ngay >= homQua) {
    return 'Hôm qua'
  }
  return ngay.toLocaleDateString('vi-VN', {
    weekday: 'long',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric'
  })
}

/** Giờ:phút của một mốc thời gian, theo giờ máy người dùng. */
export function gioPhut(iso: string): string {
  return new Date(iso).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
}
