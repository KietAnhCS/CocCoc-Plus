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
