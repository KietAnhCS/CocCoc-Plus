import { describe, it, expect, beforeEach, vi } from 'vitest'
import { AdminAuthError, type AdminCredential, type ManagedAccount } from '../lib/adminApi'

/**
 * Các thao tác quản lý tài khoản trong `dashboardStore`.
 *
 * Ba điều được chốt, và cả ba đều là chỗ đã hoặc dễ làm sai:
 *   1. đổi vai trò / xoá xong phải TẢI LẠI danh sách từ máy chủ;
 *   2. 401 phải hạ quyền ngay, không im lặng nuốt;
 *   3. `pendingAccount` phải được dọn kể cả khi hỏng — nếu không, mọi nút
 *      trong bảng kẹt ở trạng thái vô hiệu hoá vĩnh viễn.
 */
const api = vi.hoisted(() => ({
  fetchAccounts: vi.fn(),
  changeRole: vi.fn(),
  deleteAccount: vi.fn(),
  fetchDashboard: vi.fn(),
  resetTraffic: vi.fn()
}))
const admin = vi.hoisted(() => ({ revoke: vi.fn() }))

vi.mock('../lib/adminApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/adminApi')>()
  return { ...actual, ...api }
})
vi.mock('./adminStore', () => ({
  useAdminStore: { getState: () => admin }
}))

const { useDashboardStore } = await import('./dashboardStore')

const CRED: AdminCredential = { kind: 'session', token: 'token-gia' }
const NGUOI_DUNG: ManagedAccount = {
  username: 'nguoidung',
  role: 'USER',
  enabled: true,
  createdAt: '2026-08-10T10:00:00Z',
  lastLoginAt: null
}

describe('dashboardStore — tài khoản', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useDashboardStore.setState({ accounts: null, accountsError: null, pendingAccount: null })
  })

  it('tải danh sách tài khoản', async () => {
    api.fetchAccounts.mockResolvedValue([NGUOI_DUNG])

    await useDashboardStore.getState().loadAccounts(CRED)

    expect(useDashboardStore.getState().accounts).toEqual([NGUOI_DUNG])
    expect(useDashboardStore.getState().accountsError).toBeNull()
  })

  it('401 khi tải danh sách thì báo lỗi của máy chủ', async () => {
    api.fetchAccounts.mockRejectedValue(new AdminAuthError('Khoá không còn hiệu lực.'))

    await useDashboardStore.getState().loadAccounts(CRED)

    expect(useDashboardStore.getState().accountsError).toBe('Khoá không còn hiệu lực.')
  })

  /** Đọc lại từ máy chủ chứ không tự sửa mảng: máy chủ còn đóng phiên của người đó. */
  it('đổi vai trò xong thì TẢI LẠI danh sách', async () => {
    api.changeRole.mockResolvedValue({ ...NGUOI_DUNG, role: 'ADMIN' })
    api.fetchAccounts.mockResolvedValue([{ ...NGUOI_DUNG, role: 'ADMIN' }])

    await useDashboardStore.getState().setAccountRole(CRED, 'nguoidung', 'ADMIN')

    expect(api.changeRole).toHaveBeenCalledWith(CRED, 'nguoidung', 'ADMIN')
    expect(api.fetchAccounts).toHaveBeenCalled()
    expect(useDashboardStore.getState().accounts?.[0].role).toBe('ADMIN')
  })

  it('đổi vai trò hỏng thì giữ nguyên thông báo của máy chủ', async () => {
    api.changeRole.mockRejectedValue(new Error('Không thể tự hạ vai trò của chính mình.'))

    await useDashboardStore.getState().setAccountRole(CRED, 'toi', 'USER')

    expect(useDashboardStore.getState().accountsError).toBe(
      'Không thể tự hạ vai trò của chính mình.'
    )
  })

  it('xoá tài khoản xong thì TẢI LẠI danh sách', async () => {
    api.deleteAccount.mockResolvedValue(undefined)
    api.fetchAccounts.mockResolvedValue([])

    await useDashboardStore.getState().removeAccount(CRED, 'nguoidung')

    expect(api.deleteAccount).toHaveBeenCalledWith(CRED, 'nguoidung')
    expect(useDashboardStore.getState().accounts).toEqual([])
  })

  /**
   * Nếu quên dọn `pendingAccount` ở nhánh hỏng thì MỌI nút trong bảng kẹt ở
   * trạng thái vô hiệu hoá — bảng trở thành chỉ đọc mà không ai hiểu vì sao.
   */
  it('luôn dọn pendingAccount, kể cả khi thất bại', async () => {
    api.deleteAccount.mockRejectedValue(new Error('hỏng'))

    await useDashboardStore.getState().removeAccount(CRED, 'nguoidung')

    expect(useDashboardStore.getState().pendingAccount).toBeNull()
  })

  it('clear() xoá cả số liệu lẫn danh sách tài khoản', () => {
    useDashboardStore.setState({ accounts: [NGUOI_DUNG], accountsError: 'x' })

    useDashboardStore.getState().clear()

    expect(useDashboardStore.getState().accounts).toBeNull()
    expect(useDashboardStore.getState().accountsError).toBeNull()
  })
})
