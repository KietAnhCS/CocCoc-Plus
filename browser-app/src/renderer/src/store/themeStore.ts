import { create } from 'zustand'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'vnsearch.theme'

function initialTheme(): Theme {
  return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark'
}

function applyTheme(theme: Theme): void {
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
  applyTheme(theme)

  return {
    theme,
    setTheme: (next) => {
      localStorage.setItem(STORAGE_KEY, next)
      applyTheme(next)
      set({ theme: next })
    },
    toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark')
  }
})
