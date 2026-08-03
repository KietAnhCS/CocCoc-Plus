/**
 * Danh mục ứng dụng của cột bên phải (SideRail) và bảng "Thêm trang web vào
 * thanh bên" (SidePanel). Hai chỗ dùng CHUNG danh sách này để tên, màu và
 * hình vẽ không bao giờ lệch nhau.
 *
 * Hình vẽ là SVG nội tuyến chứ không tải logo thật: CSP của renderer là
 * `default-src 'self'` (xem index.html) nên mọi ảnh từ máy chủ ngoài đều bị
 * chặn — cùng lý do với "favicon giả" ở lib/site.ts. Đây là hình PHỎNG THEO
 * cho đủ nhận diện ở cỡ 20px, không phải logo chính thức của các hãng.
 */

export interface SideApp {
  id: string
  name: string
  url: string
  /** Nền viên tròn. */
  color: string
  /** Nội dung SVG, vẽ trong khung 24x24, tô bằng `currentColor`. */
  glyph: JSX.Element
  /** Màu nét trên nền; mặc định trắng. Nền sáng (Snapchat) cần nét tối. */
  ink?: string
}

/* --- Hình vẽ --- */

/** Chữ cái đặt giữa khung 24x24 — dùng cho các logo vốn là chữ (f, Z). */
function letter(text: string, size = 16): JSX.Element {
  return (
    <text
      x="12"
      y="12"
      textAnchor="middle"
      dominantBaseline="central"
      fontSize={size}
      fontWeight="700"
      fontFamily="Segoe UI, system-ui, sans-serif"
      fill="currentColor"
    >
      {text}
    </text>
  )
}

const messengerGlyph = (
  <>
    <path
      d="M12 2.6c-5.3 0-9.4 3.9-9.4 9.1 0 2.9 1.3 5.5 3.4 7.2v3.5l3.2-1.8c.9.2 1.8.4 2.8.4 5.3 0 9.4-3.9 9.4-9.1S17.3 2.6 12 2.6z"
      fill="currentColor"
    />
    {/* Tia chớp khoét ngược lại bằng màu nền viên tròn. */}
    <path d="m6.4 14.9 4.9-5.2 2.5 2.6 3.8-2.6-4.9 5.2-2.5-2.6z" fill="#0084FF" />
  </>
)

const zaloGlyph = (
  <>
    <path
      d="M4.6 3.4h14.8a2 2 0 0 1 2 2v9.3a2 2 0 0 1-2 2h-6.6l-4.6 3.3v-3.3H4.6a2 2 0 0 1-2-2V5.4a2 2 0 0 1 2-2z"
      fill="currentColor"
    />
    <text
      x="12"
      y="10.4"
      textAnchor="middle"
      dominantBaseline="central"
      fontSize="8"
      fontWeight="800"
      fontFamily="Segoe UI, system-ui, sans-serif"
      fill="#0068FF"
    >
      Zalo
    </text>
  </>
)

const gameGlyph = (
  <>
    <path
      d="M7.6 7h8.8a4.6 4.6 0 0 1 4.5 3.7l.8 4.2a2.6 2.6 0 0 1-4.7 1.9l-1.3-1.9H8.3L7 16.8a2.6 2.6 0 0 1-4.7-1.9l.8-4.2A4.6 4.6 0 0 1 7.6 7z"
      fill="currentColor"
    />
    <path d="M6.6 10.4v3M5.1 11.9h3" stroke="#7C3AED" strokeWidth="1.5" strokeLinecap="round" />
    <circle cx="16.2" cy="10.9" r="1.05" fill="#7C3AED" />
    <circle cx="18" cy="13.1" r="1.05" fill="#7C3AED" />
  </>
)

const youtubeGlyph = (
  <>
    <rect x="2.4" y="5.6" width="19.2" height="12.8" rx="3.6" fill="currentColor" />
    <path d="M10.2 9.1 15.6 12l-5.4 2.9z" fill="#FF0000" />
  </>
)

const translateGlyph = (
  <>
    <path
      d="M3.2 16.8 7.6 5.6h2.2l4.4 11.2h-2.2l-1-2.7H6.4l-1 2.7z M7 12.4h3.4L8.7 7.9z"
      fill="currentColor"
    />
    <path
      d="M13.6 8.6h7.2M17.2 8.6V6.4M20.4 8.6c-.5 4.4-3 7.4-6.2 8.9M15.9 12.6c1.1 2.2 2.9 3.9 5.4 5"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      fill="none"
    />
  </>
)

