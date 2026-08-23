import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { BookmarkTrie } from '../lib/BookmarkTrie'
import { SEED_SITES } from '../lib/seedSites'

export interface BookmarkNode {
  id: string
  title: string
  url?: string
  children?: BookmarkNode[]
}

interface BookmarkState {
  root: BookmarkNode
  addFolder: (parentId: string, name: string) => void
  removeNode: (id: string) => void
  toggleBookmark: (url: string, title: string) => void
  isBookmarked: (url: string) => boolean
  searchByPrefix: (prefix: string) => BookmarkNode[]
}

function createId(): string {
  return `bm-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function findNode(node: BookmarkNode, id: string): BookmarkNode | null {
  if (node.id === id) {
    return node
  }
  for (const child of node.children ?? []) {
    const found = findNode(child, id)
    if (found) {
      return found
    }
  }
  return null
}

function removeNodeById(node: BookmarkNode, id: string): boolean {
  if (!node.children) {
    return false
  }
  const index = node.children.findIndex((child) => child.id === id)
  if (index >= 0) {
    node.children.splice(index, 1)
    return true
  }
  return node.children.some((child) => removeNodeById(child, id))
}

export function collectBookmarks(node: BookmarkNode): BookmarkNode[] {
  const leaves: BookmarkNode[] = []
  const walk = (current: BookmarkNode): void => {
    if (current.url) {
      leaves.push(current)
    }
    for (const child of current.children ?? []) {
      walk(child)
    }
  }
  walk(node)
  return leaves
}

export const useBookmarkStore = create<BookmarkState>()(
  persist(
    (set, get) => ({
      root: {
        id: 'root',
        title: 'Bookmarks',
        children: SEED_SITES.map((site) => ({
          id: `seed-${site.url}`,
          title: site.name,
          url: site.url
        }))
      },

      addFolder: (parentId, name) => {
        set((state) => {
          const root = structuredClone(state.root)
          const parent = findNode(root, parentId) ?? root
          parent.children = parent.children ?? []
          parent.children.push({ id: createId(), title: name, children: [] })
          return { root }
        })
      },

      removeNode: (id) => {
        set((state) => {
          const root = structuredClone(state.root)
          removeNodeById(root, id)
          return { root }
        })
      },

      toggleBookmark: (url, title) => {
        set((state) => {
          const root = structuredClone(state.root)
          const existing = collectBookmarks(root).find((bookmark) => bookmark.url === url)
          if (existing) {
            removeNodeById(root, existing.id)
          } else {
            root.children = root.children ?? []
            root.children.push({ id: createId(), title: title || url, url })
          }
          return { root }
        })
      },

      isBookmarked: (url) => collectBookmarks(get().root).some((bookmark) => bookmark.url === url),

      searchByPrefix: (prefix) => {
        const bookmarks = collectBookmarks(get().root)
        const trie = new BookmarkTrie()
        for (const bookmark of bookmarks) {
          for (const word of bookmark.title.split(/\s+/)) {
            trie.insert(word, bookmark.id)
          }
        }
        const matchedIds = new Set(trie.searchByPrefix(prefix))
        return bookmarks.filter((bookmark) => matchedIds.has(bookmark.id))
      }
    }),
    { name: 'vnsearch-bookmarks' }
  )
)
