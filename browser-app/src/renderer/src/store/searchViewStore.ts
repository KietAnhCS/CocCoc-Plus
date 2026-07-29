import { create } from 'zustand'

/**
 * Trang thai nho, chia se giua AddressBar (PHASE 8) va SearchHomePage/
 * SearchResultList (PHASE 9): khi nguoi dung go MOT TU KHOA (khong phai
 * URL) vao dia chi, ta luu lai truy van o day va dieu huong tab ve
 * HOME_URL de chrome view tu render SearchResultList thay vi co gang tai
 * mot URL khong hop le.
 */
interface SearchViewState {
  query: string | null
  setQuery: (query: string | null) => void
}

export const useSearchViewStore = create<SearchViewState>((set) => ({
  query: null,
  setQuery: (query) => set({ query })
}))
