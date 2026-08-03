import { create } from 'zustand'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'vnsearch.theme'

/**
 * Đọc lựa chọn đã lưu; chưa có thì dùng nền tối.
 *
 * Cố tình KHÔNG theo cài đặt của hệ điều hành: vỏ trình duyệt được thiết kế
 * theo tông tối (thanh địa chỉ chìm, cột biểu tượng bên phải, trang chủ có
 * ảnh nền lớn) nên nền sáng chỉ là phương án phụ. Người dùng vẫn đổi được
 * bằng nút trong menu, và lựa chọn đó được nhớ lại.
 */
function initialTheme(): Theme {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'light' ? 'light' : 'dark'
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
