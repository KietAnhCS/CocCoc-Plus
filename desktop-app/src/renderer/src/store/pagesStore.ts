import { create } from 'zustand'

/**
 * Trang nội bộ đang mở dưới dạng LỚP PHỦ.
 *
 * <p>Chrome mở {@code chrome://history} và {@code chrome://settings} thành
 * những thẻ thật. Ở đây không làm được: nội dung của một thẻ do một
 * {@code WebContentsView} riêng vẽ, nằm NGOÀI cây React — nên một trang React
 * không hiển thị trong thẻ được.
 *
 * <p>Hệ quả phải chấp nhận: hai trang này không có địa chỉ riêng, không lưu
 * được vào dấu trang, và nút Quay lại không đưa người dùng ra khỏi chúng. Đổi
 * lại là một cách vẽ giao diện nội bộ duy nhất cho cả ứng dụng.
 *
 * <p><b>MỘT trang tại một thời điểm.</b> Kiểu dữ liệu là một giá trị chứ không
 * phải một tập hợp cờ bật/tắt, nên "mở Cài đặt trong khi Nhật ký đang mở" tự
 * động đóng cái trước — không có trạng thái hai lớp phủ chồng nhau để phải xử
 * lý.
 */
export type TrangNoiBo = 'history' | 'settings' | null

interface PagesState {
  page: TrangNoiBo
  mo: (page: Exclude<TrangNoiBo, null>) => void
  dong: () => void
}

export const usePagesStore = create<PagesState>((set) => ({
  page: null,
  mo: (page) => set({ page }),
  dong: () => set({ page: null })
}))
