import { useEffect, useRef, useState } from 'react'

/**
 * Kết quả của một lượt tải, kèm khoá của yêu cầu sinh ra nó.
 *
 * Có `key` vì trạng thái "đang tải" được SUY RA từ việc kết quả đã về có mang
 * đúng khoá đang cần hay không, chứ không phải một biến `loading` riêng. Đó là
 * cách `SearchResultList` và `FootballPanel` đã làm, và không chỉ để cho gọn:
 * đặt `setLoading(true)` ngay trong thân effect là thứ quy tắc
 * `react-hooks/set-state-in-effect` chặn, vì nó ép thêm một lượt render trước
 * cả khi request kịp rời máy.
 */
interface Outcome<T> {
  key: string
  data: T | null
  failed: boolean
}

export interface Resource<T> {
  /** `null` khi lô dữ liệu đang cần chưa về. */
  data: T | null
  loading: boolean
  /** Không gọi được service — khác hẳn với "gọi được nhưng rỗng". */
  failed: boolean
  /** Tải lại cùng một khoá, dùng cho nút "Thử lại". */
  reload: () => void
}

/**
 * Tải một tài nguyên theo khoá, huỷ khi khoá đổi hoặc component rời màn hình.
 *
 * `key` phải chứa MỌI tham số của yêu cầu. Nhờ vậy đổi mùa giải, đổi từ khoá
 * hay đổi giải đấu đều tự sinh ra một lượt tải mới mà không cần thêm một mảng
 * phụ thuộc thủ công nào — và quan trọng hơn, kết quả của yêu cầu cũ về muộn
 * cũng không ghi đè lên yêu cầu mới, vì khoá của nó đã lỗi thời.
 *
 * `load` được giữ trong một ref chứ không nằm trong mảng phụ thuộc: nó gần như
 * luôn là một hàm mũi tên dựng lại ở mỗi lượt render, nên để nó vào mảng phụ
 * thuộc sẽ bắn ra một request mới sau mỗi lần vẽ lại — kể cả khi không có gì
 * thay đổi. Với một service có hạn mức 100 lượt gọi mỗi ngày thì đó không phải
 * một điểm chưa tối ưu, mà là một cái vòi rò.
 */
export function useResource<T>(key: string, load: () => Promise<T>): Resource<T> {
  const [outcome, setOutcome] = useState<Outcome<T> | null>(null)
  const [attempt, setAttempt] = useState(0)

  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  })

  // Số lần bấm "Thử lại" nằm TRONG khoá, không nằm cạnh nó: có vậy một lượt
  // tải lại mới đưa màn hình về trạng thái đang tải. Để ngoài thì nút bấm
  // trông như không có tác dụng gì cho tới lúc dữ liệu mới về.
  const attemptKey = `${key}#${attempt}`

  useEffect(() => {
    let cancelled = false

    void loadRef
      .current()
      .then((data) => {
        if (!cancelled) {
          setOutcome({ key: attemptKey, data, failed: false })
        }
      })
      .catch(() => {
        if (!cancelled) {
          setOutcome({ key: attemptKey, data: null, failed: true })
        }
      })

    return () => {
      cancelled = true
    }
  }, [attemptKey])

  const settled = outcome?.key === attemptKey ? outcome : null

  return {
    data: settled?.data ?? null,
    loading: settled === null,
    failed: settled?.failed ?? false,
    reload: () => setAttempt((value) => value + 1)
  }
}
