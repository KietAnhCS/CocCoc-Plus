import { useEffect, useState, type JSX, type ReactNode } from 'react'
import { useDownloadStore, type MucTaiXuong } from '../store/downloadStore'

const THOI_GIAN_NHAY_MS = 4000

function demDaXong(items: MucTaiXuong[]): number {
  return items.filter((item) => item.state === 'COMPLETED').length
}

interface NutTaiXuongProps {
  lopNut: string
  nhan: string
  moTa: string
  active: boolean
  onClick: () => void
  thongBao?: boolean
  children: ReactNode
}

/**
 * Nút mở bảng Tải xuống, kèm huy hiệu số lượt đang tải.
 *
 * <p>Lượt tải vừa xong được nhận ra qua `subscribe` chứ không qua một
 * `useEffect` phụ thuộc số lượng: con số quay về y như cũ khi người dùng xoá
 * bớt mục, nên dựa vào nó sẽ nháy nhầm.
 */
export function NutTaiXuong({
  lopNut,
  nhan,
  moTa,
  active,
  onClick,
  thongBao = false,
  children
}: NutTaiXuongProps): JSX.Element {
  const dangTai = useDownloadStore((state) => state.dangTai)
  const [vuaXong, setVuaXong] = useState(false)

  useEffect(() => {
    let truoc = demDaXong(useDownloadStore.getState().items)
    let hen: ReturnType<typeof setTimeout> | undefined

    const huyDangKy = useDownloadStore.subscribe((state) => {
      const sau = demDaXong(state.items)
      if (sau > truoc) {
        setVuaXong(true)
        clearTimeout(hen)
        hen = setTimeout(() => setVuaXong(false), THOI_GIAN_NHAY_MS)
      }
      truoc = sau
    })

    return () => {
      huyDangKy()
      clearTimeout(hen)
    }
  }, [])

  const dangTaiText = dangTai > 0 ? `${nhan} — đang tải ${dangTai} tệp` : nhan

  return (
    <div className="relative shrink-0">
      <button
        onClick={onClick}
        className={
          lopNut +
          ' ' +
          (active ? 'bg-raised text-ink ' : '') +
          (vuaXong ? 'text-brand ring-2 ring-brand/60' : '')
        }
        aria-label={dangTaiText}
        title={dangTai > 0 ? `${moTa} — đang tải ${dangTai} tệp` : moTa}
      >
        {children}
      </button>

      {dangTai > 0 && (
        <span
          className="pointer-events-none absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center
                     justify-center rounded-full bg-brand px-1 text-[10px] font-bold text-white
                     shadow-tab"
          aria-hidden="true"
        >
          {dangTai > 9 ? '9+' : dangTai}
        </span>
      )}

      {thongBao && (
        <span className="sr-only" aria-live="polite">
          {dangTai > 0 ? `Đang tải ${dangTai} tệp` : 'Không có lượt tải nào đang chạy'}
        </span>
      )}
    </div>
  )
}
