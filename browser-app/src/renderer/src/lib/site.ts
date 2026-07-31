/**
 * Tiện ích hiển thị cho URL: tên host, đường dẫn dạng "vụn bánh mì", và
 * "favicon giả".
 *
 * Vì sao favicon giả? CSP của renderer là `default-src 'self'` nên không
 * tải được ảnh favicon từ máy chủ ngoài. Thay vì bỏ trống, ta sinh một ô
 * màu + chữ cái đầu của host, màu suy ra từ chính chuỗi host — cùng một
 * trang luôn cho ra cùng một màu, nên mắt vẫn nhận ra nguồn tin ngay.
 */

/** Host đã bỏ tiền tố "www.". Trả lại chính chuỗi vào nếu không phân tích được. */
export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/** "https://vnexpress.net/a/b-123.html" -> "vnexpress.net › a › b-123.html" */
export function prettyUrl(url: string): string {
  try {
    const parsed = new URL(url)
    const segments = parsed.pathname.split('/').filter(Boolean)
    const shown = segments.slice(0, 3)
    return [parsed.hostname.replace(/^www\./, ''), ...shown].join(' › ')
  } catch {
    return url
  }
}

/** Băm chuỗi kiểu FNV-1a 32 bit — nhỏ, ổn định, đủ tản đều cho việc chọn màu. */
function hash32(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** Nền gradient ổn định theo host, dùng cho ô favicon giả. */
export function siteGradient(url: string): string {
  const hue = hash32(hostOf(url)) % 360
  return `linear-gradient(135deg, hsl(${hue} 72% 52%), hsl(${(hue + 38) % 360} 74% 44%))`
}

/** Chữ cái đại diện cho trang (viết hoa). */
export function siteInitial(url: string): string {
  const host = hostOf(url)
  const letter = host.replace(/[^a-z0-9]/gi, '').charAt(0)
  return (letter || '?').toUpperCase()
}
