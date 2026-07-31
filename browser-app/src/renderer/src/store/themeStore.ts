import { create } from 'zustand'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'vnsearch.theme'

/** Đọc lựa chọn đã lưu; chưa có thì theo cài đặt sáng/tối của hệ điều hành. */
function initialTheme(): Theme {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'light' || saved === 'dark') {
    return saved
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** Bảng màu đổi bằng class `dark` trên <html> — xem index.css. */
function apply(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.style.colorScheme = theme
}

interface ThemeState {
  theme: Theme
  setTheme: (theme: Theme) => void
  toggleTheme: () => void
}

export const useThemeStore = create<ThemeState>((set, get) => {
  const theme = initialTheme()
  apply(theme)

  return {
    theme,
    setTheme: (next) => {
      localStorage.setItem(STORAGE_KEY, next)
      apply(next)
      set({ theme: next })
    },
    toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark')
  }
})
