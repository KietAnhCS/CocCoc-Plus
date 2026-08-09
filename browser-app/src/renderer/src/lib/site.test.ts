import { describe, expect, it } from 'vitest'
import { hostOf, prettyUrl, siteGradient, siteInitial } from './site'

/**
 * Bốn hàm này chạy trên MỌI kết quả tìm kiếm được vẽ ra, và cả bốn đều nhận
 * đầu vào từ dữ liệu crawl — tức là những chuỗi mà không ai kiểm soát hình
 * dạng. Điều đáng kiểm nhất vì vậy không phải là ca đẹp, mà là: chúng có bao
 * giờ ném lỗi không. Một ngoại lệ ở đây làm trắng cả danh sách kết quả.
 */
describe('hostOf', () => {
  it('rút host và bỏ tiền tố www.', () => {
    expect(hostOf('https://www.vnexpress.net/thoi-su')).toBe('vnexpress.net')
    expect(hostOf('https://tuoitre.vn')).toBe('tuoitre.vn')
  })

  it('trả về nguyên chuỗi khi không phân tích được, KHÔNG ném lỗi', () => {
    expect(hostOf('không-phải-url')).toBe('không-phải-url')
    expect(hostOf('')).toBe('')
  })

  it('chỉ bỏ www. ở ĐẦU, không bỏ ở giữa', () => {
    expect(hostOf('https://www.vnexpress.net')).toBe('vnexpress.net')
    expect(hostOf('https://mywww.vi-du.com')).toBe('mywww.vi-du.com')
  })
})

describe('prettyUrl', () => {
  it('ghép host với tối đa ba đoạn đường dẫn', () => {
    expect(prettyUrl('https://www.vnexpress.net/thoi-su/chinh-tri')).toBe(
      'vnexpress.net › thoi-su › chinh-tri'
    )
  })

  it('cắt bớt khi đường dẫn dài hơn ba đoạn', () => {
    expect(prettyUrl('https://vi-du.com/a/b/c/d/e')).toBe('vi-du.com › a › b › c')
  })

  it('chỉ còn host khi không có đường dẫn', () => {
    expect(prettyUrl('https://vi-du.com/')).toBe('vi-du.com')
  })

  it('trả về nguyên chuỗi khi không phân tích được', () => {
    expect(prettyUrl('rác')).toBe('rác')
  })
})

describe('siteGradient / siteInitial', () => {
  it('cùng một host luôn cho cùng một màu (ổn định giữa các lần vẽ)', () => {
    // Nếu hàm băm không ổn định thì mỗi lần render lại là một màu khác —
    // biểu tượng trang web nhấp nháy đổi màu khi cuộn danh sách.
    const a = siteGradient('https://vnexpress.net/mot-bai')
    const b = siteGradient('https://www.vnexpress.net/mot-bai-khac')
    expect(a).toBe(b)
  })

  it('host khác nhau thì màu khác nhau', () => {
    expect(siteGradient('https://vnexpress.net')).not.toBe(siteGradient('https://tuoitre.vn'))
  })

  it('sinh chuỗi CSS gradient hợp lệ', () => {
    expect(siteGradient('https://vi-du.com')).toMatch(/^linear-gradient\(135deg, hsl\(\d+ /)
  })

  it('lấy chữ cái đầu, viết hoa', () => {
    expect(siteInitial('https://vnexpress.net')).toBe('V')
    expect(siteInitial('https://tuoitre.vn')).toBe('T')
  })

  it('trả về "?" khi không rút được chữ cái nào', () => {
    expect(siteInitial('')).toBe('?')
    expect(siteInitial('...')).toBe('?')
  })

  it('tên miền quốc tế hoá được lấy chữ đầu của dạng punycode', () => {
    // `new URL` chuyển IDN sang punycode, nên '例え.jp' thành 'xn--r8jz45g.jp'
    // và chữ đầu là 'X'. Không phải hành vi lý tưởng (biểu tượng của mọi tên
    // miền IDN đều là 'X'), nhưng nó XÁC ĐỊNH và không ném lỗi — ghim lại ở
    // đây để lần sau ai đó sửa thì biết mình đang đổi cái gì.
    expect(siteInitial('https://例え.jp')).toBe('X')
  })
})