const telegramGlyph = (
  <path
    d="M21 4.3 2.9 11.3c-.9.35-.88 1.63.03 1.95l4.5 1.58 1.72 5.3c.22.68 1.1.85 1.56.3l2.42-2.9 4.66 3.42c.63.46 1.53.12 1.7-.65l3.1-14.4c.19-.86-.66-1.6-1.49-1.28z M9.1 14.3l8.6-5.5-6.9 6.5-.3 3.2z"
    fill="currentColor"
  />
)

const whatsappGlyph = (
  <path
    d="M12 2.7A9.2 9.2 0 0 0 4.1 16.6L2.8 21.3l4.85-1.27A9.2 9.2 0 1 0 12 2.7zm0 1.9a7.3 7.3 0 1 1-3.85 13.5l-.34-.21-2.7.71.72-2.63-.22-.35A7.3 7.3 0 0 1 12 4.6zm-3.4 3.5c-.18 0-.47.07-.72.34-.25.27-.94.92-.94 2.24s.96 2.6 1.1 2.78c.14.18 1.87 2.98 4.62 4.06 2.28.9 2.75.72 3.25.67.5-.04 1.6-.65 1.83-1.29.23-.63.23-1.17.16-1.29-.07-.11-.25-.18-.53-.31-.27-.14-1.6-.79-1.85-.88-.25-.09-.43-.14-.6.13-.18.27-.7.88-.86 1.06-.16.18-.32.2-.59.07-.27-.14-1.14-.42-2.18-1.34-.8-.72-1.35-1.6-1.5-1.87-.16-.27-.02-.42.12-.55.12-.12.27-.32.4-.48.14-.16.18-.27.28-.45.09-.18.04-.34-.02-.48-.07-.13-.6-1.45-.82-1.99-.22-.52-.44-.45-.6-.46z"
    fill="currentColor"
  />
)

const chatgptGlyph = (
  <path
    d="M12 2.6 20.1 7.3v9.4L12 21.4 3.9 16.7V7.3zm0 2.3L5.9 8.4v7.2L12 19.1l6.1-3.5V8.4z M12 8l3.6 2.1v3.8L12 16l-3.6-2.1v-3.8z"
    fill="currentColor"
  />
)

const geminiGlyph = (
  <path
    d="M12 2.2c.5 4.9 4.9 9.3 9.8 9.8-4.9.5-9.3 4.9-9.8 9.8-.5-4.9-4.9-9.3-9.8-9.8 4.9-.5 9.3-4.9 9.8-9.8z"
    fill="currentColor"
  />
)

const discordGlyph = (
  <path
    d="M19.3 6.3A16 16 0 0 0 15.4 5l-.25.5a12 12 0 0 1 3.4 1.6 13.9 13.9 0 0 0-11.1 0A12 12 0 0 1 10.85 5.5L10.6 5a16 16 0 0 0-3.9 1.3C3.5 10.7 3 15 3.3 19.2a16.3 16.3 0 0 0 4.9 2.2l.9-1.5c-.8-.3-1.6-.7-2.3-1.2l.5-.4a11.6 11.6 0 0 0 9.4 0l.5.4c-.7.5-1.5.9-2.3 1.2l.9 1.5a16.3 16.3 0 0 0 4.9-2.2c.4-4.9-.6-9.1-2.4-12.9zM9.3 16.3c-1 0-1.8-.9-1.8-2s.8-2 1.8-2 1.8.9 1.8 2-.8 2-1.8 2zm5.4 0c-1 0-1.8-.9-1.8-2s.8-2 1.8-2 1.8.9 1.8 2-.8 2-1.8 2z"
    fill="currentColor"
  />
)

const snapchatGlyph = (
  <path
    d="M12 2.6c2.8 0 4.6 2.1 4.6 4.9 0 .8-.06 1.6-.1 2.2.3.15.75.2 1.2.03.5-.19 1.1.05 1.2.55.1.5-.25.9-.9 1.2-.6.28-1.4.5-1.5.9-.1.4.5 1.4 1.3 2.2.7.7 1.6 1.2 2.3 1.4.4.12.6.5.5.85-.15.5-1 .85-2.3 1.05-.2.5-.15 1-.5 1.15-.35.15-.9-.1-1.7-.1-.85 0-1.6.25-2.4.85-.7.5-1.3.9-1.85.9s-1.15-.4-1.85-.9c-.8-.6-1.55-.85-2.4-.85-.8 0-1.35.25-1.7.1-.35-.15-.3-.65-.5-1.15-1.3-.2-2.15-.55-2.3-1.05-.1-.35.1-.73.5-.85.7-.2 1.6-.7 2.3-1.4.8-.8 1.4-1.8 1.3-2.2-.1-.4-.9-.62-1.5-.9-.65-.3-1-.7-.9-1.2.1-.5.7-.74 1.2-.55.45.17.9.12 1.2-.03-.04-.6-.1-1.4-.1-2.2 0-2.8 1.8-4.9 4.6-4.9z"
    fill="currentColor"
  />
)

