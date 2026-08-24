import { useEffect, useRef, type JSX, type KeyboardEvent } from 'react'
import { useFindStore } from '../store/findStore'
import { useTabStore } from '../store/tabStore'
import { CloseIcon, SearchIcon } from './icons'

/**
 * Ô "Tìm trong trang" (Ctrl+F).
 *
 * <p>Nổi ở góc trên bên phải của vùng nội dung, đúng chỗ mà mọi trình duyệt
 * đặt nó — thói quen của người dùng đáng giá hơn một bố cục sáng tạo.
 *
 * <h2>Vì sao nó nằm trong vỏ giao diện chứ không trong trang</h2>
 *
 * <p>Trang web là nội dung KHÔNG TIN CẬY chạy trong một {@code WebContentsView}
 * riêng, không có preload và không chạm được tới IPC. Ô tìm phải nói chuyện
 * với tiến trình chính ({@code webContents.findInPage}), nên nó thuộc về vỏ.
 * Hệ quả về bố cục: nó vẽ ĐÈ lên vùng nội dung chứ không nằm trong đó, và toạ
 * độ phải khớp với hằng số {@code CHROME_HEIGHT} bên {@code tabManager.ts}.
 */
function FindBar(): JSX.Element | null {
  const open = useFindStore((s) => s.open)
  const query = useFindStore((s) => s.query)
  const activeMatch = useFindStore((s) => s.activeMatch)
  const matches = useFindStore((s) => s.matches)
  const datTuKhoa = useFindStore((s) => s.datTuKhoa)
  const dongO = useFindStore((s) => s.dongO)
  const ketQuaTiepTheo = useFindStore((s) => s.ketQuaTiepTheo)
  const ketQuaTruocDo = useFindStore((s) => s.ketQuaTruocDo)

  const activeTabId = useTabStore((s) => s.activeTabId)
  const inputRef = useRef<HTMLInputElement>(null)

  // Mở ra là con trỏ nhảy vào ô và CHỌN SẴN từ khoá cũ. Chọn sẵn để người dùng
  // gõ đè được ngay — đúng hành vi của Ctrl+F ở mọi nơi khác. Chỉ đặt con trỏ
  // mà không chọn thì họ phải tự xoá từ khoá cũ trước.
  useEffect(() => {
    if (open) {
      inputRef.current?.focus()
      inputRef.current?.select()
    }
  }, [open])

  if (!open) {
    return null
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'Enter') {
      event.preventDefault()
      // Shift+Enter đi ngược — quy ước chung của mọi hộp tìm kiếm.
      if (event.shiftKey) {
        ketQuaTruocDo(activeTabId)
      } else {
        ketQuaTiepTheo(activeTabId)
      }
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      dongO(activeTabId)
    }
  }

  return (
    <div
      className="absolute right-4 top-2 z-40 flex items-center gap-1 rounded-xl border
                 border-line bg-surface px-2 py-1.5 shadow-pop"
      role="search"
      aria-label="Tìm trong trang"
    >
      <SearchIcon className="h-4 w-4 shrink-0 text-faint" />

      <input
        ref={inputRef}
        value={query}
        onChange={(event) => datTuKhoa(activeTabId, event.target.value)}
        onKeyDown={handleKeyDown}
        className="h-7 w-52 bg-transparent px-1 text-[13px] text-ink outline-none
                   placeholder:text-faint"
        placeholder="Tìm trong trang…"
        spellCheck={false}
        aria-label="Từ khoá cần tìm trong trang"
      />

      {/* Bộ đếm chỉ hiện khi đã gõ gì đó. Hiện "0/0" cho một ô trống là một
          câu trả lời cho câu hỏi chưa ai đặt. */}
      {query && (
        <span
          className={
            'shrink-0 px-1 text-[12px] tabular-nums ' +
            (matches === 0 ? 'text-danger' : 'text-faint')
          }
          aria-live="polite"
        >
          {matches === 0 ? 'Không thấy' : `${activeMatch}/${matches}`}
        </span>
      )}

      <NavButton
        onClick={() => ketQuaTruocDo(activeTabId)}
        disabled={matches === 0}
        label="Kết quả trước"
      >
        ↑
      </NavButton>
      <NavButton
        onClick={() => ketQuaTiepTheo(activeTabId)}
        disabled={matches === 0}
        label="Kết quả tiếp theo"
      >
        ↓
      </NavButton>

      <button
        onClick={() => dongO(activeTabId)}
        className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-muted
                   transition hover:bg-line hover:text-ink focus-visible:outline-none
                   focus-visible:ring-2 focus-visible:ring-brand/60"
        aria-label="Đóng ô tìm kiếm"
        title="Đóng (Esc)"
      >
        <CloseIcon className="h-3.5 w-3.5" strokeWidth={2.2} />
      </button>
    </div>
  )
}

function NavButton({
  onClick,
  disabled,
  label,
  children
}: {
  onClick: () => void
  disabled: boolean
  label: string
  children: React.ReactNode
}): JSX.Element {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-[13px]
                 text-muted transition hover:bg-line hover:text-ink
                 disabled:pointer-events-none disabled:text-faint/40
                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
      aria-label={label}
      title={label}
    >
      {children}
    </button>
  )
}

export default FindBar
