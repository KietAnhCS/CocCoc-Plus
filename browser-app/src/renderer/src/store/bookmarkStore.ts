import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { BookmarkTrie } from '../lib/BookmarkTrie'

/**
 * Zustand store luu bookmark dang cay thu muc (folder long nhau -> cau
 * truc Tree). Moi node co the la THU MUC (co children, khong co url) hoac
 * BOOKMARK LA (co url, khong co children).
 *
 * Tim kiem bookmark theo tien to tieu de dung {@link BookmarkTrie} tu cai
 * dat rieng ben TypeScript. Trie duoc XAY LAI moi lan goi searchByPrefix
 * (khong luu thuong truc trong Zustand state, vi Trie khong the serialize
 * JSON de persist) — chap nhan O(tong so bookmark) moi lan tim vi so
 * luong bookmark trong thuc te (hang chuc/hang tram) rat nho, doi lai don
 * gian hoa dang ke viec dong bo Trie voi cay khi them/xoa.
 */

export interface BookmarkNode {
  id: string
  title: string
  url?: string
  children?: BookmarkNode[]
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
  const idx = node.children.findIndex((c) => c.id === id)
  if (idx >= 0) {
    node.children.splice(idx, 1)
    return true
  }
  return node.children.some((child) => removeNodeById(child, id))
}

function collectAllBookmarks(node: BookmarkNode, out: BookmarkNode[]): void {
  if (node.url) {
    out.push(node)
  }
  for (const child of node.children ?? []) {
    collectAllBookmarks(child, out)
  }
}

interface BookmarkState {
  root: BookmarkNode
  addBookmark: (parentId: string, title: string, url: string) => void
  addFolder: (parentId: string, name: string) => void
  removeNode: (id: string) => void
  isBookmarked: (url: string) => boolean
  searchByPrefix: (prefix: string) => BookmarkNode[]
}

export const useBookmarkStore = create<BookmarkState>()(
  persist(
    (set, get) => ({
      root: { id: 'root', title: 'Bookmarks', children: [] },

      addBookmark: (parentId, title, url) => {
        set((state) => {
          const root = structuredClone(state.root)
          const parent = findNode(root, parentId) ?? root
          parent.children = parent.children ?? []
          parent.children.push({ id: createId(), title, url })
          return { root }
        })
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

      isBookmarked: (url) => {
        const all: BookmarkNode[] = []
        collectAllBookmarks(get().root, all)
        return all.some((b) => b.url === url)
      },

      searchByPrefix: (prefix) => {
        const all: BookmarkNode[] = []
        collectAllBookmarks(get().root, all)

        const trie = new BookmarkTrie()
        for (const bookmark of all) {
          for (const word of bookmark.title.split(/\s+/)) {
            trie.insert(word, bookmark.id)
          }
        }
        const matchedIds = new Set(trie.searchByPrefix(prefix))
        return all.filter((b) => matchedIds.has(b.id))
      }
    }),
    { name: 'vnsearch-bookmarks' }
  )
)
