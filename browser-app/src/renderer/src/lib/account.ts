/**
 * Tài khoản hiển thị trên thanh công cụ và trong menu.
 *
 * Ứng dụng chưa có phần đăng nhập thật (không có máy chủ tài khoản), nên đây
 * là dữ liệu tượng trưng, cố định. Tách ra một chỗ để avatar ở thanh công cụ
 * và hàng tài khoản trong menu không bao giờ hiện hai tên khác nhau.
 */
export const ACCOUNT = {
  name: 'Người dùng VnSearch',
  email: 'nguoidung@vnsearch.vn',
  initials: 'VN',
  status: 'Đã đăng nhập'
} as const
