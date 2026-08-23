import { useState, type FormEvent, type JSX } from 'react'
import { useAdminStore } from '../../store/adminStore'
import { useSessionStore } from '../../store/sessionStore'
import { KeyIcon, ShieldIcon, SpinnerIcon } from '../icons'

/**
 * Cửa vào khu vực quản trị — hai đường, tương ứng hai cơ chế xác thực của máy chủ.
 *
 *   TÀI KHOẢN   con người: đăng nhập, nhận token, mang vai trò USER/ADMIN
 *   KHOÁ TĨNH   công cụ: header X-API-Key, luôn là quyền quản trị đầy đủ
 *
 * Đường tài khoản đặt trước và mở sẵn vì nó là cách đúng cho một con người:
 * có danh tính, thu hồi được, hết hạn. Đường khoá nằm sau một nút bung ra —
 * vẫn có mặt (nó là lối vào dự phòng khi chưa có tài khoản nào, hoặc khi kho
 * tài khoản hỏng) nhưng không phải thứ mời gọi người dùng thường.
 *
 * ĐÂY LÀ MỘT CỬA, KHÔNG PHẢI Ổ KHOÁ. Ổ khoá nằm ở máy chủ. Cả hai biểu mẫu
 * dưới đây đều xác thực bằng cách GỌI THẬT một endpoint quản trị — không có
 * phép kiểm tra "cho có" nào ở phía giao diện, vì nếu có, người dùng sẽ vượt
 * qua nó rồi mới gặp lỗi ở màn hình sau, xa nơi họ gõ sai.
 */
function AdminLogin(): JSX.Element {
  const user = useSessionStore((state) => state.user)

  return (
    <div className="flex h-full items-center justify-center overflow-y-auto px-6 py-8">
      <div className="w-full max-w-md">
        <div className="flex flex-col items-center text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-soft text-brand">
            <ShieldIcon className="h-7 w-7" />
          </span>
          <h2 className="mt-4 text-[19px] font-semibold text-ink">Khu vực quản trị</h2>
          {user ? (
            // Đã đăng nhập nhưng vai trò USER: nói RÕ vì sao vẫn không vào
            // được. Hiện lại một biểu mẫu đăng nhập ở đây sẽ khiến họ gõ lại
            // đúng mật khẩu vừa gõ và không hiểu vì sao vẫn không vào được.
            <p className="mt-1.5 max-w-sm text-[13px] leading-relaxed text-muted">
              Bạn đang đăng nhập là <span className="font-medium text-ink">{user.username}</span>{' '}
              với vai trò <span className="font-medium text-ink">Người dùng</span>. Bảng số liệu này
              thuộc vai trò <span className="font-medium text-ink">Quản trị viên</span> — hãy nhờ
              một quản trị viên nâng vai trò cho tài khoản của bạn.
            </p>
          ) : (
            <p className="mt-1.5 max-w-sm text-[13px] leading-relaxed text-muted">
              Đăng nhập bằng tài khoản có vai trò{' '}
              <span className="font-medium text-ink">Quản trị viên</span> để xem số liệu.
            </p>
          )}
        </div>

        {!user && <AccountForm />}

        <ApiKeyForm expanded={!!user} />
      </div>
    </div>
  )
}

const INPUT_CLASS =
  'h-10 w-full rounded-xl border border-line bg-omni px-3 text-[13px] text-ink ' +
  'placeholder:text-faint transition focus:border-brand/50 focus:outline-none ' +
  'focus:ring-2 focus:ring-brand/15'

