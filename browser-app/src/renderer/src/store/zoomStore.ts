import { create } from 'zustand'
import { useTabStore } from './tabStore'

/**
 * Mức thu phóng của trang đang xem, điều khiển từ hàng "Thu phóng" trong
 * menu. Nấc phóng lấy đúng bộ của Chrome/Cốc Cốc để bấm +/- cho cảm giác
 * quen tay.
 *
 * Chỉ có tác dụng với TRANG NGOÀI: trang chủ và trang kết quả do chính vỏ
 * trình duyệt vẽ ra, phóng to chúng sẽ phóng luôn cả thanh tab lẫn ô địa chỉ.
 */
const STEPS = [0.25, 0.33, 0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3]

const DEFAULT_INDEX = STEPS.indexOf(1)

interface ZoomState {
  factor: number
  zoomIn: () => void
  zoomOut: () => void
  reset: () => void
}

function apply(factor: number): void {
  const { activeTabId } = useTabStore.getState()
  if (activeTabId) {
    window.browser.setZoom(activeTabId, factor)
  }
}

export const useZoomStore = create<ZoomState>((set, get) => ({
  factor: 1,

  zoomIn: () => {
    const next = STEPS[Math.min(STEPS.indexOf(get().factor) + 1, STEPS.length - 1)]
    apply(next)
    set({ factor: next })
  },

  zoomOut: () => {
    const next = STEPS[Math.max(STEPS.indexOf(get().factor) - 1, 0)]
    apply(next)
    set({ factor: next })
  },

  reset: () => {
    const next = STEPS[DEFAULT_INDEX]
    apply(next)
    set({ factor: next })
  }
}))
