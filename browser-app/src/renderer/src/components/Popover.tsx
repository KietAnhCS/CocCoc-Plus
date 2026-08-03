import { useEffect } from 'react'
import { useOverlayStore } from '../store/overlayStore'

interface PopoverProps {
  open: boolean
  onClose: () => void
  /** Neo mép phải (mặc định) hay mép trái của nút bấm. */
  align?: 'left' | 'right'
  width?: number
  children: React.ReactNode
  /** Nhãn cho trình đọc màn hình. */
  label?: string
}

/**
 * Bảng nhỏ thả xuống từ một nút trên thanh công cụ. Nút bọc ngoài PHẢI có
 * `position: relative` vì bảng định vị tuyệt đối theo nút đó.
 *
 * Mở bảng cũng đồng thời "giữ chỗ" ở overlayStore để main process tạm gỡ
 * trang ngoài xuống — nếu không, phần bảng đổ xuống dưới thanh công cụ sẽ
 * bị trang ngoài che mất (xem chú thích trong store/overlayStore.ts).
 */
function Popover({
  open,
  onClose,
  align = 'right',
  width = 260,
  children,
  label
}: PopoverProps): JSX.Element | null {
  const acquire = useOverlayStore((s) => s.acquire)
  const release = useOverlayStore((s) => s.release)

  useEffect(() => {
    if (!open) {
      return undefined
    }
    acquire()

    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
      }
    }
    window.addEventListener('keydown', onKeyDown)

    return () => {
      window.removeEventListener('keydown', onKeyDown)
      release()
    }
  }, [open, acquire, release, onClose])

  if (!open) {
    return null
  }

  return (
    <>
      {/* Lớp bắt cú bấm ra ngoài. Trong suốt, phủ kín cửa sổ. */}
      <div className="fixed inset-0 z-40" onMouseDown={onClose} aria-hidden="true" />

      <div
        role="dialog"
        aria-label={label}
        className={
          'absolute top-[calc(100%+6px)] z-50 animate-scale-in rounded-xl border border-line ' +
          'bg-surface p-1.5 shadow-pop ' +
          (align === 'right' ? 'right-0' : 'left-0')
        }
        style={{ width }}
      >
        {children}
      </div>
    </>
  )
}

export default Popover
