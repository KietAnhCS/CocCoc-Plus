import { describe, it, expect } from 'vitest'
import { bytes, compact, count, dayLabel, millis, percent, shortUrl } from './format'

describe('count', () => {
  it('dùng dấu phân cách nghìn kiểu Việt Nam', () => {
    expect(count(1234567)).toBe('1.234.567')
  })

  it('trả về "—" cho giá trị không hợp lệ thay vì "NaN"', () => {
    expect(count(Number.NaN)).toBe('—')
    expect(count(Number.POSITIVE_INFINITY)).toBe('—')
  })
})

describe('compact', () => {
  it('không rút gọn dưới 10.000 — "9.999" vừa ngắn vừa chính xác', () => {
    expect(compact(9999)).toBe('9.999')
  })

  it('rút gọn hàng nghìn và hàng triệu', () => {
    expect(compact(12345)).toBe('12,3 N')
    expect(compact(2_500_000)).toBe('2,5 Tr')
  })
})

describe('percent', () => {
  it('nhận tỉ lệ 0..1, không nhận số phần trăm sẵn', () => {
    expect(percent(0.3421)).toBe('34,2%')
    expect(percent(1)).toBe('100,0%')
  })

  it('làm tròn theo số chữ số yêu cầu', () => {
    expect(percent(0.3421, 0)).toBe('34%')
  })
})

describe('bytes', () => {
  it('dùng bội số 1024 vì đây là kích thước tệp', () => {
    expect(bytes(1536)).toBe('1,5 KB')
    expect(bytes(900)).toBe('900 B')
  })

  it('trả về "—" cho số âm', () => {
    expect(bytes(-1)).toBe('—')
  })
})

describe('millis', () => {
  it('không làm tròn một giá trị dương thành "0 ms"', () => {
    expect(millis(0.4)).toBe('< 1 ms')
  })

  it('làm tròn về mili giây nguyên', () => {
    expect(millis(18.6)).toBe('19 ms')
    expect(millis(0)).toBe('0 ms')
  })
})

describe('dayLabel', () => {
  it('bỏ năm cho nhãn trục ngày', () => {
    expect(dayLabel('2026-08-10')).toBe('10/08')
  })

  it('trả nguyên chuỗi khi định dạng lạ', () => {
    expect(dayLabel('hom-nay')).toBe('hom-nay')
  })
})

describe('shortUrl', () => {
  it('bỏ giao thức và "www."', () => {
    expect(shortUrl('https://www.vnexpress.net/a')).toBe('vnexpress.net/a')
  })

  it('cắt ở GIỮA để phần đuôi phân biệt vẫn còn', () => {
    const long = `https://vnexpress.net/${'a'.repeat(60)}/bai-viet-quan-trong`
    const short = shortUrl(long, 40)

    expect(short.length).toBeLessThanOrEqual(40)
    expect(short).toContain('…')
    // Regex NEO ĐẦU thay cho startsWith: CodeQL cảnh báo đúng rằng
    // `x.startsWith('mot.ten.mien')` là khuôn của một phép kiểm tra URL không
    // đầy đủ. Ở đây nó chỉ là một phép so trong test, nhưng viết theo khuôn an
    // toàn thì không phải dạy công cụ bỏ qua — và khuôn đó cũng chặt hơn thật.
    expect(short).toMatch(/^vnexpress\.net\//)
    expect(short.endsWith('quan-trong')).toBe(true)
  })

  it('không đụng tới URL đã đủ ngắn', () => {
    expect(shortUrl('https://a.vn/x')).toBe('a.vn/x')
  })
})
