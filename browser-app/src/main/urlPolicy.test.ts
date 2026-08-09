import { describe, expect, it } from 'vitest'
import { HOME_URL, MAX_ZOOM, MIN_ZOOM, clampZoomFactor, resolveNavigable } from './urlPolicy'

/**
 * Bộ test cho ranh giới bảo mật của tiến trình chính.
 *
 * <p>Nhóm "PHẢI TỪ CHỐI" là nhóm quan trọng nhất và được viết TRƯỚC: nó chính
 * là bản mô tả lỗ hổng mà `urlPolicy.ts` vá. Nếu ai đó sau này nới lỏng phép
 * kiểm tra "cho tiện", những bài này đỏ ngay.
 */
describe('resolveNavigable — phải TỪ CHỐI', () => {
  it('chặn file:// (đường đọc tệp cục bộ)', () => {
    expect(resolveNavigable('file:///C:/Users/kelly/.ssh/id_rsa')).toBeNull()
    expect(resolveNavigable('file:///etc/passwd')).toBeNull()
    // Chia sẻ SMB: cùng scheme, nhưng chạm vào máy khác trong mạng nội bộ.
    expect(resolveNavigable('file://may-chu-noi-bo/o-chia-se')).toBeNull()
  })

  it('chặn scheme trần không có dấu gạch chéo', () => {
    expect(resolveNavigable('javascript:alert(1)')).toBeNull()
    expect(resolveNavigable('data:text/html,<script>alert(1)</script>')).toBeNull()
    expect(resolveNavigable('mailto:ai-do@vi-du.com')).toBeNull()
    expect(resolveNavigable('vbscript:msgbox(1)')).toBeNull()
  })

  it('chặn cả biến thể viết hoa và có khoảng trắng thừa', () => {
    expect(resolveNavigable('FILE:///C:/Windows/win.ini')).toBeNull()
    expect(resolveNavigable('  JavaScript:alert(1)  ')).toBeNull()
  })

  it('chặn các scheme nội bộ của Chromium và Electron', () => {
    expect(resolveNavigable('chrome://settings')).toBeNull()
    expect(resolveNavigable('devtools://devtools/bundled/inspector.html')).toBeNull()
    expect(resolveNavigable('blob:https://vi-du.com/abc-123')).toBeNull()
  })

  it('chặn chuỗi rỗng, chuỗi trắng và giá trị không phải chuỗi', () => {
    expect(resolveNavigable('')).toBeNull()
    expect(resolveNavigable('   ')).toBeNull()
    expect(resolveNavigable(null)).toBeNull()
    expect(resolveNavigable(undefined)).toBeNull()
  })

  it('chặn URL http/https không phân giải được thành host', () => {
    // `new URL` của WHATWG BẮT BUỘC scheme http/https phải có host, nên các
    // chuỗi dưới đây ném ngay ở bước phân giải.
    expect(resolveNavigable('http://')).toBeNull()
    expect(resolveNavigable('https://')).toBeNull()

    // Ngược lại, ba dấu gạch chéo KHÔNG phải là ca không host: WHATWG gộp
    // chúng lại và `chi-co-duong-dan` trở thành host thật. Ghim hành vi này
    // lại vì nó phản trực giác — bài test này ban đầu được viết với kỳ vọng
    // ngược, và chính nó chỉ ra rằng kỳ vọng đó sai chứ không phải mã sai.
    expect(resolveNavigable('http:///chi-co-duong-dan')).toBe('http://chi-co-duong-dan/')
  })
})

describe('resolveNavigable — phải CHO QUA', () => {
  it('giữ nguyên URL http/https đầy đủ', () => {
    expect(resolveNavigable('https://vnexpress.net/thoi-su')).toBe('https://vnexpress.net/thoi-su')
    expect(resolveNavigable('http://vi-du.com/a?b=c#d')).toBe('http://vi-du.com/a?b=c#d')
  })

  it('thêm https:// cho tên miền gõ trần', () => {
    expect(resolveNavigable('vnexpress.net')).toBe('https://vnexpress.net/')
    expect(resolveNavigable('  tuoitre.vn/the-thao  ')).toBe('https://tuoitre.vn/the-thao')
  })

  it('KHÔNG nhầm host:port thành scheme — ca dùng thường ngày nhất', () => {
    // Đây là bài test canh cho phần `(?!\d)` trong SCHEME_WITHOUT_SLASHES.
    // Bỏ lookahead đó đi thì bài này đỏ, và người phát triển mất khả năng gõ
    // địa chỉ máy chủ cục bộ của chính mình.
    expect(resolveNavigable('localhost:8080')).toBe('https://localhost:8080/')
    expect(resolveNavigable('localhost:8080/api/health')).toBe('https://localhost:8080/api/health')
    expect(resolveNavigable('127.0.0.1:5173')).toBe('https://127.0.0.1:5173/')
  })

  it('cho qua trang chủ nội bộ nguyên vẹn', () => {
    expect(resolveNavigable(HOME_URL)).toBe(HOME_URL)
    expect(resolveNavigable(`  ${HOME_URL}  `)).toBe(HOME_URL)
  })

  it('nhưng KHÔNG cho qua scheme nội bộ khác của ứng dụng', () => {
    // Chỉ đúng một địa chỉ `vnsearch://` được phép, không phải cả scheme.
    expect(resolveNavigable('vnsearch://cai-gi-do-khac')).toBeNull()
  })
})

describe('clampZoomFactor', () => {
  it('giữ nguyên giá trị nằm trong dải', () => {
    expect(clampZoomFactor(1)).toBe(1)
    expect(clampZoomFactor(1.5)).toBe(1.5)
  })

  it('ép giá trị ngoài dải về hai biên', () => {
    expect(clampZoomFactor(0)).toBe(MIN_ZOOM)
    expect(clampZoomFactor(-3)).toBe(MIN_ZOOM)
    expect(clampZoomFactor(1000)).toBe(MAX_ZOOM)
  })

  it('trả về 1 cho giá trị không phải số hữu hạn', () => {
    // Ba giá trị này đều đến được từ IPC, và `setZoomFactor` xử lý chúng theo
    // cách không xác định — `0` làm nội dung biến mất hẳn.
    expect(clampZoomFactor(Number.NaN)).toBe(1)
    expect(clampZoomFactor(Number.POSITIVE_INFINITY)).toBe(1)
    expect(clampZoomFactor('2' as unknown as number)).toBe(1)
  })
})
