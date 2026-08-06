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
          <li key={suggestion}>
            <button
              type="button"
              onMouseEnter={() => onHighlight(index)}
              onMouseDown={(event) => {
                event.preventDefault()
                onSelect(suggestion)
              }}
              className={
                'flex w-full items-center gap-3 px-4 py-2 text-left text-[15px] transition-colors ' +
                (highlighted ? 'bg-brand-soft text-ink' : 'text-ink hover:bg-raised')
              }
            >
              <SearchIcon
                className={'h-4 w-4 shrink-0 ' + (highlighted ? 'text-brand' : 'text-faint')}
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
