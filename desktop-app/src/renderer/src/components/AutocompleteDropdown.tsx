import type { JSX } from 'react'
import { SearchIcon } from './icons'

interface AutocompleteDropdownProps {
  suggestions: string[]
  highlightedIndex: number
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
}: AutocompleteDropdownProps): JSX.Element | null {
  if (suggestions.length === 0) {
    return null
  }

  const typed = query.trim().toLowerCase()

  return (
    <ul
      className="absolute top-full right-0 left-0 z-30 mt-2 max-h-80 animate-scale-in
                 overflow-auto rounded-2xl border border-line bg-surface py-1.5 shadow-pop"
    >
      {suggestions.map((suggestion, index) => {
        const matches = typed.length > 0 && suggestion.toLowerCase().startsWith(typed)
        const head = matches ? suggestion.slice(0, typed.length) : ''
        const tail = matches ? suggestion.slice(typed.length) : suggestion
        const highlighted = index === highlightedIndex

        return (
          <li
            key={suggestion}
            className="animate-fade-up"
            // Đổ xuống lần lượt, 22ms một dòng. Đủ để đọc ra một hướng chuyển
            // động, và đủ ngắn để dòng cuối cùng vẫn hiện xong trước khi ngón
            // tay kịp bấm — gợi ý mà đến chậm hơn thao tác thì thành chướng
            // ngại chứ không phải trợ giúp.
            style={{ animationDelay: `${index * 22}ms` }}
          >
            <button
              type="button"
              onMouseEnter={() => onHighlight(index)}
              onMouseDown={(event) => {
                event.preventDefault()
                onSelect(suggestion)
              }}
              className={
                'group relative flex w-full items-center gap-3 px-4 py-2 text-left text-[15px] ' +
                'transition-colors duration-150 ' +
                (highlighted ? 'bg-brand-soft text-ink' : 'text-ink hover:bg-raised')
              }
            >
              <span
                aria-hidden="true"
                className={
                  'absolute inset-y-1 left-0 w-[3px] rounded-full bg-brand transition-transform ' +
                  'duration-200 ' +
                  (highlighted ? 'scale-y-100' : 'scale-y-0')
                }
              />
              <SearchIcon
                className={
                  'h-4 w-4 shrink-0 transition-all duration-200 ' +
                  (highlighted ? 'scale-110 text-brand' : 'text-faint')
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
