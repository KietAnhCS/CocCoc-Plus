/**
 * Bộ biểu tượng SVG nội tuyến (nét, kế thừa `currentColor`).
 *
 * Cố tình KHÔNG dùng thư viện icon ngoài: `index.html` đặt CSP
 * `default-src 'self'` nên mọi tài nguyên phải nằm trong gói, và một trình
 * duyệt chỉ cần vài chục biểu tượng — thêm cả một package cho ngần đó là
 * lãng phí.
 */
import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

/** Khung chung: 24x24, nét 1.8, bo tròn đầu nét — cùng ngôn ngữ hình học. */
function Icon({ children, ...props }: IconProps): JSX.Element {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  )
}

export function ArrowLeftIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M19 12H5m0 0 6-6m-6 6 6 6" />
    </Icon>
  )
}

export function ArrowRightIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M5 12h14m0 0-6-6m6 6-6 6" />
    </Icon>
  )
}

export function ReloadIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M20 11a8 8 0 1 0-.6 4" />
      <path d="M20 4v6h-6" />
    </Icon>
  )
}

export function HomeIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4v-5H9v5H5a1 1 0 0 1-1-1z" />
    </Icon>
  )
}

export function SearchIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="11" cy="11" r="6.5" />
      <path d="m16 16 4 4" />
    </Icon>
  )
}

export function LockIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <rect x="5" y="10.5" width="14" height="9.5" rx="2" />
      <path d="M8 10.5V7.5a4 4 0 1 1 8 0v3" />
    </Icon>
  )
}

export function GlobeIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M3.5 12h17M12 3.5c2.2 2.3 3.3 5.2 3.3 8.5s-1.1 6.2-3.3 8.5c-2.2-2.3-3.3-5.2-3.3-8.5S9.8 5.8 12 3.5Z" />
    </Icon>
  )
}

export function StarIcon({ filled, ...props }: IconProps & { filled?: boolean }): JSX.Element {
  return (
    <Icon fill={filled ? 'currentColor' : 'none'} {...props}>
      <path d="m12 4 2.45 4.97 5.49.8-3.97 3.87.94 5.46L12 16.52l-4.91 2.58.94-5.46-3.97-3.87 5.49-.8z" />
    </Icon>
  )
}

export function PlusIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M12 5v14M5 12h14" />
    </Icon>
  )
}

export function CloseIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="m6 6 12 12M18 6 6 18" />
    </Icon>
  )
}

export function SunIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.5v2M12 19.5v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M2.5 12h2M19.5 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4" />
    </Icon>
  )
}

export function MoonIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M20 14.2A8.2 8.2 0 0 1 9.8 4a8.5 8.5 0 1 0 10.2 10.2Z" />
    </Icon>
  )
}

export function ClockIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 1.8" />
    </Icon>
  )
}

export function ChevronLeftIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="m14.5 6-6 6 6 6" />
    </Icon>
  )
}

export function ChevronRightIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="m9.5 6 6 6-6 6" />
    </Icon>
  )
}

export function SlidersIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M5 7h9m3 0h2M5 17h3m3 0h9" />
      <circle cx="16" cy="7" r="2" />
      <circle cx="9.5" cy="17" r="2" />
    </Icon>
  )
}

export function BoltIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M13 3 5.5 13.2h5.2L11 21l7.5-10.2h-5.2z" />
    </Icon>
  )
}

export function AlertIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M12 4.5 21 20H3z" />
      <path d="M12 10v4.2M12 17.3v.2" />
    </Icon>
  )
}

/** Vòng xoay chờ — dùng cho tab đang tải và nút "Đang tìm". */
export function SpinnerIcon(props: IconProps): JSX.Element {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false" {...props}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.22" strokeWidth="2.6" />
      <path
        d="M21 12a9 9 0 0 0-9-9"
        stroke="currentColor"
        strokeWidth="2.6"
        strokeLinecap="round"
        className="origin-center animate-spin"
        style={{ animationDuration: '0.8s' }}
      />
    </svg>
  )
}

/* --- Thanh công cụ: cụm bên phải ô địa chỉ --- */

/** Mảnh ghép — lối vào danh sách tiện ích mở rộng. */
export function PuzzleIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M10 4.5a1.8 1.8 0 0 1 3.6 0V6h2.7a1 1 0 0 1 1 1v2.7h1.2a1.8 1.8 0 0 1 0 3.6h-1.2V16a1 1 0 0 1-1 1h-2.7v1.2a1.8 1.8 0 0 1-3.6 0V17H7.3a1 1 0 0 1-1-1v-2.7H5.1a1.8 1.8 0 0 1 0-3.6h1.2V7a1 1 0 0 1 1-1H10z" />
    </Icon>
  )
}

/** Tia lấp lánh của nút "Hỏi AI": một ngôi sao lớn kèm một ngôi sao nhỏ. */
export function SparkleIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M11 3.5 12.7 8 17 9.7 12.7 11.4 11 15.8 9.3 11.4 5 9.7 9.3 8z" />
      <path d="M17.6 14.6 18.4 16.6 20.4 17.4 18.4 18.2 17.6 20.2 16.8 18.2 14.8 17.4 16.8 16.6z" />
    </Icon>
  )
}

