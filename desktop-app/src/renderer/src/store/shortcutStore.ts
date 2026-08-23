import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { SEED_SITES } from '../lib/seedSites'
import { normalizeUrl } from './sidePanelStore'

export interface Shortcut {
  id: string
  name: string
  url: string
}

interface ShortcutState {
  shortcuts: Shortcut[]
  add: (name: string, url: string) => boolean
  remove: (id: string) => void
}

export const useShortcutStore = create<ShortcutState>()(
  persist(
    (set, get) => ({
      shortcuts: SEED_SITES.map((site) => ({
        id: `seed-${site.url}`,
        name: site.name,
        url: site.url
      })),

      add: (name, url) => {
        const normalized = normalizeUrl(url)
        if (!normalized || get().shortcuts.some((shortcut) => shortcut.url === normalized)) {
          return false
        }
        const host = new URL(normalized).hostname.replace(/^www\./, '')
        set((state) => ({
          shortcuts: [
            ...state.shortcuts,
            { id: `sc-${Date.now()}`, name: name.trim() || host, url: normalized }
          ]
        }))
        return true
      },

      remove: (id) =>
        set((state) => ({ shortcuts: state.shortcuts.filter((shortcut) => shortcut.id !== id) }))
    }),
    { name: 'vnsearch-shortcuts' }
  )
)
