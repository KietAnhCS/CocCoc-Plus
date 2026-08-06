class TrieNode {
  children = new Map<string, TrieNode>()
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
    for (const character of normalized) {
      let next = node.children.get(character)
      if (!next) {
        next = new TrieNode()
        node.children.set(character, next)
      }
      node = next
    }

    node.isEndOfWord = true
    if (!node.bookmarkIds.includes(bookmarkId)) {
      node.bookmarkIds.push(bookmarkId)
    }
  }

  searchByPrefix(prefix: string): string[] {
    let node = this.root
    for (const character of prefix.toLowerCase()) {
      const next = node.children.get(character)
      if (!next) {
        return []
      }
      node = next
    }

    const matched: string[] = []
    this.collect(node, matched)
    return Array.from(new Set(matched))
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
