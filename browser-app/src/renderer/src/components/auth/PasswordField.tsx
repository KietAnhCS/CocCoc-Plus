import { useState, type JSX } from 'react'
import { EyeIcon, EyeOffIcon } from '../icons'

/**
 * Ô mật khẩu có nút hiện/ẩn.
 *
 * VÌ SAO CÓ NÚT HIỆN. Ô mật khẩu che ký tự để chống người đứng sau lưng nhìn
 * trộm — nhưng cái giá là người gõ cũng không tự kiểm tra được mình vừa gõ gì.
 * Trên bàn phím lạ hoặc với một mật khẩu dài, đó là nguyên nhân số một của
 * "đăng nhập sai mà không hiểu vì sao". Nút hiện trả lại quyền quyết định cho
 * người dùng: họ biết lúc nào quanh mình an toàn, giao diện thì không.
 *
 * MẶC ĐỊNH LUÔN LÀ ẨN, và trạng thái hiện KHÔNG được nhớ giữa các lần mở — một
 * ô mật khẩu tự động hiện ở lần sau là một cái bẫy.
 */
function PasswordField({
  id,
  label,
  value,
  onChange,
  placeholder,
  autoComplete,
  autoFocus,
  error,
  hint
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  autoComplete: 'current-password' | 'new-password'
  autoFocus?: boolean
  error?: string | null
  hint?: string
}): JSX.Element {
  const [visible, setVisible] = useState(false)

  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-[12px] font-medium text-muted">
        {label}
      </label>
      <div className="relative">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          autoComplete={autoComplete}
          autoFocus={autoFocus}
          spellCheck={false}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
          className={
            'h-10 w-full rounded-xl border bg-omni pl-3 pr-10 text-[13px] text-ink ' +
            'placeholder:text-faint transition focus:outline-none focus:ring-2 ' +
            (error
              ? 'border-danger/60 focus:border-danger focus:ring-danger/15'
              : 'border-line focus:border-brand/50 focus:ring-brand/15')
          }
        />
        <button
          type="button"
          onClick={() => setVisible((shown) => !shown)}
          // KHÔNG đặt tabIndex={-1}. Bản đầu có, với lý do "đỡ chen một nút phụ
          // vào giữa ô mật khẩu và nút gửi" — đánh đổi đó SAI: nó khiến người
          // chỉ dùng bàn phím không cách nào bật được chế độ hiện mật khẩu, tức
          // là mất hẳn một chức năng chứ không phải chậm hơn một nhịp. Tiết
          // kiệm một lần nhấn Tab không đáng đổi lấy điều đó.
          className="absolute right-1 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center
                     justify-center rounded-lg text-faint transition hover:bg-raised
                     hover:text-ink focus-visible:outline-none focus-visible:ring-2
                     focus-visible:ring-brand/50"
          aria-label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
          title={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
        >
          {visible ? (
            <EyeOffIcon className="h-[17px] w-[17px]" />
          ) : (
            <EyeIcon className="h-[17px] w-[17px]" />
          )}
        </button>
      </div>

      {error ? (
        <p id={`${id}-error`} role="alert" className="mt-1.5 text-[11.5px] text-danger">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1.5 text-[11.5px] text-faint">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

export default PasswordField
