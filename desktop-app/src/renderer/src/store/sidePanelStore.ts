import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { RAIL_APPS } from '../lib/apps'

export const PANEL_WIDTH = 340

export const SIDE_RAIL_WIDTH = 48

export type PanelKind = 'add-site' | 'ai' | 'downloads' | 'bookmarks' | 'app'

export interface RailItem {
  id: string
  url?: string
  name?: string
}

interface SidePanelState {
  open: PanelKind | null
  activeItemId: string | null
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

export function normalizeUrl(raw: string): string | null {
  const text = raw.trim()
  if (!text) {
    return null
  }
  const withScheme = /^https?:\/\//i.test(text) ? text : `https://${text}`
  try {
    const parsed = new URL(withScheme)
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
          state.open === kind
            ? { open: null, activeItemId: null }
            : { open: kind, activeItemId: null }
        ),

      setPinned: (pinned) => set({ pinned }),

      addApp: (appId) => {
        if (get().items.some((item) => item.id === appId)) {
          return
        }
        set((state) => ({ items: [...state.items, { id: appId }] }))
      },

      addSite: (url) => {
        const normalized = normalizeUrl(url)
        if (!normalized || get().items.some((item) => item.url === normalized)) {
          return
        }
        const host = new URL(normalized).hostname.replace(/^www\./, '')
        set((state) => ({
          items: [...state.items, { id: `site-${Date.now()}`, url: normalized, name: host }]
        }))
      },

      removeItem: (itemId) => {
        set((state) => ({
          items: state.items.filter((item) => item.id !== itemId),
          ...(state.activeItemId === itemId ? { open: null, activeItemId: null } : {})
        }))
      }
    }),
    {
      name: 'vnsearch-side-panel',
      partialize: (state) => ({ items: state.items, pinned: state.pinned })
    }
  )
)