/** Chia đôi màn hình: một khung chia làm hai cột. */
export function SplitScreenIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <rect x="3.5" y="5" width="17" height="14" rx="2.2" />
      <path d="M12 5v14" />
    </Icon>
  )
}

/** Mũi tên xuống khay — danh sách tải xuống. */
export function DownloadIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M12 4v10m0 0 4-4m-4 4-4-4" />
      <path d="M4.5 16.5v1.8a1.7 1.7 0 0 0 1.7 1.7h11.6a1.7 1.7 0 0 0 1.7-1.7v-1.8" />
    </Icon>
  )
}

/** Ba gạch ngang — nút mở menu chính. */
export function MenuIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M4 7h16M4 12h16M4 17h16" />
    </Icon>
  )
}

/** Lưới 3x3 — nút "ứng dụng" đầu thanh dấu trang. */
export function GridAppsIcon(props: IconProps): JSX.Element {
  return (
    <Icon fill="currentColor" stroke="none" {...props}>
      <circle cx="6" cy="6" r="1.6" />
      <circle cx="12" cy="6" r="1.6" />
      <circle cx="18" cy="6" r="1.6" />
      <circle cx="6" cy="12" r="1.6" />
      <circle cx="12" cy="12" r="1.6" />
      <circle cx="18" cy="12" r="1.6" />
      <circle cx="6" cy="18" r="1.6" />
      <circle cx="12" cy="18" r="1.6" />
      <circle cx="18" cy="18" r="1.6" />
    </Icon>
  )
}

/** ">>" — mở phần dấu trang bị tràn khỏi thanh. */
export function ChevronsRightIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="m7 6 6 6-6 6M14 6l6 6-6 6" />
    </Icon>
  )
}

export function ChevronDownIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="m6 9.5 6 6 6-6" />
    </Icon>
  )
}

/** Thư mục — nhóm dấu trang. */
export function FolderIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M3.5 7.2a1.7 1.7 0 0 1 1.7-1.7h3.4l1.8 2.2h8a1.7 1.7 0 0 1 1.7 1.7v8.4a1.7 1.7 0 0 1-1.7 1.7H5.2a1.7 1.7 0 0 1-1.7-1.7z" />
    </Icon>
  )
}

/* --- Trang chủ --- */

/** Micrô — tìm kiếm bằng giọng nói. */
export function MicIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <rect x="9.2" y="3" width="5.6" height="10.5" rx="2.8" />
      <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3" />
    </Icon>
  )
}

/** Mặt trời sau đám mây — overlay thời tiết ban ngày. */
export function SunCloudIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="8.5" cy="8" r="3.2" />
      <path d="M8.5 2v1.4M8.5 12.6V14M2.5 8h1.4M13.1 8h1.4M4.3 3.8l1 1M12.7 3.8l-1 1" />
      <path d="M11 19.5h6.8a3.2 3.2 0 0 0 .2-6.4 4.4 4.4 0 0 0-8.4-.6A3.3 3.3 0 0 0 10 19.4z" />
    </Icon>
  )
}

/** Trăng sau đám mây — overlay thời tiết ban đêm. */
export function MoonCloudIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M14.6 9.4A5.2 5.2 0 0 1 8.3 3a5.4 5.4 0 1 0 6.3 6.4Z" />
      <path d="M11 20h6.8a3.2 3.2 0 0 0 .2-6.4 4.4 4.4 0 0 0-8.4-.6A3.3 3.3 0 0 0 10 19.9z" />
    </Icon>
  )
}

/* --- Cột biểu tượng bên phải và menu --- */

/** Ghim — giữ bảng bên luôn mở. */
export function PinIcon({ filled, ...props }: IconProps & { filled?: boolean }): JSX.Element {
  return (
    <Icon fill={filled ? 'currentColor' : 'none'} {...props}>
      <path d="M9 3.5h6l-.8 5.2 3 3.1v1.4H15V21l-1.5-1.5L12 21v-7.8H6.8v-1.4l3-3.1z" />
    </Icon>
  )
}

/** Chữ "A" kèm gạch chân — nút dịch trang. */
export function TranslateIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M4 17.5 8.8 6l4.8 11.5M5.8 14h6" />
      <path d="M14.5 9.5h5.8M17.4 9.5v-2M19.8 9.5c-.4 4.2-2.6 7-5.3 8.5M15.6 13.2c.9 2 2.4 3.7 4.7 4.8" />
    </Icon>
  )
}

/** Bánh răng — cài đặt. */
export function SettingsIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="12" cy="12" r="3.1" />
      <path d="M12 2.8l1.5 2.3 2.7-.5.5 2.7 2.3 1.5-1.4 2.4 1.4 2.4-2.3 1.5-.5 2.7-2.7-.5L12 21.2l-1.5-2.3-2.7.5-.5-2.7-2.3-1.5L6.4 12 5 9.6l2.3-1.5.5-2.7 2.7.5z" />
    </Icon>
  )
}

/** Cửa sổ mới. */
export function WindowIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <rect x="3.5" y="5" width="17" height="14" rx="2.2" />
      <path d="M3.5 9h17" />
    </Icon>
  )
}

