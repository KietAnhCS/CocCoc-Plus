import { useMemo } from 'react'
import { create } from 'zustand'
import { AdminAuthError, fetchDashboard, type AdminCredential } from '../lib/adminApi'
import { getAuthToken } from '../lib/authToken'
import { useSessionStore } from './sessionStore'

/**
 * Đường xác thực thứ hai: <b>khoá quản trị tĩnh</b> (`X-API-Key`), dành cho
 * công cụ vận hành — và cho lối vào dự phòng khi chưa có tài khoản nào.
 *
 * Đường thứ nhất là tài khoản, ở `sessionStore`. Bảng điều khiển chấp nhận cả
 * hai, và {@link useAdminCredential} là nơi quyết định dùng cái nào.
 *
 * VÌ SAO KHOÁ KHÔNG ĐƯỢC LƯU BỀN, TRONG KHI TOKEN PHIÊN THÌ CÓ. Xem bảng so
 * sánh trong `lib/authToken.ts`: khoá này không hết hạn, không thu hồi được,
 * luôn mang quyền cao nhất và dùng chung cho mọi người. Một bí mật như vậy
 * không đáng nằm lại trên đĩa để đổi lấy việc đỡ gõ. Đóng ứng dụng là mất —
 * có chủ ý.
 *
 * VÀ ĐIỀU QUAN TRỌNG NHẤT: store này KHÔNG phải lớp bảo vệ. Lớp bảo vệ nằm ở
 * máy chủ (`SecurityConfig` + hai filter xác thực), và nó chặn mọi request
 * không có bằng chứng hợp lệ, bất kể giao diện vẽ ra cái gì. Ai sửa được
 * trạng thái trong bộ nhớ tiến trình sẽ mở được cái khung rỗng và thấy đúng
 * một thứ: 401 từ máy chủ.
 */

interface AdminState {
  /** Khoá quản trị đang giữ. `null` = chưa nhập. CHỈ nằm trong bộ nhớ. */
  apiKey: string | null
  dashboardOpen: boolean
  verifying: boolean
  error: string | null

  /** Thử khoá bằng một lời gọi THẬT tới endpoint quản trị. */
  signInWithKey: (key: string) => Promise<boolean>
  clearKey: () => void
  openDashboard: () => void
  closeDashboard: () => void
  /** Máy chủ vừa từ chối thứ đang giữ: bỏ nó đi, không đợi lần bấm sau. */
  revoke: (message?: string) => void
  setError: (message: string | null) => void
}

export const useAdminStore = create<AdminState>((set) => ({
  apiKey: null,
  dashboardOpen: false,
  verifying: false,
  error: null,

  /**
   * Không có cách nào khác đúng: chỉ máy chủ biết khoá nào hợp lệ. Mọi phép
   * kiểm tra phía giao diện (độ dài, ký tự) chỉ bắt được lỗi gõ nhầm, và nếu
   * dừng ở đó thì người dùng "đăng nhập thành công" rồi mới thấy bảng trống —
   * thất bại ở xa nguyên nhân, kiểu khó chịu nhất.
   */
  signInWithKey: async (key) => {
    const trimmed = key.trim()
    if (!trimmed) {
      set({ error: 'Hãy nhập khoá quản trị.' })
      return false
    }
    set({ verifying: true, error: null })
    try {
      await fetchDashboard({ kind: 'apiKey', key: trimmed })
      set({ apiKey: trimmed, verifying: false, error: null })
      return true
    } catch (error) {
      set({
        apiKey: null,
        verifying: false,
        error:
          error instanceof AdminAuthError
            ? error.message
            : 'Không kết nối được tới máy chủ (http://localhost:8080). Kiểm tra backend đang chạy.'
      })
      return false
    }
  },

  clearKey: () => set({ apiKey: null, error: null }),

  openDashboard: () => set({ dashboardOpen: true }),

  closeDashboard: () => set({ dashboardOpen: false }),

  revoke: (message) =>
    set({
      apiKey: null,
      error: message ?? 'Quyền quản trị không còn hiệu lực. Hãy xác thực lại.'
    }),

  setError: (message) => set({ error: message })
}))

/**
 * Bằng chứng quyền quản trị đang dùng được, hoặc `null`.
 *
 * <b>Tài khoản được ưu tiên hơn khoá tĩnh</b> khi có cả hai: phiên có danh
 * tính ghi lại được *ai* đã gọi, còn khoá tĩnh thì không. Máy chủ áp đúng thứ
 * tự ưu tiên này trong chuỗi filter, nên hai bên nhất quán.
 *
 * Là một hook (không phải hàm thường) vì nó phải *đăng ký nhận* thay đổi từ cả
 * hai store — đăng nhập bằng tài khoản ADMIN ở một góc màn hình phải làm bảng
 * điều khiển đang mở tự có quyền, không đợi mở lại.
 */
export function useAdminCredential(): AdminCredential | null {
  const role = useSessionStore((state) => state.user?.role)
  const apiKey = useAdminStore((state) => state.apiKey)
  const token = role === 'ADMIN' ? getAuthToken() : null

  // useMemo KHÔNG phải để tối ưu ở đây — nó là thứ giữ cho ĐÚNG.
  //
  // Giá trị này được dùng làm phụ thuộc của effect tải số liệu. Không ghi nhớ
  // thì mỗi lần render sinh một object mới, `Object.is` thấy khác, effect chạy
  // lại: huỷ request đang bay, xoá số liệu, gọi lại — rồi lần render kế tiếp
  // lặp lại y hệt. Kết quả là bảng điều khiển kẹt ở "Đang tải…" kèm thông báo
  // lỗi, dù máy chủ trả 200 cho mọi request.
  //
  // Lỗi này KHÔNG lộ ra ở test hay typecheck; nó chỉ hiện khi mở ứng dụng thật
  // và nhìn vào màn hình.
  return useMemo(() => {
    if (token) {
      return { kind: 'session', token }
    }
    return apiKey ? { kind: 'apiKey', key: apiKey } : null
  }, [token, apiKey])
}
