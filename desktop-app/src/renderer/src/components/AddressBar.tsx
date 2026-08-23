import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type JSX,
  type KeyboardEvent,
  type SyntheticEvent
} from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useBookmarkStore, collectBookmarks } from '../store/bookmarkStore'
import { useSearchViewStore } from '../store/searchViewStore'
import AutocompleteDropdown from './AutocompleteDropdown'
import { suggest } from '../lib/searchApi'
import { historyApi } from '../lib/userDataApi'
import { useSettingsStore } from '../store/settingsStore'
import { CloseIcon, GlobeIcon, LockIcon, StarIcon, VnSearchMark } from './icons'

const SUGGEST_DEBOUNCE_MS = 180

function looksLikeUrl(text: string): boolean {
  const trimmed = text.trim()
  if (/^https?:\/\//i.test(trimmed)) {
    return true
  }
  return !trimmed.includes(' ') && /\.[a-z]{2,}(\/.*)?$/i.test(trimmed)
}

function AddressBar(): JSX.Element {
  const tabs = useTabStore((state) => state.tabs)
  const activeTabId = useTabStore((state) => state.activeTabId)
  const navigate = useTabStore((state) => state.navigate)
  const query = useSearchViewStore((state) => state.query)
  const runSearch = useSearchViewStore((state) => state.runSearch)
  const clearSearch = useSearchViewStore((state) => state.clear)
  const bookmarkRoot = useBookmarkStore((state) => state.root)
  const toggleBookmark = useBookmarkStore((state) => state.toggleBookmark)

  const activeTab = tabs.find((tab) => tab.id === activeTabId)
  const displayedUrl = !activeTab || activeTab.url === HOME_URL ? '' : activeTab.url

  const canonicalText = query ?? displayedUrl
  const [typedValue, setTypedValue] = useState('')
  const [focused, setFocused] = useState(false)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const inputRef = useRef<HTMLInputElement>(null)

  const inputValue = focused ? typedValue : canonicalText
  const suggestible = focused && typedValue.trim().length >= 2 && !looksLikeUrl(typedValue)

  const goiYTuLichSu = useSettingsStore((state) => state.tuyChon.goiYTuLichSu)

  useEffect(() => {
    if (!suggestible) {
      return undefined
    }

    const timer = window.setTimeout(() => {
      const tuChiMuc = suggest(typedValue).catch(() => [] as string[])
      const tuLichSu = goiYTuLichSu
        ? historyApi
            .goiY(typedValue, 4)
            .then((items) => items.map((item) => item.query))
            .catch(() => [] as string[])
        : Promise.resolve([] as string[])

      void Promise.all([tuLichSu, tuChiMuc]).then(([lichSu, chiMuc]) => {
        const daCo = new Set<string>()
        const gop: string[] = []
        for (const item of [...lichSu, ...chiMuc]) {
          const khoa = item.trim().toLowerCase()
          if (khoa && !daCo.has(khoa)) {
            daCo.add(khoa)
            gop.push(item)
          }
        }
        setSuggestions(gop.slice(0, 8))
      })
    }, SUGGEST_DEBOUNCE_MS)

    return () => window.clearTimeout(timer)
  }, [typedValue, suggestible, goiYTuLichSu])

  const bookmarked = useMemo(() => {
    if (!displayedUrl) {
      return false
    }
    return collectBookmarks(bookmarkRoot).some((bookmark) => bookmark.url === displayedUrl)
  }, [bookmarkRoot, displayedUrl])

  const isSecure = /^https:\/\//i.test(displayedUrl)
  const searchMode = !displayedUrl || !!query

  function submitValue(text: string): void {
    const value = text.trim()
    if (!value) {
      return
    }

    setSuggestions([])
    setHighlighted(-1)

    if (looksLikeUrl(value)) {
      clearSearch()
      navigate(/^https?:\/\//i.test(value) ? value : `https://${value}`)
    } else {
      navigate(HOME_URL)
      runSearch(value)
    }
    inputRef.current?.blur()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'ArrowDown' && suggestions.length > 0) {
      event.preventDefault()
      setHighlighted((index) => (index + 1) % suggestions.length)
    } else if (event.key === 'ArrowUp' && suggestions.length > 0) {
      event.preventDefault()
      setHighlighted((index) => (index <= 0 ? suggestions.length - 1 : index - 1))
    } else if (event.key === 'Escape') {
      setSuggestions([])
      setHighlighted(-1)
      setTypedValue(canonicalText)
    }
  }

  function handleSubmit(event: SyntheticEvent): void {
    event.preventDefault()
    submitValue(highlighted >= 0 ? suggestions[highlighted] : inputValue)
  }

  return (
    <form onSubmit={handleSubmit} className="relative flex min-w-0 flex-1 items-center gap-1.5">
      <div
        className={
          'flex h-10 min-w-0 flex-1 items-center gap-2.5 rounded-full border px-3.5 ' +
          'transition-all duration-300 ' +
          (focused
            ? 'border-brand/50 bg-omni shadow-glow ring-4 ring-brand/12'
            : 'border-transparent bg-omni hover:border-brand/20 hover:brightness-110')
        }
      >
        <span
          className={
            'flex shrink-0 items-center transition-transform duration-300 ' +
            (focused ? 'scale-110' : '')
          }
        >
          {searchMode ? (
            <VnSearchMark className="h-[18px] w-[18px] text-muted" />
          ) : isSecure ? (
            <LockIcon className="h-[15px] w-[15px] text-success" />
          ) : (
            <GlobeIcon className="h-[16px] w-[16px] text-warn" />
          )}
        </span>

        <input
          id="omnibox"
          ref={inputRef}
          value={inputValue}
          onChange={(event) => {
            setTypedValue(event.target.value)
            setHighlighted(-1)
          }}
          onKeyDown={handleKeyDown}
          onFocus={(event) => {
            setTypedValue(canonicalText)
            setFocused(true)
            event.target.select()
          }}
          onBlur={() => {
            setFocused(false)
            setHighlighted(-1)
          }}
          className="min-w-0 flex-1 bg-transparent text-[13.5px] text-ink placeholder:text-faint focus:outline-none"
          placeholder="Tìm kiếm hoặc nhập địa chỉ web"
          spellCheck={false}
          aria-label="Ô địa chỉ và tìm kiếm"
        />

        {inputValue && (
          <button
            type="button"
            onClick={() => {
              setTypedValue('')
              setSuggestions([])
              inputRef.current?.focus()
            }}
            className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-faint
                       transition hover:bg-line hover:text-ink"
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
            toggleBookmark(activeTab.url, activeTab.title)
          }
        }}
        disabled={!displayedUrl}
        className={'icon-btn ' + (bookmarked ? 'text-amber-500 hover:text-amber-500' : '')}
        aria-label="Đánh dấu trang"
        title={bookmarked ? 'Bỏ đánh dấu' : 'Đánh dấu trang (Ctrl+D)'}
      >
        <StarIcon className="h-[18px] w-[18px]" filled={bookmarked} />
      </button>

      <AutocompleteDropdown
        suggestions={suggestible ? suggestions : []}
        highlightedIndex={highlighted}
        query={typedValue}
        onSelect={(suggestion) => {
          setTypedValue(suggestion)
          submitValue(suggestion)
        }}
        onHighlight={setHighlighted}
      />
    </form>
  )
}

export default AddressBar