/** Mũ + kính — cửa sổ ẩn danh. */
export function IncognitoIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M5 11.5 6.9 5.8A1.8 1.8 0 0 1 8.6 4.5h6.8a1.8 1.8 0 0 1 1.7 1.3L19 11.5M3 11.5h18" />
      <circle cx="7.4" cy="16" r="2.9" />
      <circle cx="16.6" cy="16" r="2.9" />
      <path d="M10.3 15.4a3 3 0 0 1 3.4 0" />
    </Icon>
  )
}

/** Máy in. */
export function PrintIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M7 8.5V4h10v4.5" />
      <path d="M6.5 18.5H5a1.5 1.5 0 0 1-1.5-1.5v-5A1.5 1.5 0 0 1 5 10.5h14a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 1-1.5 1.5h-1.5" />
      <rect x="7" y="15" width="10" height="5.5" rx="1" />
    </Icon>
  )
}

/** Dấu trừ / dấu cộng của hàng thu phóng. */
export function MinusIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M5 12h14" />
    </Icon>
  )
}

/** Bốn góc mở ra — toàn màn hình. */
export function FullscreenIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15" />
    </Icon>
  )
}

/** Dấu hỏi trong vòng tròn — trợ giúp. */
export function HelpIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.6 9.6a2.5 2.5 0 1 1 3.3 2.4c-.6.2-.9.8-.9 1.4v.4M12 16.8v.2" />
    </Icon>
  )
}

/** Mũi tên ra khỏi cửa — thoát. */
export function ExitIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M14.5 4.5H18a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5h-3.5" />
      <path d="M10 15.5 13.5 12 10 8.5M13.5 12h-9" />
    </Icon>
  )
}

/** Máy tính xách tay — mục "Các thiết bị của bạn". */
export function DeviceIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <rect x="4.5" y="5" width="15" height="10" rx="1.6" />
      <path d="M2.5 18.5h19" />
    </Icon>
  )
}

/** Thùng rác — xoá dữ liệu duyệt web. */
export function TrashIcon(props: IconProps): JSX.Element {
  return (
    <Icon {...props}>
      <path d="M4.5 6.5h15M9.5 6.5V4.8a1.3 1.3 0 0 1 1.3-1.3h2.4a1.3 1.3 0 0 1 1.3 1.3v1.7" />
      <path d="M6.5 6.5 7.4 19a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-12.5" />
    </Icon>
  )
}

/* --- Nút điều khiển cửa sổ: vẽ theo đúng hình dạng của Windows 11 --- */

export function WinMinimizeIcon(props: IconProps): JSX.Element {
  return (
    <svg viewBox="0 0 10 10" fill="none" aria-hidden="true" focusable="false" {...props}>
      <path d="M0 5h10" stroke="currentColor" strokeWidth="1" />
    </svg>
  )
}

export function WinMaximizeIcon(props: IconProps): JSX.Element {
  return (
    <svg viewBox="0 0 10 10" fill="none" aria-hidden="true" focusable="false" {...props}>
      <rect x="0.5" y="0.5" width="9" height="9" stroke="currentColor" strokeWidth="1" rx="1" />
    </svg>
  )
}

export function WinRestoreIcon(props: IconProps): JSX.Element {
  return (
    <svg viewBox="0 0 10 10" fill="none" aria-hidden="true" focusable="false" {...props}>
      <path d="M2.5 2.5V1a.5.5 0 0 1 .5-.5h6a.5.5 0 0 1 .5.5v6a.5.5 0 0 1-.5.5H7.5" stroke="currentColor" strokeWidth="1" />
      <rect x="0.5" y="2.5" width="7" height="7" stroke="currentColor" strokeWidth="1" rx="1" />
    </svg>
  )
}

export function WinCloseIcon(props: IconProps): JSX.Element {
  return (
    <svg viewBox="0 0 10 10" fill="none" aria-hidden="true" focusable="false" {...props}>
      <path d="M0.5 0.5l9 9M9.5 0.5l-9 9" stroke="currentColor" strokeWidth="1.1" />
    </svg>
  )
}

/** Logo VnSearch: ngôi sao năm cánh (gợi quốc kỳ) đặt trong kính lúp. */
export function VnSearchMark({ className }: { className?: string }): JSX.Element {
  return (
    <svg viewBox="0 0 48 48" className={className} aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="vn-mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#f43f5e" />
          <stop offset="55%" stopColor="#f97316" />
          <stop offset="100%" stopColor="#fbbf24" />
        </linearGradient>
      </defs>
      <circle cx="21" cy="21" r="14.5" fill="url(#vn-mark)" />
      <path
        d="M21 12.2l2.36 4.79 5.29.77-3.83 3.73.9 5.27L21 24.28l-4.72 2.48.9-5.27-3.83-3.73 5.29-.77z"
        fill="#fff"
        fillOpacity="0.96"
      />
      <path
        d="M31.6 31.6 41 41"
        stroke="currentColor"
        strokeWidth="4.6"
        strokeLinecap="round"
        fill="none"
      />
    </svg>
  )
}
