import { describe, it, expect } from 'vitest'
import {
  MIN_PASSWORD_LENGTH,
  passwordStrength,
  validateConfirmation,
  validatePassword,
  validateUsername
} from './validation'

/**
 * Luật ở đây phải KHỚP với `UserService` phía Java. Lỏng hơn máy chủ thì giao
 * diện hứa một đằng máy chủ bác một nẻo — và người dùng học được rằng đừng tin
 * thông báo của giao diện.
 */
describe('validateUsername', () => {
  it('chấp nhận tên hợp lệ', () => {
    expect(validateUsername('nguyenvana')).toBeNull()
    expect(validateUsername('nguoi.dung_01')).toBeNull()
    expect(validateUsername('a-b-c')).toBeNull()
  })

  it('từ chối tên quá ngắn hoặc quá dài', () => {
    expect(validateUsername('ab')).toContain('3 ký tự')
    expect(validateUsername('x'.repeat(33))).toContain('32 ký tự')
  })

  it('từ chối khoảng trắng và ký tự lạ', () => {
    expect(validateUsername('co khoang trang')).toContain('Chỉ dùng')
    expect(validateUsername('ten/co/gach-cheo')).toContain('Chỉ dùng')
    expect(validateUsername('nguyễn')).toContain('Chỉ dùng')
  })

  it('ô rỗng thì nhắc nhập, không phải báo sai định dạng', () => {
    expect(validateUsername('   ')).toBe('Hãy nhập tên tài khoản.')
  })
})

describe('validatePassword', () => {
  it('chấp nhận mật khẩu đủ dài', () => {
    expect(validatePassword('x'.repeat(MIN_PASSWORD_LENGTH))).toBeNull()
  })

  it('nói rõ đang thiếu bao nhiêu', () => {
    expect(validatePassword('ngan')).toContain('đang có 4')
  })

  it('từ chối mật khẩu quá dài', () => {
    expect(validatePassword('x'.repeat(201))).toContain('200 ký tự')
  })
})

describe('validateConfirmation', () => {
  it('chưa gõ gì thì CHƯA báo lỗi', () => {
    expect(validateConfirmation('matkhaudaidu', '')).toBeNull()
  })

  it('báo khi hai lần nhập lệch nhau', () => {
    expect(validateConfirmation('matkhaudaidu', 'matkhaukhac')).toContain('không khớp')
  })

  it('khớp thì không báo gì', () => {
    expect(validateConfirmation('matkhaudaidu', 'matkhaudaidu')).toBeNull()
  })
})

describe('passwordStrength', () => {
  it('dưới ngưỡng tối thiểu là điểm 0', () => {
    expect(passwordStrength('ngan').score).toBe(0)
  })

  it('độ dài ảnh hưởng nhiều hơn ký tự đặc biệt', () => {
    const daiThuan = passwordStrength('conmeotrangnhotrongsan')
    const nganPhucTap = passwordStrength('Ab1!xyzq')

    expect(daiThuan.score).toBeGreaterThan(nganPhucTap.score)
  })

  it('không bao giờ vượt thang 0..3', () => {
    const manh = passwordStrength('Rat-Dai-Va-Phuc-Tap-123456789!')
    expect(manh.score).toBe(3)
    expect(manh.label).toBe('Mạnh')
  })
})
