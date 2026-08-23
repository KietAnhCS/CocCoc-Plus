import { useEffect, type JSX, type ReactNode } from 'react'
import { useOverlayStore } from '../store/overlayStore'

interface PopoverProps {
  open: boolean
  onClose: () => void
  align?: 'left' | 'right'
  width?: number
  children: ReactNode
  label?: string
}

function Popover({
  open,
  onClose,
  align = 'right',
  width = 260,
  children,
  label
}: PopoverProps): JSX.Element | null {
  const acquire = useOverlayStore((state) => state.acquire)
  const release = useOverlayStore((state) => state.release)

  useEffect(() => {
    if (!open) {
      return undefined
    }
    acquire()

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.stopPropagation()
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