/** Đường thứ nhất: tài khoản và mật khẩu. */
function AccountForm(): JSX.Element {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const signIn = useSessionStore((state) => state.signIn)
  const busy = useSessionStore((state) => state.busy)
  const error = useSessionStore((state) => state.error)

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    if (await signIn(username, password)) {
      setPassword('')
    }
  }

  return (
    <form onSubmit={submit} className="mt-6">
      <label htmlFor="admin-username" className="mb-1.5 block text-[12px] font-medium text-muted">
        Tài khoản quản trị
      </label>
      <div className="space-y-2">
        <input
          id="admin-username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          autoFocus
          spellCheck={false}
          autoComplete="username"
          placeholder="Tên tài khoản"
          className={INPUT_CLASS}
        />
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          placeholder="Mật khẩu"
          className={INPUT_CLASS}
          aria-label="Mật khẩu"
        />
      </div>

      <button
        type="submit"
        disabled={busy || !username.trim() || !password}
        className="mt-3 flex h-10 w-full items-center justify-center rounded-xl text-[13px]
                   font-medium transition enabled:bg-brand enabled:text-white
                   enabled:hover:brightness-110 disabled:cursor-not-allowed disabled:bg-raised
                   disabled:text-faint focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-brand/60"
      >
        {busy ? <SpinnerIcon className="h-4 w-4" /> : 'Đăng nhập'}
      </button>

      {error && (
        <p role="alert" className="mt-2.5 text-[12.5px] leading-relaxed text-danger">
          {error}
        </p>
      )}
    </form>
  )
}

/** Đường thứ hai: khoá tĩnh, dành cho công cụ vận hành và cho lối vào dự phòng. */
function ApiKeyForm({ expanded }: { expanded: boolean }): JSX.Element {
  const [open, setOpen] = useState(expanded)
  const [key, setKey] = useState('')
  const signInWithKey = useAdminStore((state) => state.signInWithKey)
  const verifying = useAdminStore((state) => state.verifying)
  const error = useAdminStore((state) => state.error)

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault()
    if (await signInWithKey(key)) {
      // Xoá khỏi ô nhập ngay khi đã đổi được lấy quyền: không để khoá nằm hiển
      // thị trên màn hình sau lưng người dùng.
      setKey('')
    }
  }

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className="mt-4 w-full text-center text-[12px] text-muted underline-offset-2
                   hover:text-ink hover:underline"
      >
        Hoặc dùng khoá quản trị (dành cho công cụ vận hành)
      </button>
    )
  }

  return (
    <form onSubmit={submit} className="mt-6 rounded-2xl border border-line bg-raised/60 p-4">
      <label htmlFor="admin-key" className="mb-1.5 block text-[12px] font-medium text-muted">
        Khoá quản trị (<code className="text-brand">ADMIN_API_KEY</code> của máy chủ)
      </label>
      <div className="flex items-center gap-2">
        <div className="relative min-w-0 flex-1">
          <KeyIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
          {/* type="password": khoá không hiện ra khi trình chiếu màn hình. */}
          <input
            id="admin-key"
            type="password"
            value={key}
            onChange={(event) => setKey(event.target.value)}
            spellCheck={false}
            autoComplete="off"
            placeholder="Dán khoá vào đây"
            className={INPUT_CLASS + ' pl-9'}
          />
        </div>
        <button
          type="submit"
          disabled={verifying || !key.trim()}
          className="h-10 shrink-0 rounded-xl px-4 text-[13px] font-medium transition
                     enabled:bg-brand enabled:text-white enabled:hover:brightness-110
                     disabled:cursor-not-allowed disabled:bg-surface disabled:text-faint
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
        >
          {verifying ? <SpinnerIcon className="h-4 w-4" /> : 'Xác thực'}
        </button>
      </div>

      {error && (
        <p role="alert" className="mt-2.5 text-[12.5px] leading-relaxed text-danger">
          {error}
        </p>
      )}

      <p className="mt-3 text-[11.5px] leading-relaxed text-faint">
        Khoá này không hết hạn và không thu hồi được, nên nó{' '}
        <b>chỉ được giữ trong bộ nhớ của phiên làm việc này</b> — đóng ứng dụng là phải nhập lại.
        Token đăng nhập bằng tài khoản thì ngược lại: hết hạn sau 12 giờ và huỷ được, nên nó được
        lưu để bạn không phải đăng nhập lại mỗi lần mở ứng dụng.
      </p>
    </form>
  )
}

export default AdminLogin
