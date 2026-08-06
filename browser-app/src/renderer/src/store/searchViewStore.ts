import { create } from 'zustand'

interface SearchViewState {
  query: string | null
  runSearch: (query: string) => void
  clear: () => void
}

export const useSearchViewStore = create<SearchViewState>((set) => ({
  query: null,
  runSearch: (query) => {
    const trimmed = query.trim()
    set({ query: trimmed || null })
  },
  clear: () => set({ query: null })
}))
