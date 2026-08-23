import { useEffect, useState, type FormEvent, type JSX } from 'react'
import { useSessionStore } from '../../store/sessionStore'
import { useOverlayStore } from '../../store/overlayStore'
import {
  passwordStrength,
  validateConfirmation,
  validatePassword,
  validateUsername
} from '../../lib/validation'
import PasswordField from './PasswordField'
import { CloseIcon, ShieldCheckIcon, SpinnerIcon, UserIcon, VnSearchMark } from '../icons'

/**
 * Màn hình tài khoản toàn màn hình: đăng nhập, đăng ký, đổi mật khẩu.
 *
 * VÌ SAO MỘT MÀN HÌNH RIÊNG, TRONG KHI ĐÃ CÓ BIỂU MẪU TRONG POPOVER. Popover
 * rộng 280px là chỗ tốt cho *lối vào nhanh* — gõ hai ô rồi xong. Nó là chỗ tồi
 * cho mọi thứ còn lại: đăng ký cần ba ô cộng thanh đo độ mạnh và các dòng nhắc
 * lỗi, đổi mật khẩu cần ba ô nữa, và cả hai đều cần chỗ để giải thích luật.
 * Nhồi chúng vào một popover thì hoặc là chật, hoặc là phải cắt bớt phần giải
 * thích — mà phần giải thích chính là thứ làm biểu mẫu dùng được.
 *
 * Hai lối vào cùng tồn tại có chủ ý: popover cho người đã biết mình làm gì,
 * màn hình này cho lần đầu.
 *
 * KIỂM TRA TẠI CHỖ, NHƯNG CHỈ SAU KHI RỜI Ô. Báo "tên quá ngắn" ngay từ ký tự
 * đầu tiên là mắng người dùng vì chưa gõ xong. Ở đây lỗi chỉ hiện sau khi ô đã
 * mất tiêu điểm (`touched`) hoặc sau lần bấm gửi đầu tiên.
 */
function AuthScreen(): JSX.Element | null {
  const screen = useSessionStore((state) => state.screen)
  const close = useSessionStore((state) => state.closeScreen)
  const acquire = useOverlayStore((state) => state.acquire)
  const release = useOverlayStore((state) => state.release)

  useEffect(() => {
    if (!screen) {
      return undefined
    }
    // Khung nội dung web là một WebContentsView của Electron, nằm TRÊN mọi thứ
    // React vẽ. Không giành lớp phủ thì màn hình này bị trang web che mất.
    acquire()
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        close()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      release()
    }
  }, [screen, acquire, release, close])

  if (!screen) {
    return null
  }

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col overflow-y-auto bg-chrome"
      role="dialog"
      aria-modal="true"
      aria-label="Tài khoản VnSearch"
    >
      <header className="flex h-12 shrink-0 items-center gap-2 px-4">
        <VnSearchMark className="h-5 w-5" />
        <span className="text-[13px] font-semibold text-ink">VnSearch</span>
        <button onClick={close} className="icon-btn ml-auto" aria-label="Đóng" title="Đóng (Esc)">
          <CloseIcon className="h-4 w-4" strokeWidth={2.2} />
        </button>
      </header>

      <div className="flex flex-1 items-start justify-center px-6 pb-12 pt-4">
        <div className="w-full max-w-[380px]">
          {screen === 'password' ? <ChangePasswordForm /> : <SignInOrUpForm mode={screen} />}
        </div>
      </div>
    </div>
  )
}

const INPUT_CLASS =
  'h-10 w-full rounded-xl border bg-omni px-3 text-[13px] text-ink placeholder:text-faint ' +
  'transition focus:outline-none focus:ring-2'

function fieldClass(hasError: boolean): string {
  return (
    INPUT_CLASS +
    (hasError
      ? ' border-danger/60 focus:border-danger focus:ring-danger/15'
      : ' border-line focus:border-brand/50 focus:ring-brand/15')
  )
}

