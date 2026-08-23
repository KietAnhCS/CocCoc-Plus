import { useState, type FormEvent, type JSX } from 'react'
import { useSessionStore } from '../store/sessionStore'
import { useAdminStore } from '../store/adminStore'
import { dateTime } from '../lib/format'
import { DeviceIcon, ExitIcon, KeyIcon, ShieldCheckIcon, SpinnerIcon } from './icons'

/**
 * Nội dung của popover tài khoản trên thanh công cụ.
 *
 * ĐÂY LÀ CHỖ TRẢ LỜI CÂU HỎI "AI LÀ ADMIN, AI LÀ NGƯỜI DÙNG THƯỜNG". Trước
 * đây nó hiện một hằng số cứng — `admin / admin@gmail.com / Đã đăng nhập` —
 * cho **mọi** người, kể cả khi chưa ai đăng nhập. Hai nguồn sự thật mâu thuẫn
 * trên cùng một màn hình: người dùng thấy mình "đã đăng nhập admin" rồi bấm
 * vào khu vực quản trị lại bị hỏi mật khẩu.
 *
 * Nay tấm thẻ này đọc `sessionStore`, và `sessionStore` lấy sự thật từ máy chủ
 * qua `/api/auth/me`. Vai trò hiện ra ở đây là vai trò **thật sự** sẽ được máy
 * chủ áp cho mọi request tiếp theo.
 */
function AccountMenu({ onNavigateAdmin }: { onNavigateAdmin: () => void }): JSX.Element {
  const user = useSessionStore((state) => state.user)
  return user ? <SignedIn onNavigateAdmin={onNavigateAdmin} /> : <SignedOut />
}

