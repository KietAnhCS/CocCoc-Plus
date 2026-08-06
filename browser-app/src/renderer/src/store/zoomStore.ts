import { create } from 'zustand'
import { useTabStore } from './tabStore'

const STEPS = [0.25, 0.33, 0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3]
const DEFAULT_FACTOR = 1

interface ZoomState {
  factors: Record<string, number>
  zoomIn: () => void
  zoomOut: () => void
  reset: () => void
}

function stepIndex(factor: number): number {
  const index = STEPS.indexOf(factor)
  return index >= 0 ? index : STEPS.indexOf(DEFAULT_FACTOR)
}

export function zoomFactorOf(factors: Record<string, number>, tabId: string | null): number {
  return tabId ? (factors[tabId] ?? DEFAULT_FACTOR) : DEFAULT_FACTOR
}

export const useZoomStore = create<ZoomState>((set, get) => {
  function applyStep(move: (index: number) => number): void {
    const { activeTabId } = useTabStore.getState()
    if (!activeTabId) {
      return
    }
    const current = zoomFactorOf(get().factors, activeTabId)
    const next = STEPS[Math.min(Math.max(move(stepIndex(current)), 0), STEPS.length - 1)]
    window.browser.setZoom(activeTabId, next)
    set((state) => ({ factors: { ...state.factors, [activeTabId]: next } }))
  }

  return {
    factors: {},
    zoomIn: () => applyStep((index) => index + 1),
    zoomOut: () => applyStep((index) => index - 1),
    reset: () => applyStep(() => stepIndex(DEFAULT_FACTOR))
  }
})
