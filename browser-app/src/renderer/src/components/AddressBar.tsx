import { useEffect, useRef, useState } from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useBookmarkStore } from '../store/bookmarkStore'
import { useSearchViewStore } from '../store/searchViewStore'
import AutocompleteDropdown from './AutocompleteDropdown'
import { suggest } from '../lib/searchApi'
import { CloseIcon, GlobeIcon, LockIcon, SearchIcon, StarIcon } from './icons'

/** Nhận diện một chuỗi gõ vào là URL (có dấu chấm TLD, không khoảng trắng) hay từ khoá tìm kiếm. */
function looksLikeUrl(text: string): boolean {
  const trimmed = text.trim()
  if (/^https?:\/\//i.test(trimmed)) {
    return true
  }
  return !trimmed.includes(' ') && /\.[a-z]{2,}(\/.*)?$/i.test(trimmed)
}

/**
 * Ô địa chỉ đa năng (omnibox): URL thì điều hướng, từ khoá thì bật
 * SearchResultList. Khi đang gõ từ khoá, ô này cũng gọi /api/suggest và đổ
 * gợi ý xuống dropdown — đúng hành vi người dùng chờ đợi ở một trình duyệt
 * thật, và tận dụng lại đúng Trie đã cài ở backend.
 */
function AddressBar(): JSX.Element {
  const tabs = useTabStore((s) => s.tabs)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const navigate = useTabStore((s) => s.navigate)
  const query = useSearchViewStore((s) => s.query)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const isBookmarked = useBookmarkStore((s) => s.isBookmarked)
  const toggleBookmark = useBookmarkStore((s) => s.toggleBookmark)
  // Đọc `root` để component vẽ lại ngay khi cây bookmark đổi (ngôi sao phải
  // đổi màu tức thì); bản thân giá trị không dùng tới ở đây.
  useBookmarkStore((s) => s.root)

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const displayedUrl = activeTab?.url === HOME_URL ? '' : (activeTab?.url ?? '')
  const [inputValue, setInputValue] = useState(query ?? displayedUrl)
  const [focused, setFocused] = useState(false)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const inputRef = useRef<HTMLInputElement>(null)
  const debounceRef = useRef<number | undefined>(undefined)

  useEffect(() => {
    setInputValue(query ?? displayedUrl)
    // Chỉ đồng bộ lại khi tab/url/query thực sự thay đổi từ bên ngoài (không phải lúc gõ).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTabId, displayedUrl, query])

  useEffect(() => {
    const text = inputValue.trim()
    // Đang gõ một địa chỉ thì gợi ý từ khoá chỉ tổ vướng.
    if (!focused || !text || looksLikeUrl(text)) {
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
  }, [inputValue, focused])

  const bookmarked = activeTab && activeTab.url !== HOME_URL ? isBookmarked(activeTab.url) : false
  const isSecure = /^https:\/\//i.test(displayedUrl)
  const searchMode = !displayedUrl || !!query

  function run(text: string): void {
    const trimmed = text.trim()
    if (!trimmed) {
      return
    }
    setSuggestions([])
    setHighlighted(-1)
    inputRef.current?.blur()

    if (looksLikeUrl(trimmed)) {
      const url = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`
      setQuery(null)
      navigate(url)
    } else {
      setQuery(trimmed)
      navigate(HOME_URL)
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>): void {
    if (e.key === 'ArrowDown' && suggestions.length > 0) {
      e.preventDefault()
      setHighlighted((h) => Math.min(h + 1, suggestions.length - 1))
    } else if (e.key === 'ArrowUp' && suggestions.length > 0) {
      e.preventDefault()
      setHighlighted((h) => Math.max(h - 1, -1))
    } else if (e.key === 'Escape') {
      setSuggestions([])
      setHighlighted(-1)
      inputRef.current?.blur()
    }
  }

  function handleSubmit(e: React.FormEvent): void {
    e.preventDefault()
    run(highlighted >= 0 ? suggestions[highlighted] : inputValue)
  }

  return (
    <form onSubmit={handleSubmit} className="relative flex min-w-0 flex-1 items-center gap-1.5">
      <div
        className={
          'flex h-9 min-w-0 flex-1 items-center gap-2 rounded-full border px-3 transition-all duration-200 ' +
          (focused
            ? 'border-brand/45 bg-surface shadow-omni ring-4 ring-brand/10'
            : 'border-transparent bg-raised hover:bg-raised/70')
        }
      >
        <span className="shrink-0 text-faint">
          {searchMode ? (
            <SearchIcon className="h-[17px] w-[17px]" />
          ) : isSecure ? (
            <LockIcon className="h-[15px] w-[15px] text-success" />
          ) : (
            <GlobeIcon className="h-[16px] w-[16px] text-warn" />
          )}
        </span>

        <input
          // Ctrl+L / Alt+D nhảy về đây — xem lib/useBrowserShortcuts.ts.
          id="omnibox"
          ref={inputRef}
          value={inputValue}
          onChange={(e) => {
            setInputValue(e.target.value)
            setHighlighted(-1)
          }}
          onKeyDown={handleKeyDown}
          onFocus={(e) => {
            setFocused(true)
            e.target.select() // chọn sẵn toàn bộ như mọi trình duyệt
          }}
          onBlur={() => {
            setFocused(false)
            setHighlighted(-1)
          }}
          className="min-w-0 flex-1 bg-transparent text-[13.5px] text-ink placeholder:text-faint focus:outline-none"
          placeholder="Tìm trên VnSearch hoặc nhập địa chỉ"
          spellCheck={false}
          aria-label="Ô địa chỉ và tìm kiếm"
        />

        {inputValue && (
          <button
            type="button"
            onClick={() => {
              setInputValue('')
              setSuggestions([])
              inputRef.current?.focus()
            }}
            className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-faint transition hover:bg-line hover:text-ink"
            aria-label="Xoá nội dung"
            title="Xoá"
          >
            <CloseIcon className="h-3 w-3" strokeWidth={2.2} />
          </button>
        )}
      </div>

      <button
        type="button"
        onClick={() => {
          if (activeTab && activeTab.url !== HOME_URL) {
            toggleBookmark(activeTab.title, activeTab.url)
          }
        }}
        disabled={!activeTab || activeTab.url === HOME_URL}
        className={'icon-btn ' + (bookmarked ? 'text-amber-500 hover:text-amber-500' : '')}
        aria-label="Đánh dấu trang"
        title={bookmarked ? 'Bỏ đánh dấu' : 'Đánh dấu trang (Ctrl+D)'}
      >
        <StarIcon className="h-[18px] w-[18px]" filled={bookmarked} />
      </button>

      <AutocompleteDropdown
        suggestions={suggestions}
        highlightedIndex={highlighted}
        query={inputValue}
        onSelect={(s) => {
          setInputValue(s)
          run(s)
        }}
        onHighlight={setHighlighted}
      />
    </form>
  )
}

export default AddressBar