/** Đăng nhập và đăng ký dùng chung một biểu mẫu — chỉ khác vài ô và vài chữ. */
function SignInOrUpForm({ mode }: { mode: 'signin' | 'signup' }): JSX.Element {
  const signIn = useSessionStore((state) => state.signIn)
  const signUp = useSessionStore((state) => state.signUp)
  const openScreen = useSessionStore((state) => state.openScreen)
  const busy = useSessionStore((state) => state.busy)
  const serverError = useSessionStore((state) => state.error)

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const [submitted, setSubmitted] = useState(false)

  const isSignUp = mode === 'signup'
  const usernameError = validateUsername(username)
  // Khi ĐĂNG NHẬP không kiểm luật mật khẩu: luật có thể đã đổi kể từ lúc người
  // này tạo tài khoản, và chặn họ đăng nhập vì mật khẩu cũ "không đạt chuẩn
  // mới" là chặn nhầm hoàn toàn.
  const passwordError = isSignUp
    ? validatePassword(password)
    : password
      ? null
      : 'Hãy nhập mật khẩu.'
  const confirmationError = isSignUp ? validateConfirmation(password, confirmation) : null
  const strength = isSignUp && password ? passwordStrength(password) : null

  const show = (field: string): boolean => submitted || touched[field] === true
  const canSubmit =
    !busy &&
    !usernameError &&
    !passwordError &&
    (!isSignUp || (!!confirmation && !confirmationError))

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    setSubmitted(true)
    if (!canSubmit) {
      return
    }
    const ok = isSignUp ? await signUp(username, password) : await signIn(username, password)
    if (!ok) {
      setPassword('')
      setConfirmation('')
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="mb-6 text-center">
        <span className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-soft text-brand">
          <UserIcon className="h-7 w-7" />
        </span>
        <h1 className="mt-4 text-[20px] font-semibold text-ink">
          {isSignUp ? 'Tạo tài khoản VnSearch' : 'Đăng nhập VnSearch'}
        </h1>
        <p className="mt-1.5 text-[12.5px] leading-relaxed text-muted">
          {isSignUp
            ? 'Tài khoản mới luôn có vai trò Người dùng. Chỉ quản trị viên mới nâng được vai trò.'
            : 'Tìm kiếm không cần đăng nhập — đăng nhập chỉ mở thêm những gì cần biết bạn là ai.'}
        </p>
      </div>

      <div className="space-y-3.5">
        <div>
          <label
            htmlFor="auth-username"
            className="mb-1.5 block text-[12px] font-medium text-muted"
          >
            Tên tài khoản
          </label>
          <input
            id="auth-username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            onBlur={() => setTouched((t) => ({ ...t, username: true }))}
            autoFocus
            spellCheck={false}
            autoComplete="username"
            placeholder="vidu.nguyenvana"
            aria-invalid={show('username') && !!usernameError}
            className={fieldClass(show('username') && !!usernameError)}
          />
          {show('username') && usernameError && (
            <p role="alert" className="mt-1.5 text-[11.5px] text-danger">
              {usernameError}
            </p>
          )}
        </div>

        <div onBlur={() => setTouched((t) => ({ ...t, password: true }))}>
          <PasswordField
            id="auth-password"
            label="Mật khẩu"
            value={password}
            onChange={setPassword}
            autoComplete={isSignUp ? 'new-password' : 'current-password'}
            placeholder={isSignUp ? 'Ít nhất 8 ký tự' : ''}
            error={show('password') ? passwordError : null}
            hint={
              isSignUp && !password
                ? 'Một cụm nhiều từ dễ nhớ hơn và khó phá hơn một từ có thêm ký tự lạ.'
                : undefined
            }
          />
          {strength && <StrengthMeter strength={strength} />}
        </div>

        {isSignUp && (
          <div onBlur={() => setTouched((t) => ({ ...t, confirmation: true }))}>
            <PasswordField
              id="auth-confirmation"
              label="Nhập lại mật khẩu"
              value={confirmation}
              onChange={setConfirmation}
              autoComplete="new-password"
              error={show('confirmation') ? confirmationError : null}
            />
          </div>
        )}
      </div>

      {serverError && (
        <div
          role="alert"
          className="mt-4 rounded-xl border border-danger/25 bg-danger/5 px-3.5 py-2.5
                     text-[12.5px] leading-relaxed text-danger"
        >
          {serverError}
        </div>
      )}

      <button
        type="submit"
        disabled={busy}
        className="mt-5 flex h-10 w-full items-center justify-center rounded-xl text-[13px]
                   font-medium transition enabled:bg-brand enabled:text-white
                   enabled:hover:brightness-110 disabled:cursor-not-allowed disabled:bg-raised
                   disabled:text-faint focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-brand/60"
      >
        {busy ? <SpinnerIcon className="h-4 w-4" /> : isSignUp ? 'Tạo tài khoản' : 'Đăng nhập'}
      </button>

      <p className="mt-4 text-center text-[12px] text-muted">
        {isSignUp ? 'Đã có tài khoản?' : 'Chưa có tài khoản?'}{' '}
        <button
          type="button"
          onClick={() => openScreen(isSignUp ? 'signin' : 'signup')}
          className="font-medium text-brand underline-offset-2 hover:underline
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
        >
          {isSignUp ? 'Đăng nhập' : 'Đăng ký'}
        </button>
      </p>
    </form>
  )
}

