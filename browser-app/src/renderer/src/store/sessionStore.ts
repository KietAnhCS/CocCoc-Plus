import { create } from 'zustand'
import {
  AuthError,
  ServerError,
  changePassword,
  login,
  logout,
  logoutEverywhere,
  me,
  register,
  type AccountDto
} from '../lib/authApi'
import { setAuthToken } from '../lib/authToken'

/**
 * Phiên đăng nhập của người dùng ứng dụng.
 *
 * PHÂN VAI VỚI `adminStore`. Hai store nghe giống nhau nhưng là hai *cơ chế*
 * xác thực khác nhau, cố ý tách rời:
 *
 *   sessionStore  con người  tài khoản + mật khẩu → token, có vai trò USER/ADMIN
 *   adminStore    công cụ    khoá X-API-Key tĩnh, luôn là quyền quản trị
 *
 * Bảng điều khiển chấp nhận cả hai. Giữ chúng ở hai store thay vì nhồi vào một
 * chỗ vì vòng đời của chúng khác hẳn: token thì tự khôi phục lúc khởi động và
 * tự hết hạn, còn khoá thì chỉ sống trong bộ nhớ tới lúc đóng ứng dụng.
 *
 * NGUỒN SỰ THẬT LÀ MÁY CHỦ. `restore()` không tin `localStorage`: nó gọi
 * `/api/auth/me` và để máy chủ nói người dùng là ai. Tin bản sao ở máy khách
 * thì một người đã bị hạ quyền vẫn thấy giao diện quản trị đầy đủ cho tới lần
 * gọi API đầu tiên thất bại — trông như lỗi, và tệ hơn, che mất việc quyền đã
 * bị thu hồi.
 */

/**
 * Màn hình tài khoản đang mở, hoặc `null`.
 *
 * Ba màn hình dùng chung một lớp phủ toàn màn hình vì chúng là ba bước của
 * cùng một việc và người dùng chuyển qua lại giữa chúng ("chưa có tài khoản?
 * đăng ký"). Ba lớp phủ riêng sẽ phải tự đồng bộ để không cùng mở một lúc.
 */
export type AuthScreen = 'signin' | 'signup' | 'password'

interface SessionState {
  user: AccountDto | null
  /** `false` cho tới khi `restore()` xong — trước đó chưa biết gì để vẽ. */
  ready: boolean
  busy: boolean
  error: string | null
  /** Thông báo thành công ngắn, ví dụ sau khi đổi mật khẩu. */
  notice: string | null
  screen: AuthScreen | null

  restore: () => Promise<void>
  signIn: (username: string, password: string) => Promise<boolean>
  signUp: (username: string, password: string) => Promise<boolean>
  signOut: () => Promise<void>
  changePassword: (currentPassword: string, newPassword: string) => Promise<boolean>
  signOutEverywhere: () => Promise<void>
  openScreen: (screen: AuthScreen) => void
  closeScreen: () => void
  clearError: () => void
}

/**
 * Ba loại hỏng, ba câu khác nhau — vì chúng dẫn tới ba việc phải làm khác nhau.
 *
 *   AuthError    máy chủ nói bạn gõ sai      -> gõ lại cho đúng
 *   ServerError  máy chủ trả lời bằng lỗi    -> chờ (429) hoặc xem log (5xx)
 *   còn lại      không nối được tới máy chủ  -> bật backend lên
 *
 * Bản đầu gộp hai loại sau làm một và luôn nói "không kết nối được, kiểm tra
 * backend đang chạy". Câu đó **sai sự thật** khi máy chủ vừa trả về 429 — nó
 * có chạy, nó vừa trả lời — và người đọc sẽ đi khởi động lại backend cho một
 * việc không liên quan. Lỗi này lộ ra khi chạy thử ứng dụng thật.
 */
function describe(error: unknown): string {
  if (error instanceof AuthError || error instanceof ServerError) {
    return error.message
  }
  return 'Không kết nối được tới máy chủ (http://localhost:8080). Kiểm tra backend đang chạy.'
}

export const useSessionStore = create<SessionState>((set) => ({
  user: null,
  ready: false,
  busy: false,
  error: null,
  notice: null,
  screen: null,

  restore: async () => {
    try {
      const user = await me()
      if (!user) {
        // Token hết hạn hoặc bị thu hồi: dọn luôn để lần gọi sau không mang
        // theo một header vô nghĩa.
        setAuthToken(null)
      }
      set({ user, ready: true })
    } catch {
      // Máy chủ chưa chạy. KHÔNG xoá token: phiên có thể vẫn còn hiệu lực, và
      // đăng xuất người dùng chỉ vì backend đang khởi động lại là hành vi tồi.
      set({ user: null, ready: true })
    }
  },

  signIn: async (username, password) => {
    set({ busy: true, error: null })
    try {
      const response = await login(username, password)
      setAuthToken(response.token)
      // Đóng luôn màn hình đăng nhập: người dùng vừa hoàn thành việc họ mở nó ra để làm.
      set({ user: response.user, busy: false, error: null, ready: true, screen: null })
      return true
    } catch (error) {
      setAuthToken(null)
      set({ user: null, busy: false, error: describe(error) })
      return false
    }
  },

  /**
   * Đăng ký rồi đăng nhập luôn.
   *
   * Bắt người vừa tạo tài khoản gõ lại đúng thông tin họ vừa gõ là một bước
   * thừa duy nhất phục vụ sự tiện của lập trình viên.
   */
  signUp: async (username, password) => {
    set({ busy: true, error: null })
    try {
      await register(username, password)
    } catch (error) {
      set({ busy: false, error: describe(error) })
      return false
    }
    return useSessionStore.getState().signIn(username, password)
  },

  signOut: async () => {
    await logout()
    setAuthToken(null)
    set({ user: null, error: null, notice: null, screen: null })
  },

  /**
   * Đổi mật khẩu. Phiên hiện tại được GIỮ (máy chủ quyết định điều đó), nên
   * không đăng xuất ở đây — chỉ báo lại đã đóng bao nhiêu phiên khác.
   */
  changePassword: async (currentPassword, newPassword) => {
    set({ busy: true, error: null, notice: null })
    try {
      const closed = await changePassword(currentPassword, newPassword)
      set({
        busy: false,
        screen: null,
        notice:
          closed > 0
            ? `Đã đổi mật khẩu. ${closed} phiên đăng nhập khác đã bị đóng.`
            : 'Đã đổi mật khẩu.'
      })
      return true
    } catch (error) {
      set({ busy: false, error: describe(error) })
      return false
    }
  },

  /** Đăng xuất khỏi MỌI thiết bị, kể cả thiết bị này. */
  signOutEverywhere: async () => {
    set({ busy: true, error: null })
    try {
      await logoutEverywhere()
    } catch {
      // Máy chủ không trả lời thì vẫn phải dọn phía máy này — xem `logout` ở
      // `authApi`. Phiên phía máy chủ sẽ tự hết hạn.
    }
    setAuthToken(null)
    set({ user: null, busy: false, error: null, notice: null, screen: null })
  },

  openScreen: (screen) => set({ screen, error: null, notice: null }),

  closeScreen: () => set({ screen: null, error: null }),

  clearError: () => set({ error: null })
}))
