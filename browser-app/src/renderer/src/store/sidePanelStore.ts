import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { RAIL_APPS } from '../lib/apps'

/**
 * Trạng thái cột biểu tượng dọc bên phải và bảng trượt ra từ mép phải.
 *
 * Bảng NEO (dock) chứ không phủ lên trang: khi mở, main process thu hẹp
 * WebContentsView của trang ngoài đúng {@link PANEL_WIDTH} pixel (xem
 * tabManager.setPanelWidth). Nếu chỉ phủ bằng CSS thì bảng sẽ bị trang ngoài
 * che mất hoàn toàn, vì view của trang luôn nằm trên vỏ trình duyệt.
 */

/** Bề ngang bảng bên (px). */
export const PANEL_WIDTH = 340

/** Bề ngang cột biểu tượng. PHẢI khớp với SIDE_RAIL_WIDTH ở main/tabManager.ts. */
export const SIDE_RAIL_WIDTH = 48

export type PanelKind =
  /** "Thêm trang web vào thanh bên" */
  | 'add-site'
  /** Khung Hỏi AI */
  | 'ai'
  /** Danh sách tải xuống */
  | 'downloads'
  /** Toàn bộ dấu trang đã lưu */
  | 'bookmarks'
  /** Một ứng dụng đã ghim trên cột */
  | 'app'

/** Một ô trên cột: hoặc trỏ tới danh mục dựng sẵn, hoặc là trang người dùng tự thêm. */
export interface RailItem {
  id: string
  /** Có `url` nghĩa là trang tự thêm; không có thì tra id trong lib/apps.tsx. */
  url?: string
  name?: string
}

interface SidePanelState {
  open: PanelKind | null
  /** id ô đang mở, chỉ dùng khi open === 'app'. */
  activeItemId: string | null
  /** Ghim thì bảng không tự đóng khi bấm ra ngoài. */
  pinned: boolean
  items: RailItem[]

  openPanel: (kind: Exclude<PanelKind, 'app'>) => void
  openApp: (itemId: string) => void
  closePanel: () => void
  togglePanel: (kind: Exclude<PanelKind, 'app'>) => void
  setPinned: (pinned: boolean) => void

  addApp: (appId: string) => void
  addSite: (url: string) => void
  removeItem: (itemId: string) => void
}

/** Chuẩn hoá chuỗi người dùng gõ thành URL đầy đủ; null nếu không dùng được. */
export function normalizeUrl(raw: string): string | null {
  const text = raw.trim()
  if (!text) {
    return null
  }
  const withScheme = /^https?:\/\//i.test(text) ? text : `https://${text}`
  try {
    const parsed = new URL(withScheme)
    // Phải có ít nhất một dấu chấm trong host, nếu không "abc" cũng lọt qua.
    return parsed.hostname.includes('.') ? parsed.toString() : null
  } catch {
    return null
  }
}

export const useSidePanelStore = create<SidePanelState>()(
  persist(
    (set, get) => ({
      open: null,
      activeItemId: null,
      pinned: false,
      items: RAIL_APPS.map((app) => ({ id: app.id })),

      openPanel: (kind) => set({ open: kind, activeItemId: null }),

      openApp: (itemId) => set({ open: 'app', activeItemId: itemId }),

      closePanel: () => set({ open: null, activeItemId: null }),

      togglePanel: (kind) =>
        set((state) =>
          state.open === kind ? { open: null, activeItemId: null } : { open: kind, activeItemId: null }
        ),

      setPinned: (pinned) => set({ pinned }),

      addApp: (appId) => {
        if (get().items.some((i) => i.id === appId)) {
          return
        }
        set((state) => ({ items: [...state.items, { id: appId }] }))
      },

      addSite: (url) => {
        const normalized = normalizeUrl(url)
        if (!normalized) {
          return
        }
        const host = new URL(normalized).hostname.replace(/^www\./, '')
        if (get().items.some((i) => i.url === normalized)) {
          return
        }
        set((state) => ({
          items: [...state.items, { id: `site-${Date.now()}`, url: normalized, name: host }]
        }))
      },

      removeItem: (itemId) => {
        set((state) => ({
          items: state.items.filter((i) => i.id !== itemId),
          // Đang mở đúng ô vừa gỡ thì đóng bảng lại cho khỏi trơ.
          ...(state.activeItemId === itemId ? { open: null, activeItemId: null } : {})
        }))
      }
    }),
    {
      name: 'vnsearch-side-panel',
      // Bảng đang mở hay không là chuyện của phiên làm việc, không nên nhớ
      // qua lần mở sau; chỉ nhớ những ô người dùng đã ghim.
      partialize: (state) => ({ items: state.items, pinned: state.pinned })
    }
  )
)