/**
 * Thanh đo độ mạnh — chỉ để **gợi ý**, không chặn.
 *
 * Chặn theo thang này sẽ tái lập đúng thứ mà luật mật khẩu ở máy chủ cố tránh:
 * ép người dùng vào một khuôn dễ đoán. Và màu không phải kênh duy nhất — luôn
 * có nhãn chữ bên cạnh.
 */
function StrengthMeter({
  strength
}: {
  strength: { score: 0 | 1 | 2 | 3; label: string; hint: string }
}): JSX.Element {
  const colors = ['bg-danger', 'bg-warn', 'bg-warn', 'bg-success']
  return (
    <div className="mt-2">
      <div className="flex items-center gap-2">
        <div className="flex h-1 flex-1 gap-1" aria-hidden="true">
          {[0, 1, 2].map((segment) => (
            <div
              key={segment}
              className={
                'h-full flex-1 rounded-full ' +
                (segment < strength.score ? colors[strength.score] : 'bg-raised')
              }
            />
          ))}
        </div>
        <span className="w-16 text-right text-[11px] text-muted">{strength.label}</span>
      </div>
      <p className="mt-1 text-[11px] leading-snug text-faint">{strength.hint}</p>
    </div>
  )
}

/** Đổi mật khẩu: ba ô, và một lời cảnh báo về hệ quả. */
function ChangePasswordForm(): JSX.Element {
  const user = useSessionStore((state) => state.user)
  const change = useSessionStore((state) => state.changePassword)
  const busy = useSessionStore((state) => state.busy)
  const serverError = useSessionStore((state) => state.error)

  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [submitted, setSubmitted] = useState(false)

  const nextError = validatePassword(next)
  const sameAsOld =
    next && current && next === current ? 'Mật khẩu mới phải khác mật khẩu hiện tại.' : null
  const confirmationError = validateConfirmation(next, confirmation)
  const strength = next ? passwordStrength(next) : null
  const canSubmit =
    !busy && !!current && !nextError && !sameAsOld && !!confirmation && !confirmationError

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    setSubmitted(true)
    if (!canSubmit) {
      return
    }
    if (!(await change(current, next))) {
      setCurrent('')
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="mb-6 text-center">
        <span className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-soft text-brand">
          <ShieldCheckIcon className="h-7 w-7" />
        </span>
        <h1 className="mt-4 text-[20px] font-semibold text-ink">Đổi mật khẩu</h1>
        <p className="mt-1.5 text-[12.5px] leading-relaxed text-muted">
          Tài khoản <span className="font-medium text-ink">{user?.username}</span>. Mọi phiên đăng
          nhập <b>khác</b> sẽ bị đóng; thiết bị này thì không.
        </p>
      </div>

      <div className="space-y-3.5">
        <PasswordField
          id="password-current"
          label="Mật khẩu hiện tại"
          value={current}
          onChange={setCurrent}
          autoComplete="current-password"
          autoFocus
          hint="Phải nhập lại dù bạn đang đăng nhập — chi tiết ở dưới."
        />
        <div>
          <PasswordField
            id="password-new"
            label="Mật khẩu mới"
            value={next}
            onChange={setNext}
            autoComplete="new-password"
            placeholder="Ít nhất 8 ký tự"
            error={submitted ? (nextError ?? sameAsOld) : null}
          />
          {strength && <StrengthMeter strength={strength} />}
        </div>
        <PasswordField
          id="password-confirm"
          label="Nhập lại mật khẩu mới"
          value={confirmation}
          onChange={setConfirmation}
          autoComplete="new-password"
          error={confirmationError}
        />
      </div>

      {serverError && (
        <div
          role="alert"
          className="mt-4 rounded-xl border border-danger/25 bg-danger/5 px-3.5 py-2.5
                     text-[12.5px] leading-relaxed text-danger"
        >
          {serverError}
        </div>
      )}

      <button
        type="submit"
        disabled={busy}
        className="mt-5 flex h-10 w-full items-center justify-center rounded-xl text-[13px]
                   font-medium transition enabled:bg-brand enabled:text-white
                   enabled:hover:brightness-110 disabled:cursor-not-allowed disabled:bg-raised
                   disabled:text-faint focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-brand/60"
      >
        {busy ? <SpinnerIcon className="h-4 w-4" /> : 'Đổi mật khẩu'}
      </button>

      <p className="mt-5 rounded-xl border border-line bg-raised px-3.5 py-3 text-[11.5px] leading-relaxed text-faint">
        <b>Vì sao vẫn phải nhập mật khẩu hiện tại?</b> Vì đây là chỗ chặn một chiếc token bị đánh
        cắp. Không hỏi thì kẻ cầm token đổi được mật khẩu và khoá chính bạn ra ngoài — biến một
        phiên bị lộ tạm thời thành mất tài khoản vĩnh viễn.
      </p>
    </form>
  )
}

export default AuthScreen
