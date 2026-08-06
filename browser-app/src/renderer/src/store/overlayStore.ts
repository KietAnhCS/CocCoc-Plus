import { create } from 'zustand'

interface OverlayState {
  count: number
  acquire: () => void
  release: () => void
}

export const useOverlayStore = create<OverlayState>((set) => ({
  count: 0,
  acquire: () => set((state) => ({ count: state.count + 1 })),
  release: () => set((state) => ({ count: Math.max(0, state.count - 1) }))
}))