/* --- Danh sách ứng dụng --- */

const MESSENGER: SideApp = {
  id: 'messenger',
  name: 'Messenger',
  url: 'https://www.messenger.com/',
  color: '#0084FF',
  glyph: messengerGlyph
}

const ZALO: SideApp = {
  id: 'zalo',
  name: 'Zalo',
  url: 'https://chat.zalo.me/',
  color: '#0068FF',
  glyph: zaloGlyph
}

const GAME: SideApp = {
  id: 'game',
  name: 'Trò chơi',
  url: 'https://coccoc.com/games',
  color: '#7C3AED',
  glyph: gameGlyph
}

const YOUTUBE: SideApp = {
  id: 'youtube',
  name: 'YouTube',
  url: 'https://www.youtube.com/',
  color: '#FF0000',
  glyph: youtubeGlyph
}

const FACEBOOK: SideApp = {
  id: 'facebook',
  name: 'Facebook',
  url: 'https://www.facebook.com/',
  color: '#1877F2',
  glyph: letter('f', 17)
}

const GOOGLE_TRANSLATE: SideApp = {
  id: 'gtranslate',
  name: 'Google Dịch',
  url: 'https://translate.google.com/',
  color: '#4285F4',
  glyph: translateGlyph
}

const TELEGRAM: SideApp = {
  id: 'telegram',
  name: 'Telegram',
  url: 'https://web.telegram.org/',
  color: '#2AABEE',
  glyph: telegramGlyph
}

const WHATSAPP: SideApp = {
  id: 'whatsapp',
  name: 'WhatsApp',
  url: 'https://web.whatsapp.com/',
  color: '#25D366',
  glyph: whatsappGlyph
}

const CHATGPT: SideApp = {
  id: 'chatgpt',
  name: 'Chat GPT',
  url: 'https://chatgpt.com/',
  color: '#10A37F',
  glyph: chatgptGlyph
}

const GEMINI: SideApp = {
  id: 'gemini',
  name: 'Gemini',
  url: 'https://gemini.google.com/',
  color: '#1A73E8',
  glyph: geminiGlyph
}

const DISCORD: SideApp = {
  id: 'discord',
  name: 'Discord',
  url: 'https://discord.com/app',
  color: '#5865F2',
  glyph: discordGlyph
}

const SNAPCHAT: SideApp = {
  id: 'snapchat',
  name: 'Snapchat',
  url: 'https://web.snapchat.com/',
  color: '#FFFC00',
  ink: '#111318',
  glyph: snapchatGlyph
}

/** Sáu ứng dụng ghim sẵn ở cột dọc bên phải. */
export const RAIL_APPS: SideApp[] = [
  MESSENGER,
  ZALO,
  GAME,
  YOUTUBE,
  FACEBOOK,
  GOOGLE_TRANSLATE
]

/** Các nhóm hiện trong bảng "Thêm trang web vào thanh bên". */
export const APP_GROUPS: { label: string; apps: SideApp[] }[] = [
  { label: 'Trò chuyện', apps: [ZALO, MESSENGER, TELEGRAM, WHATSAPP] },
  { label: 'Giải trí', apps: [CHATGPT, GEMINI, DISCORD, SNAPCHAT] }
]

/** Tra một ứng dụng theo id — dùng khi khôi phục danh sách đã ghim từ localStorage. */
export function findApp(id: string): SideApp | undefined {
  const all = [...RAIL_APPS, ...APP_GROUPS.flatMap((g) => g.apps)]
  return all.find((a) => a.id === id)
}

/**
 * Viên tròn chứa hình vẽ. Cùng một thành phần dùng cho cột bên phải (cỡ 30px)
 * lẫn lưới trong bảng (cỡ 44px), nên kích thước truyền từ ngoài vào.
 */
export function AppTile({ app, size = 32 }: { app: SideApp; size?: number }): JSX.Element {
  return (
    <span
      className="flex shrink-0 items-center justify-center rounded-full"
      style={{ background: app.color, color: app.ink ?? '#fff', width: size, height: size }}
    >
      <svg viewBox="0 0 24 24" style={{ width: size * 0.62, height: size * 0.62 }} aria-hidden="true">
        {app.glyph}
      </svg>
    </span>
  )
}
