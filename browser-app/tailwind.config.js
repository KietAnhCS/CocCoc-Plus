/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: ['./src/renderer/index.html', './src/renderer/src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      // Màu ngữ nghĩa (semantic) trỏ tới biến CSS khai báo trong index.css.
      // Nhờ vậy chế độ tối chỉ cần đổi một class trên <html>, không phải rải
      // `dark:` khắp mọi component.
      colors: {
        chrome: 'rgb(var(--c-chrome) / <alpha-value>)',
        surface: 'rgb(var(--c-surface) / <alpha-value>)',
        raised: 'rgb(var(--c-raised) / <alpha-value>)',
        omni: 'rgb(var(--c-omni) / <alpha-value>)',
        bookmarks: 'rgb(var(--c-bookmarks) / <alpha-value>)',
        line: 'rgb(var(--c-line) / <alpha-value>)',
        ink: 'rgb(var(--c-ink) / <alpha-value>)',
        muted: 'rgb(var(--c-muted) / <alpha-value>)',
        faint: 'rgb(var(--c-faint) / <alpha-value>)',
        brand: 'rgb(var(--c-brand) / <alpha-value>)',
        'brand-soft': 'rgb(var(--c-brand-soft) / <alpha-value>)',
        link: 'rgb(var(--c-link) / <alpha-value>)',
        visited: 'rgb(var(--c-visited) / <alpha-value>)',
        success: 'rgb(var(--c-success) / <alpha-value>)',
        warn: 'rgb(var(--c-warn) / <alpha-value>)',
        danger: 'rgb(var(--c-danger) / <alpha-value>)'
      },
      fontFamily: {
        sans: [
          'Inter',
          'Segoe UI Variable Text',
          'Segoe UI',
          'system-ui',
          'Roboto',
          'Helvetica Neue',
          'Arial',
          'sans-serif'
        ],
        display: [
          'Segoe UI Variable Display',
          'Segoe UI',
          'system-ui',
          'Inter',
          'Helvetica Neue',
          'sans-serif'
        ]
      },
      boxShadow: {
        tab: '0 1px 3px rgb(0 0 0 / 0.10), 0 0 0 0.5px rgb(0 0 0 / 0.04)',
        omni: '0 1px 2px rgb(0 0 0 / 0.06), 0 6px 20px -8px rgb(0 0 0 / 0.18)',
        pop: '0 10px 38px -10px rgb(0 0 0 / 0.35), 0 0 0 0.5px rgb(0 0 0 / 0.06)',
        card: '0 1px 2px rgb(0 0 0 / 0.05), 0 8px 24px -16px rgb(0 0 0 / 0.30)'
      },
      keyframes: {
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(6px)' },
          to: { opacity: '1', transform: 'translateY(0)' }
        },
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' }
        },
        'scale-in': {
          from: { opacity: '0', transform: 'translateY(-4px) scale(0.985)' },
          to: { opacity: '1', transform: 'translateY(0) scale(1)' }
        },
        shimmer: {
          '100%': { transform: 'translateX(100%)' }
        },
        'aurora-drift': {
          '0%, 100%': { transform: 'translate3d(0,0,0) scale(1)' },
          '50%': { transform: 'translate3d(3%, -4%, 0) scale(1.12)' }
        }
      },
      animation: {
        'fade-up': 'fade-up 0.32s cubic-bezier(0.22, 1, 0.36, 1) both',
        'fade-in': 'fade-in 0.25s ease-out both',
        'scale-in': 'scale-in 0.14s cubic-bezier(0.22, 1, 0.36, 1) both',
        shimmer: 'shimmer 1.6s infinite',
        aurora: 'aurora-drift 18s ease-in-out infinite'
      }
    }
  },
  plugins: []
}
