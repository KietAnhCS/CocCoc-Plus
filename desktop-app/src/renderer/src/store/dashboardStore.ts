import { create } from 'zustand'
import {
  AdminAuthError,
  AdminServerError,
  changeRole,
  deleteAccount,
  fetchAccounts,
  fetchDashboard,
  resetTraffic,
  type AdminCredential,
  type DashboardDto,
  type ManagedAccount
} from '../lib/adminApi'
import { useAdminStore } from './adminStore'

/**
 * Số liệu của bảng điều khiển, và trạng thái của lần tải gần nhất.
 *
 * VÌ SAO Ở STORE CHỨ KHÔNG PHẢI `useState` TRONG COMPONENT. Dữ liệu này được
 * làm mới theo một CHU KỲ chạy nền, không theo lượt render — nó là trạng thái
 * của một hệ thống bên ngoài (máy chủ) chứ không phải của một khung giao diện.
 * Để trong component thì effect khởi động phải `setState` ngay trong thân
 * effect, tạo một vòng render nối tiếp mỗi lần bảng mở; ở store thì component
 * chỉ việc *đăng ký nhận*, đúng vai mà React dành cho effect.
 *
 * Bộ điều khiển huỷ nằm ở phạm vi module, không phải trong state: nó không
 * ảnh hưởng tới thứ được vẽ ra, nên đưa vào state chỉ tạo thêm những lần render
 * không cần thiết.
 */

let inFlight: AbortController | null = null

interface DashboardState {
  data: DashboardDto | null
  error: string | null
  loading: boolean

  /**
   * Tải lại số liệu.
   *
   * @param spinner `false` cho lần tải đầu — lúc đó `data` còn `null` nên
   *                khung xương đang hiện, thêm con quay chỉ là nhiễu
   */
  load: (credential: AdminCredential, spinner?: boolean) => Promise<void>
  resetTraffic: (credential: AdminCredential) => Promise<void>
  /**
   * Danh sách tài khoản. `null` = chưa tải lần nào.
   *
   * Ở store cùng lý do với `data`: nó được tải trong một effect, và một
   * `useState` trong component sẽ buộc effect khởi động gọi `setState` ngay
   * trong thân nó — vòng render nối tiếp mỗi lần bảng mở.
   */
  accounts: ManagedAccount[] | null
  accountsError: string | null
  /** Tên tài khoản đang chờ máy chủ trả lời cho một thao tác đổi vai trò. */
  pendingAccount: string | null

  loadAccounts: (credential: AdminCredential) => Promise<void>
  setAccountRole: (
    credential: AdminCredential,
    username: string,
    role: 'USER' | 'ADMIN'
  ) => Promise<void>
  removeAccount: (credential: AdminCredential, username: string) => Promise<void>

  /** Vứt bỏ số liệu khi thoát quyền — không giữ thứ mà vai trò hiện tại không được xem. */
  clear: () => void
}

export const useDashboardStore = create<DashboardState>((set) => ({
  data: null,
  error: null,
  loading: false,
  accounts: null,
  accountsError: null,
  pendingAccount: null,

  load: async (credential, spinner = true) => {
    // Huỷ lần tải trước: hai phản hồi về không đúng thứ tự sẽ làm bảng nhấp
    // nháy giữa số liệu cũ và mới.
    inFlight?.abort()
    const controller = new AbortController()
    inFlight = controller
    if (spinner) {
      set({ loading: true })
    }

    try {
      const data = await fetchDashboard(credential, controller.signal)
      set({ data, error: null })
    } catch (caught) {
      if (controller.signal.aborted) {
        return
      }
      if (caught instanceof AdminAuthError) {
        // Máy chủ đã thu hồi quyền (khoá đổi, vai trò bị hạ, phiên hết hạn).
        // Hạ quyền NGAY thay vì cứ 10 giây lại gọi một lần và tích thêm một
        // dòng cảnh báo trong log máy chủ mỗi lần.
        useAdminStore.getState().revoke()
        return
      }
      set({
        error:
          caught instanceof AdminServerError
            ? caught.message
            : 'Không kết nối được tới máy chủ. Số liệu bên dưới là của lần tải gần nhất.'
      })
    } finally {
      if (!controller.signal.aborted) {
        set({ loading: false })
      }
    }
  },

  resetTraffic: async (credential) => {
    try {
      await resetTraffic(credential)
    } catch (caught) {
      set({
        error:
          caught instanceof AdminAuthError
            ? caught.message
            : 'Không đặt lại được số liệu lưu lượng.'
      })
      return
    }
    await useDashboardStore.getState().load(credential, false)
  },

  loadAccounts: async (credential) => {
    try {
      set({ accounts: await fetchAccounts(credential), accountsError: null })
    } catch (caught) {
      set({
        accountsError:
          caught instanceof AdminAuthError ? caught.message : 'Không tải được danh sách tài khoản.'
      })
    }
  },

  /**
   * Đổi vai trò rồi TẢI LẠI danh sách.
   *
   * Không tự sửa mảng trong bộ nhớ cho nhanh: máy chủ còn đóng mọi phiên của
   * người đó, và có thể từ chối (tự hạ quyền chính mình). Đọc lại là cách duy
   * nhất chắc chắn bảng khớp với trạng thái thật.
   */
  setAccountRole: async (credential, username, role) => {
    set({ pendingAccount: username, accountsError: null })
    try {
      await changeRole(credential, username, role)
      set({ accounts: await fetchAccounts(credential) })
    } catch (caught) {
      set({
        accountsError: caught instanceof Error ? caught.message : 'Không đổi được vai trò.'
      })
    } finally {
      set({ pendingAccount: null })
    }
  },

  removeAccount: async (credential, username) => {
    set({ pendingAccount: username, accountsError: null })
    try {
      await deleteAccount(credential, username)
      set({ accounts: await fetchAccounts(credential) })
    } catch (caught) {
      set({ accountsError: caught instanceof Error ? caught.message : 'Không xoá được tài khoản.' })
    } finally {
      set({ pendingAccount: null })
    }
  },

  clear: () => {
    inFlight?.abort()
    inFlight = null
    set({
      data: null,
      error: null,
      loading: false,
      accounts: null,
      accountsError: null,
      pendingAccount: null
    })
  }
}))
