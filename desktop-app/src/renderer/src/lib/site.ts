export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

export function prettyUrl(url: string): string {
  try {
    const parsed = new URL(url)
    const segments = parsed.pathname.split('/').filter(Boolean).slice(0, 3)
    return [parsed.hostname.replace(/^www\./, ''), ...segments].join(' › ')
  } catch {
    return url
  }
}

function hash32(text: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

/**
 * Bảng sắc độ cho ô đại diện trang web — MƯỜI HAI ô chọn sẵn, không phải cả
 * 360 độ của vòng tròn màu.
 *
 * Bản trước lấy `hash % 360`, nghĩa là mọi sắc độ đều có thể ra: cả vàng chanh
 * 60°, cả nâu bùn 35°, cả tím hồng 315° — và chúng nằm cạnh nhau trên cùng một
 * hàng lối tắt. Đó là lý do hàng ô ấy trông như một hộp bút chì màu chứ không
 * như một phần của ứng dụng.
 *
 * Mười hai ô dưới đây bỏ hẳn những khoảng sắc độ cho ra màu bẩn khi hạ độ
 * sáng (vàng–nâu quanh 35–70°, xanh nõn chuối quanh 90°), và bắt đầu bằng ba ô
 * xanh lá / xanh ngọc để đa số trang rơi vào đúng tông của giao diện. Vẫn đủ
 * mười hai để hai trang cạnh nhau hiếm khi trùng màu, mà không ô nào lạc lõng.
 */
const TILE_HUES = [152, 168, 186, 205, 222, 246, 266, 288, 316, 340, 6, 22]

export function siteGradient(url: string): string {
  const hue = TILE_HUES[hash32(hostOf(url)) % TILE_HUES.length]
  return `linear-gradient(135deg, hsl(${hue} 66% 54%), hsl(${(hue + 24) % 360} 70% 42%))`
}

export function siteInitial(url: string): string {
  const letter = hostOf(url)
    .replace(/[^a-z0-9]/gi, '')
    .charAt(0)
  return (letter || '?').toUpperCase()
}
