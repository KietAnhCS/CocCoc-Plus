import { useEffect, useRef, useState } from 'react'
import AutocompleteDropdown from './AutocompleteDropdown'
import { suggest } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'

/**
 * Trang chu mac dinh kieu Google/Coc Coc. Go vao o tim kiem -> debounce
 * 200ms -> goi /api/suggest -> AutocompleteDropdown. Dieu huong dropdown
 * bang phim mui ten, Enter de chon/tim. Enter (khong co goi y duoc chon)
 * -> chuyen sang SearchResultList qua searchViewStore.
 */
function SearchHomePage(): JSX.Element {
  const [text, setText] = useState('')
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const debounceRef = useRef<number | undefined>(undefined)

  useEffect(() => {
    if (!text.trim()) {
      setSuggestions([])
      return undefined
    }
    window.clearTimeout(debounceRef.current)
    debounceRef.current = window.setTimeout(() => {
      suggest(text, 8)
        .then(setSuggestions)
        .catch(() => setSuggestions([]))
    }, 200)
    return () => window.clearTimeout(debounceRef.current)
  }, [text])

  function runSearch(q: string): void {
    const trimmed = q.trim()
    if (!trimmed) {
      return
    }
    setSuggestions([])
    setHighlighted(-1)
    setQuery(trimmed)
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>): void {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setHighlighted((h) => Math.min(h + 1, suggestions.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHighlighted((h) => Math.max(h - 1, -1))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      runSearch(highlighted >= 0 ? suggestions[highlighted] : text)
    } else if (e.key === 'Escape') {
      setSuggestions([])
      setHighlighted(-1)
    }
  }

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4">
      <h1 className="text-3xl font-semibold text-blue-600">VnSearch</h1>
      <div className="relative w-full max-w-xl">
        <input
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setHighlighted(-1)
          }}
          onKeyDown={handleKeyDown}
          className="w-full rounded-full border border-gray-300 px-5 py-3 shadow-sm focus:border-blue-400 focus:outline-none"
          placeholder="Tìm kiếm..."
          autoFocus
        />
        <AutocompleteDropdown
          suggestions={suggestions}
          highlightedIndex={highlighted}
          onSelect={(s) => {
            setText(s)
            runSearch(s)
          }}
          onHighlight={setHighlighted}
        />
      </div>
    </div>
  )
}

export default SearchHomePage
