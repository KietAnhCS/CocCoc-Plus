import { create } from 'zustand'

/**
 * Đếm số lớp phủ (menu, popover) đang mở trong vỏ trình duyệt.
 *
 * Vì sao cần đếm? Menu của vỏ đổ dài xuống quá vùng thanh công cụ, mà
 * WebContentsView của trang ngoài luôn nằm TRÊN chromeView nên phần đổ xuống
 * đó sẽ bị trang che mất. App.tsx theo dõi số đếm này và báo xuống main
 * process (`browser:setOverlay`) để tạm gỡ trang ngoài khỏi cây view trong
 * lúc còn lớp phủ — trang không tải lại, chỉ tạm ẩn.
 *
 * Dùng SỐ ĐẾM chứ không phải cờ đúng/sai vì có thể mở chồng (ví dụ menu
 * chính mở panel con): đóng cái trong cùng không được phép làm lộ trang ra
 * khi cái ngoài vẫn đang mở.
 */
interface OverlayState {
  count: number
  acquire: () => void
  release: () => void
}

export const useOverlayStore = create<OverlayState>((set) => ({
  count: 0,
  acquire: () => set((s) => ({ count: s.count + 1 })),
  release: () => set((s) => ({ count: Math.max(0, s.count - 1) }))
}))
