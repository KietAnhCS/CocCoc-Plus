import type { JSX } from 'react'
import type { SideApp } from '../lib/apps'

interface AppTileProps {
  app: SideApp
  size?: number
}

function AppTile({ app, size = 32 }: AppTileProps): JSX.Element {
  return (
    <span
      className="relative flex shrink-0 items-center justify-center overflow-hidden rounded-full
                 shadow-sm ring-1 ring-inset ring-white/25"
      style={{ background: app.color, color: app.ink ?? '#fff', width: size, height: size }}
    >
      {/* Vệt sáng mặt trên. Mọi ô ứng dụng ở đây đều là một mảng màu phẳng lấy
          từ thương hiệu gốc; thêm cùng một vệt sáng cho tất cả khiến chúng
          trông như cùng một bộ, thay vì mười hai mảng màu vay mượn. */}
      <span
        aria-hidden="true"
        className="absolute inset-x-0 top-0 h-1/2 bg-linear-to-b from-white/30 to-transparent"
      />
      <svg
        viewBox="0 0 24 24"
        style={{ width: size * 0.62, height: size * 0.62 }}
        className="relative"
        aria-hidden="true"
      >
        {app.glyph}
      </svg>
    </span>
  )
}

export default AppTile
