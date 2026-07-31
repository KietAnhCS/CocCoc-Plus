import { SearchIcon } from './icons'

/**
 * Dropdown gợi ý bên dưới ô tìm kiếm. Điều hướng bằng phím mũi tên và chọn
 * bằng Enter do component cha xử lý (qua highlightedIndex/onSelect) — ở đây
 * chỉ hiển thị và bắt sự kiện chuột.
 *
 * Phần người dùng đã gõ được để nhạt, phần trie gợi ý thêm mới in đậm —
 * mắt bắt ngay được "máy đoán thêm chữ gì", giống ô địa chỉ của Chrome.
 */
interface Props {
  suggestions: string[]
  highlightedIndex: number
  /** Chuỗi người dùng đang gõ, để tô đậm phần được gợi ý thêm. */
  query?: string
  onSelect: (value: string) => void
  onHighlight: (index: number) => void
}

function AutocompleteDropdown({
  suggestions,
  highlightedIndex,
  query = '',
  onSelect,
  onHighlight
}: Props): JSX.Element | null {
  if (suggestions.length === 0) {
    return null
  }

  const typed = query.trim().toLowerCase()

  return (
    <ul
      className="absolute left-0 right-0 top-full z-30 mt-2 max-h-80 animate-scale-in overflow-auto
                 rounded-2xl border border-line bg-surface py-1.5 shadow-pop"
    >
      {suggestions.map((suggestion, index) => {
        const matches = typed.length > 0 && suggestion.toLowerCase().startsWith(typed)
        const head = matches ? suggestion.slice(0, typed.length) : ''
        const tail = matches ? suggestion.slice(typed.length) : suggestion

        return (
          <li key={suggestion}>
            <button
              type="button"
              onMouseEnter={() => onHighlight(index)}
              // onMouseDown (không phải onClick) để chạy TRƯỚC sự kiện blur của
              // input, tránh dropdown biến mất trước khi kịp nhận lựa chọn.
              onMouseDown={(e) => {
                e.preventDefault()
                onSelect(suggestion)
              }}
              className={
                'flex w-full items-center gap-3 px-4 py-2 text-left text-[15px] transition-colors ' +
                (index === highlightedIndex ? 'bg-brand-soft text-ink' : 'text-ink hover:bg-raised')
              }
            >
              <SearchIcon
                className={
                  'h-4 w-4 shrink-0 ' + (index === highlightedIndex ? 'text-brand' : 'text-faint')
                }
              />
              <span className="truncate">
                <span className="text-muted">{head}</span>
                <span className="font-semibold">{tail}</span>
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

export default AutocompleteDropdown
