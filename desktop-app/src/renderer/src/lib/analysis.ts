/**
 * Vài phép tính rút ra TỪ số liệu thô — phần "khoa học dữ liệu" của bảng điều
 * khiển.
 *
 * VÌ SAO TÍNH Ở GIAO DIỆN CHỨ KHÔNG Ở MÁY CHỦ. Mọi hàm dưới đây chỉ ăn vào
 * những con số mà máy chủ ĐÃ gửi (bảng xếp hạng, các tổng), không cần thêm một
 * byte dữ liệu nào. Đẩy chúng lên máy chủ chỉ làm phình lược đồ phản hồi và
 * buộc phải triển khai lại backend mỗi khi muốn đổi cách nhìn một con số.
 *
 * GIỚI HẠN PHẢI BIẾT KHI ĐỌC KẾT QUẢ: các chỉ số tính trên BẢNG XẾP HẠNG (top
 * 10), không phải trên toàn bộ phân bố. Chúng mô tả *phần đầu* của phân bố —
 * đúng phần mà bảng điều khiển hiển thị — chứ không phải toàn bộ lưu lượng.
 * Nói rõ ở đây vì một con số "entropy" đứng trơ trọi trên màn hình rất dễ bị
 * đọc như thể nó nói về tất cả.
 */

/**
 * Entropy Shannon (đơn vị bit) của một phân bố tần suất.
 *
 *   H = −Σ pᵢ · log₂(pᵢ)
 *
 * Ý NGHĨA Ở ĐÂY: mức ĐA DẠNG của các truy vấn. H thấp nghĩa là hầu hết lượt
 * tìm dồn vào vài từ khoá; H cao nghĩa là người dùng hỏi tản mát. Hai tình
 * huống này đòi hai hướng cải thiện trái ngược nhau — một bên nên tối ưu cache
 * và chất lượng cho nhóm truy vấn nóng, bên kia nên lo độ phủ của chỉ mục.
 *
 * Quy ước 0·log₂0 = 0 (giới hạn toán học), nên mục có tần suất 0 không đóng góp.
 */
export function shannonEntropy(counts: number[]): number {
  const total = counts.reduce((sum, value) => sum + Math.max(0, value), 0)
  if (total <= 0) {
    return 0
  }
  let entropy = 0
  for (const value of counts) {
    if (value > 0) {
      const p = value / total
      entropy -= p * Math.log2(p)
    }
  }
  return entropy
}

/**
 * Entropy chuẩn hoá về khoảng 0..1 bằng cách chia cho log₂(số mục).
 *
 * Vì sao cần: entropy thô không so sánh được giữa hai bảng có số dòng khác
 * nhau — một bảng 10 dòng có trần 3,32 bit còn bảng 50 dòng có trần 5,64 bit,
 * nên "2,9 bit" là rất tập trung ở bảng này và rất tản mát ở bảng kia. Chia
 * cho trần thì `0` = tất cả dồn vào một mục, `1` = chia đều tuyệt đối.
 */
export function normalizedEntropy(counts: number[]): number {
  const nonZero = counts.filter((value) => value > 0).length
  if (nonZero <= 1) {
    return 0
  }
  return shannonEntropy(counts) / Math.log2(nonZero)
}

/**
 * Tỉ lệ mà `k` mục lớn nhất chiếm trong tổng — độ TẬP TRUNG của phần đầu.
 *
 * Phân bố truy vấn của máy tìm kiếm thường tuân theo quy luật Zipf: một nhúm
 * truy vấn chiếm phần lớn lưu lượng. Con số này đo trực tiếp cái nhúm đó, và
 * nó là căn cứ để chọn kích thước cache LRU: đầu càng nặng thì một cache nhỏ
 * càng hiệu quả.
 */
export function headShare(counts: number[], k = 3): number {
  const total = counts.reduce((sum, value) => sum + Math.max(0, value), 0)
  if (total <= 0) {
    return 0
  }
  const head = [...counts]
    .sort((a, b) => b - a)
    .slice(0, k)
    .reduce((sum, value) => sum + value, 0)
  return head / total
}

/**
 * Tỉ lệ phần web đã BIẾT mà crawler đã thật sự tải về.
 *
 *   documents / distinctLinkTargets
 *
 * Mẫu số là số đích liên kết phân biệt mà corpus nhìn thấy — tức "biên giới"
 * đã phát hiện. Tỉ lệ thấp không phải lỗi: nó là trạng thái bình thường của
 * một lần crawl có giới hạn `maxPages`, và nó cho biết còn bao nhiêu đường
 * đang chờ trong hàng đợi. Tỉ lệ tiến gần 1 nghĩa là crawler đã vét gần hết
 * những gì nó nhìn thấy — lúc đó muốn đi xa hơn thì phải thêm seed mới.
 */
export function coverageRatio(documents: number, distinctLinkTargets: number): number {
  if (distinctLinkTargets <= 0) {
    return 0
  }
  return Math.min(1, documents / distinctLinkTargets)
}

/** Tỉ lệ an toàn, không bao giờ trả về `NaN` hay `Infinity`. */
export function ratio(part: number, whole: number): number {
  if (!Number.isFinite(part) || !Number.isFinite(whole) || whole <= 0) {
    return 0
  }
  return part / whole
}

/**
 * Gộp phần đuôi của một phân bố thành mục "khác".
 *
 * Bảng màu chỉ có 4 ô đã qua kiểm định, và ô thứ 5 tự chế sẽ không phân biệt
 * được với một ô sẵn có dưới mắt người mù màu. Nên phần đuôi được GỘP chứ
 * không được cấp thêm màu.
 */
export function foldTail<T extends { label: string; count: number }>(
  items: T[],
  keep: number,
  otherLabel = 'khác'
): { label: string; value: number }[] {
  const sorted = [...items].sort((a, b) => b.count - a.count)
  const head = sorted.slice(0, keep).map((item) => ({ label: item.label, value: item.count }))
  const tail = sorted.slice(keep).reduce((sum, item) => sum + item.count, 0)
  return tail > 0 ? [...head, { label: otherLabel, value: tail }] : head
}
