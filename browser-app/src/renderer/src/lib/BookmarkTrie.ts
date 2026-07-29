/**
 * Trie tu cai dat bang TypeScript, dung de tim kiem bookmark theo tien to
 * tieu de. Cai dat SONG SONG voi datastructure/Trie.java o backend (cung
 * y tuong: children la Map<ky tu, node>, isEndOfWord danh dau tu hoan
 * chinh) — dung de so sanh 2 cach cai dat (Java vs TypeScript) trong bao cao.
 *
 * Khac voi Trie.java (chi luu 1 tu khoa duy nhat moi node), o day moi nut
 * ket thuc tu luu THEM danh sach bookmarkId, vi nhieu bookmark khac nhau
 * co the co cung 1 tu trong tieu de ("tin tuc cong nghe", "tin tuc the thao"
 * deu co tu "tin").
 *
 * Do phuc tap: insert O(L), search(prefix) O(L + m) voi m la tong so ky tu
 * duoi cay con cua prefix (DFS thu thap).
 */
class TrieNode {
  children: Map<string, TrieNode> = new Map()
  isEndOfWord = false
  bookmarkIds: string[] = []
}

export class BookmarkTrie {
  private root = new TrieNode()

  insert(word: string, bookmarkId: string): void {
    const normalized = word.toLowerCase()
    if (!normalized) {
      return
    }
    let node = this.root
    for (const ch of normalized) {
      let next = node.children.get(ch)
      if (!next) {
        next = new TrieNode()
        node.children.set(ch, next)
      }
      node = next
    }
    node.isEndOfWord = true
    if (!node.bookmarkIds.includes(bookmarkId)) {
      node.bookmarkIds.push(bookmarkId)
    }
  }

  /** Tra ve danh sach bookmarkId (khong trung) co MOT TU trong tieu de bat dau bang prefix. */
  searchByPrefix(prefix: string): string[] {
    const normalized = prefix.toLowerCase()
    let node = this.root
    for (const ch of normalized) {
      const next = node.children.get(ch)
      if (!next) {
        return []
      }
      node = next
    }
    const result: string[] = []
    this.collect(node, result)
    return Array.from(new Set(result))
  }

  private collect(node: TrieNode, out: string[]): void {
    if (node.isEndOfWord) {
      out.push(...node.bookmarkIds)
    }
    for (const child of node.children.values()) {
      this.collect(child, out)
    }
  }
}
