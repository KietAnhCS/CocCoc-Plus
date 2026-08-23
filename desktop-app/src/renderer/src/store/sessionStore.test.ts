import { describe, it, expect, beforeEach, vi } from 'vitest'
import { AuthError, type AccountDto } from '../lib/authApi'

/**
 * Phiên đăng nhập phía giao diện.
 *
 * Ba điều được chốt ở đây, và cả ba đều là chỗ dễ làm sai:
 *   1. đăng nhập hỏng thì KHÔNG được giữ lại token nào;
 *   2. `restore()` tin MÁY CHỦ, không tin token trong localStorage;
 *   3. mất mạng lúc khởi động thì KHÔNG được xoá token — phiên có thể vẫn còn.
 */

const api = vi.hoisted(() => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
  register: vi.fn()
}))
const token = vi.hoisted(() => ({ setAuthToken: vi.fn() }))

vi.mock('../lib/authApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/authApi')>()
  return { ...actual, ...api }
})
vi.mock('../lib/authToken', () => token)

const { useSessionStore } = await import('./sessionStore')

const NGUOI_DUNG: AccountDto = {
  username: 'nguoidung',
  role: 'USER',
  enabled: true,
  createdAt: '2026-08-10T10:00:00Z',
  lastLoginAt: null
}

describe('sessionStore', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useSessionStore.setState({ user: null, ready: false, busy: false, error: null })
  })

  it('đăng nhập thành công thì lưu token và người dùng', async () => {
    api.login.mockResolvedValue({
      token: 'token-moi',
      expiresAt: '2026-08-10T22:00:00Z',
      user: NGUOI_DUNG
    })

    const ok = await useSessionStore.getState().signIn('nguoidung', 'matkhaudaidu')

    expect(ok).toBe(true)
    expect(token.setAuthToken).toHaveBeenCalledWith('token-moi')
    expect(useSessionStore.getState().user).toEqual(NGUOI_DUNG)
    expect(useSessionStore.getState().error).toBeNull()
  })

  it('đăng nhập hỏng thì xoá sạch token và giữ thông báo của máy chủ', async () => {
    api.login.mockRejectedValue(new AuthError('Tên tài khoản hoặc mật khẩu không đúng.'))

    const ok = await useSessionStore.getState().signIn('nguoidung', 'sai')

    expect(ok).toBe(false)
    expect(token.setAuthToken).toHaveBeenCalledWith(null)
    expect(useSessionStore.getState().user).toBeNull()
    expect(useSessionStore.getState().error).toBe('Tên tài khoản hoặc mật khẩu không đúng.')
  })

  /** Lỗi mạng phải nói khác lỗi từ chối, nếu không người dùng sửa nhầm chỗ. */
  it('lỗi mạng thì báo là lỗi kết nối, không phải sai mật khẩu', async () => {
    api.login.mockRejectedValue(new TypeError('Failed to fetch'))

    await useSessionStore.getState().signIn('nguoidung', 'matkhaudaidu')

    expect(useSessionStore.getState().error).toContain('Không kết nối được')
  })

  it('đăng ký xong thì đăng nhập luôn, không bắt gõ lại', async () => {
    api.register.mockResolvedValue(NGUOI_DUNG)
    api.login.mockResolvedValue({
      token: 'token-moi',
      expiresAt: '2026-08-10T22:00:00Z',
      user: NGUOI_DUNG
    })

    const ok = await useSessionStore.getState().signUp('nguoidung', 'matkhaudaidu')

    expect(ok).toBe(true)
    expect(api.login).toHaveBeenCalledWith('nguoidung', 'matkhaudaidu')
    expect(useSessionStore.getState().user).toEqual(NGUOI_DUNG)
  })

  it('đăng ký hỏng thì KHÔNG thử đăng nhập', async () => {
    api.register.mockRejectedValue(new AuthError('Tên tài khoản đã tồn tại: nguoidung'))

    const ok = await useSessionStore.getState().signUp('nguoidung', 'matkhaudaidu')

    expect(ok).toBe(false)
    expect(api.login).not.toHaveBeenCalled()
    expect(useSessionStore.getState().error).toContain('đã tồn tại')
  })

  it('khôi phục phiên theo lời MÁY CHỦ', async () => {
    api.me.mockResolvedValue(NGUOI_DUNG)

    await useSessionStore.getState().restore()

    expect(useSessionStore.getState().user).toEqual(NGUOI_DUNG)
    expect(useSessionStore.getState().ready).toBe(true)
  })

  it('máy chủ nói token không còn hiệu lực thì dọn token đi', async () => {
    api.me.mockResolvedValue(null)

    await useSessionStore.getState().restore()

    expect(token.setAuthToken).toHaveBeenCalledWith(null)
    expect(useSessionStore.getState().user).toBeNull()
    expect(useSessionStore.getState().ready).toBe(true)
  })

  /**
   * Backend đang khởi động lại KHÔNG phải lý do để đăng xuất người dùng: phiên
   * có thể vẫn còn hiệu lực, và xoá token ở đây bắt họ đăng nhập lại vô cớ.
   */
  it('mất kết nối lúc khởi động thì KHÔNG xoá token', async () => {
    api.me.mockRejectedValue(new TypeError('Failed to fetch'))

    await useSessionStore.getState().restore()

    expect(token.setAuthToken).not.toHaveBeenCalled()
    expect(useSessionStore.getState().ready).toBe(true)
  })

  it('đăng xuất thì báo máy chủ rồi xoá trạng thái tại máy', async () => {
    useSessionStore.setState({ user: NGUOI_DUNG })
    api.logout.mockResolvedValue(undefined)

    await useSessionStore.getState().signOut()

    expect(api.logout).toHaveBeenCalled()
    expect(token.setAuthToken).toHaveBeenCalledWith(null)
    expect(useSessionStore.getState().user).toBeNull()
  })
})