function SignedIn({ onNavigateAdmin }: { onNavigateAdmin: () => void }): JSX.Element {
  const user = useSessionStore((state) => state.user)!
  const signOut = useSessionStore((state) => state.signOut)
  const signOutEverywhere = useSessionStore((state) => state.signOutEverywhere)
  const openScreen = useSessionStore((state) => state.openScreen)
  const notice = useSessionStore((state) => state.notice)
  const busy = useSessionStore((state) => state.busy)
  const clearKey = useAdminStore((state) => state.clearKey)
  const isAdmin = user.role === 'ADMIN'

  return (
    <div className="px-2.5 py-2">
      <div className="flex items-center gap-2.5">
        <span
          className={
            'flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[13px] font-bold text-white ' +
            (isAdmin
              ? 'bg-linear-to-br from-emerald-500 to-lime-400'
              : 'bg-linear-to-br from-teal-500 to-emerald-400')
          }
        >
          {user.username.slice(0, 2).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium text-ink">{user.username}</p>
          <p className="mt-0.5 flex items-center gap-1 text-[11.5px]">
            {isAdmin ? (
              <>
                <ShieldCheckIcon className="h-3.5 w-3.5 text-success" />
                <span className="font-medium text-success">Quản trị viên</span>
              </>
            ) : (
              <span className="text-muted">Người dùng</span>
            )}
          </p>
        </div>
      </div>

      <dl className="mt-3 space-y-1 border-t border-line pt-2.5 text-[11.5px]">
        <div className="flex justify-between gap-2">
          <dt className="text-faint">Tạo lúc</dt>
          <dd className="text-muted">{dateTime(user.createdAt)}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-faint">Đăng nhập gần nhất</dt>
          <dd className="text-muted">{dateTime(user.lastLoginAt)}</dd>
        </div>
      </dl>

      {notice && (
        <p className="mt-2.5 rounded-lg bg-success/10 px-2.5 py-2 text-[11.5px] leading-relaxed text-success">
          {notice}
        </p>
      )}

      <div className="menu-sep" />

      {isAdmin && (
        <button onClick={onNavigateAdmin} className="menu-row">
          <ShieldCheckIcon className="h-4 w-4 text-success" />
          Bảng điều khiển quản trị
        </button>
      )}

      <button onClick={() => openScreen('password')} className="menu-row">
        <KeyIcon className="h-4 w-4 text-muted" />
        Đổi mật khẩu
      </button>

      <button
        onClick={() => {
          clearKey()
          void signOut()
        }}
        className="menu-row text-danger hover:bg-danger/10"
      >
        <ExitIcon className="h-4 w-4" />
        Đăng xuất
      </button>

      {/*
        Tách riêng khỏi "Đăng xuất" bằng một đường kẻ, và ghi rõ hệ quả trong
        tooltip: hai nút nhìn giống nhau nhưng một cái chỉ đóng phiên tại đây,
        cái kia đóng cả những phiên người dùng không nhớ đã mở ở đâu.
      */}
      <div className="menu-sep" />
      <button
        onClick={() => {
          clearKey()
          void signOutEverywhere()
        }}
        disabled={busy}
        className="menu-row text-danger hover:bg-danger/10"
        title="Đóng MỌI phiên đăng nhập của tài khoản này trên mọi thiết bị, kể cả thiết bị đang dùng. Dành cho lúc nghi ngờ phiên của mình bị lộ ở nơi khác."
      >
        <DeviceIcon className="h-4 w-4" />
        Đăng xuất khỏi mọi thiết bị
      </button>
    </div>
  )
}

/** Chưa đăng nhập: một biểu mẫu làm được cả hai việc, đổi bằng một dòng chữ. */
function SignedOut(): JSX.Element {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  const busy = useSessionStore((state) => state.busy)
  const error = useSessionStore((state) => state.error)
  const signIn = useSessionStore((state) => state.signIn)
  const signUp = useSessionStore((state) => state.signUp)
  const openScreen = useSessionStore((state) => state.openScreen)
  const clearError = useSessionStore((state) => state.clearError)

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    const ok =
      mode === 'login' ? await signIn(username, password) : await signUp(username, password)
    if (ok) {
      // Xoá mật khẩu khỏi state ngay khi xong: không để nó nằm trong bộ nhớ
      // của một component còn sống sau khi đã dùng xong.
      setPassword('')
      setUsername('')
    }
  }

  const inputClass =
    'h-9 w-full rounded-lg border border-line bg-omni px-2.5 text-[13px] text-ink ' +
    'placeholder:text-faint transition focus:border-brand/50 focus:outline-none ' +
    'focus:ring-2 focus:ring-brand/15'

  return (
    <form onSubmit={submit} className="px-2.5 py-2">
      <p className="text-[13px] font-medium text-ink">
        {mode === 'login' ? 'Đăng nhập' : 'Tạo tài khoản'}
      </p>
      <p className="mt-0.5 text-[11.5px] leading-snug text-faint">
        {mode === 'login'
          ? 'Chưa đăng nhập — bạn vẫn tìm kiếm bình thường, chỉ không xem được số liệu quản trị.'
          : 'Tài khoản mới luôn có vai trò Người dùng. Chỉ quản trị viên mới nâng được vai trò.'}
      </p>

      <div className="mt-2.5 space-y-1.5">
        <input
          value={username}
          onChange={(event) => {
            setUsername(event.target.value)
            clearError()
          }}
          className={inputClass}
          placeholder="Tên tài khoản"
          autoComplete="username"
          spellCheck={false}
          aria-label="Tên tài khoản"
        />
        <input
          type="password"
          value={password}
          onChange={(event) => {
            setPassword(event.target.value)
            clearError()
          }}
          className={inputClass}
          placeholder="Mật khẩu"
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
          aria-label="Mật khẩu"
        />
      </div>

      {error && (
        <p role="alert" className="mt-2 text-[11.5px] leading-relaxed text-danger">
          {error}
        </p>
      )}

      <button
        type="submit"
        disabled={busy || !username.trim() || !password}
        className="mt-2.5 flex h-9 w-full items-center justify-center rounded-lg text-[13px]
                   font-medium transition enabled:bg-brand enabled:text-white
                   enabled:hover:brightness-110 disabled:cursor-not-allowed
                   disabled:bg-raised disabled:text-faint focus-visible:outline-none
                   focus-visible:ring-2 focus-visible:ring-brand/60"
      >
        {busy ? <SpinnerIcon className="h-4 w-4" /> : mode === 'login' ? 'Đăng nhập' : 'Đăng ký'}
      </button>

      <button
        type="button"
        onClick={() => {
          setMode(mode === 'login' ? 'register' : 'login')
          clearError()
        }}
        className="mt-2 w-full text-[11.5px] text-muted underline-offset-2 hover:text-ink hover:underline"
      >
        {mode === 'login' ? 'Chưa có tài khoản? Đăng ký' : 'Đã có tài khoản? Đăng nhập'}
      </button>

      {/*
        Popover này là LỐI VÀO NHANH cho người đã biết mình làm gì. Màn hình
        đầy đủ có thêm ô nhập lại mật khẩu, thanh đo độ mạnh và các dòng nhắc
        lỗi — những thứ không nhét vừa 280px mà không phải cắt bớt chính phần
        giải thích làm cho biểu mẫu dùng được.
      */}
      <button
        type="button"
        onClick={() => openScreen(mode === 'login' ? 'signin' : 'signup')}
        className="mt-1.5 w-full text-[11.5px] text-brand underline-offset-2 hover:underline"
      >
        Mở màn hình đầy đủ
      </button>
    </form>
  )
}

export default AccountMenu
