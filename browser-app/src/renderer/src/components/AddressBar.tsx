import { useEffect, useState } from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useBookmarkStore } from '../store/bookmarkStore'
import { useSearchViewStore } from '../store/searchViewStore'

/** Nhan dien mot chuoi go vao la URL (co dau cham TLD, khong khoang trang) hay tu khoa tim kiem. */
function looksLikeUrl(text: string): boolean {
  const trimmed = text.trim()
  if (/^https?:\/\//i.test(trimmed)) {
    return true
  }
  return !trimmed.includes(' ') && /\.[a-z]{2,}(\/.*)?$/i.test(trimmed)
}

/** O nhap URL/tu khoa + nut bookmark. URL -> browser.navigate; tu khoa -> SearchResultList (PHASE 9). */
function AddressBar(): JSX.Element {
  const tabs = useTabStore((s) => s.tabs)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const navigate = useTabStore((s) => s.navigate)
  const query = useSearchViewStore((s) => s.query)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const isBookmarked = useBookmarkStore((s) => s.isBookmarked)
  const addBookmark = useBookmarkStore((s) => s.addBookmark)
  const removeNode = useBookmarkStore((s) => s.removeNode)
  const root = useBookmarkStore((s) => s.root)

  const activeTab = tabs.find((t) => t.id === activeTabId)
  const displayedUrl = activeTab?.url === HOME_URL ? '' : (activeTab?.url ?? '')
  const [inputValue, setInputValue] = useState(query ?? displayedUrl)

  useEffect(() => {
    setInputValue(query ?? displayedUrl)
    // Chi dong bo lai khi tab/url/query thuc su thay doi tu ben ngoai (khong phai luc go).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTabId, displayedUrl, query])

  const bookmarked = activeTab && activeTab.url !== HOME_URL ? isBookmarked(activeTab.url) : false

  function handleSubmit(e: React.FormEvent): void {
    e.preventDefault()
    const text = inputValue.trim()
    if (!text) {
      return
    }
    if (looksLikeUrl(text)) {
      const url = /^https?:\/\//i.test(text) ? text : `https://${text}`
      setQuery(null)
      navigate(url)
    } else {
      setQuery(text)
      navigate(HOME_URL)
    }
  }

  function toggleBookmark(): void {
    if (!activeTab || activeTab.url === HOME_URL) {
      return
    }
    if (bookmarked) {
      const existing = findBookmarkByUrl(root, activeTab.url)
      if (existing) {
        removeNode(existing.id)
      }
    } else {
      addBookmark('root', activeTab.title || activeTab.url, activeTab.url)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-1 items-center gap-2">
      <input
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        className="flex-1 rounded-full border border-gray-300 px-3 py-1 text-sm text-gray-800 focus:border-blue-400 focus:outline-none"
        placeholder="Nhập URL hoặc từ khóa tìm kiếm"
      />
      <button
        type="button"
        onClick={toggleBookmark}
        disabled={!activeTab || activeTab.url === HOME_URL}
        className={
          'text-lg ' + (bookmarked ? 'text-yellow-500' : 'text-gray-300 hover:text-gray-400')
        }
        aria-label="Đánh dấu trang"
        title={bookmarked ? 'Bỏ đánh dấu' : 'Đánh dấu trang'}
      >
        ★
      </button>
    </form>
  )
}

function findBookmarkByUrl(
  node: { id: string; url?: string; children?: { id: string; url?: string; children?: unknown[] }[] },
  url: string
): { id: string } | null {
  if (node.url === url) {
    return node
  }
  for (const child of node.children ?? []) {
    const found = findBookmarkByUrl(child as typeof node, url)
    if (found) {
      return found
    }
  }
  return null
}

export default AddressBar
