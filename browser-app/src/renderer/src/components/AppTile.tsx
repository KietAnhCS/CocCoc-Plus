import type { JSX } from 'react'
import type { SideApp } from '../lib/apps'

interface AppTileProps {
  app: SideApp
  size?: number
}

function AppTile({ app, size = 32 }: AppTileProps): JSX.Element {
  return (
    <span
      className="flex shrink-0 items-center justify-center rounded-full"
      style={{ background: app.color, color: app.ink ?? '#fff', width: size, height: size }}
    >
      <svg
        viewBox="0 0 24 24"
        style={{ width: size * 0.62, height: size * 0.62 }}
        aria-hidden="true"
      >
        {app.glyph}
      </svg>
    </span>
  )
}

export default AppTile
