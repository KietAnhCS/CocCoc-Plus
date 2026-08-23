/**
 * Định dạng số cho bảng điều khiển.
 *
 * VÌ SAO GOM MỘT CHỖ. Cùng một con số xuất hiện ở ô KPI, ở nhãn cột và ở bảng
 * số; ba lần gọi `toLocaleString` rải rác là ba cơ hội để một chỗ hiện
 * `1234.5678` còn chỗ kia hiện `1.234,57`. Gom lại thì cũng gom luôn được các
 * bài kiểm thử — đây là phần duy nhất của bảng điều khiển kiểm được mà không
 * cần dựng DOM.
 */

const VI = 'vi-VN'

/** `1234` → `1.234`. Số nguyên, dấu phân cách nghìn kiểu Việt Nam. */
export function count(value: number): string {
  if (!Number.isFinite(value)) {
    return '—'
  }
  return Math.round(value).toLocaleString(VI)
}

/**
 * Dạng gọn cho ô KPI: `12345` → `12,3 N`, `2500000` → `2,5 Tr`.
 *
 * Chỉ rút gọn từ 10.000 trở lên. Dưới ngưỡng đó, `9.999` vừa ngắn vừa chính
 * xác, còn `10,0 N` thì mất thông tin mà không tiết kiệm được chỗ nào.
 */
export function compact(value: number): string {
  if (!Number.isFinite(value)) {
    return '—'
  }
  const absolute = Math.abs(value)
  if (absolute >= 1_000_000) {
    return `${(value / 1_000_000).toLocaleString(VI, { maximumFractionDigits: 1 })} Tr`
  }
  if (absolute >= 10_000) {
    return `${(value / 1_000).toLocaleString(VI, { maximumFractionDigits: 1 })} N`
  }
  return count(value)
}

/** `0.3421` → `34,2%`. Nhận tỉ lệ 0..1, KHÔNG nhận số phần trăm sẵn. */
export function percent(ratio: number, digits = 1): string {
  if (!Number.isFinite(ratio)) {
    return '—'
  }
  return `${(ratio * 100).toLocaleString(VI, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  })}%`
}

/** `1536` → `1,5 KB`. Dùng bội số 1024 vì đây là kích thước tệp trên đĩa. */
export function bytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) {
    return '—'
  }
  const units = ['B', 'KB', 'MB', 'GB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit++
  }
  const digits = unit === 0 ? 0 : 1
  return `${size.toLocaleString(VI, { maximumFractionDigits: digits })} ${units[unit]}`
}

/** `18.4` → `18 ms`; dưới 1 ms thì `< 1 ms` chứ không phải `0 ms`. */
export function millis(value: number): string {
  if (!Number.isFinite(value)) {
    return '—'
  }
  if (value > 0 && value < 1) {
    return '< 1 ms'
  }
  return `${Math.round(value).toLocaleString(VI)} ms`
}

/**
 * `2026-08-10T03:20:00Z` → `10/08/2026 10:20`.
 *
 * Chuỗi rỗng cho đầu vào rỗng, không phải `Invalid Date`: bảng điều khiển hiện
 * mốc thời gian của corpus, và một corpus chưa crawl thì thật sự không có mốc
 * nào — hiện `Invalid Date` là báo lỗi cho một chuyện không phải lỗi.
 */
export function dateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—'
  }
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) {
    return '—'
  }
  return parsed.toLocaleString(VI, {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

/** `2026-08-10` → `10/08`. Nhãn trục ngày, bỏ năm cho đỡ chật. */
export function dayLabel(isoDate: string): string {
  const parts = isoDate.split('-')
  return parts.length === 3 ? `${parts[2]}/${parts[1]}` : isoDate
}

/**
 * Rút gọn URL dài để vừa một dòng bảng.
 *
 * Cắt ở GIỮA chứ không ở cuối: phần đuôi của một URL (tên bài viết) thường là
 * phần duy nhất phân biệt được nó với các URL khác cùng trang, nên cắt đuôi
 * biến mười dòng khác nhau thành mười dòng trông y hệt.
 */
export function shortUrl(url: string, maxChars = 64): string {
  const stripped = url.replace(/^https?:\/\//, '').replace(/^www\./, '')
  if (stripped.length <= maxChars) {
    return stripped
  }
  const head = Math.ceil((maxChars - 1) / 2)
  const tail = Math.floor((maxChars - 1) / 2)
  return `${stripped.slice(0, head)}…${stripped.slice(stripped.length - tail)}`
}
