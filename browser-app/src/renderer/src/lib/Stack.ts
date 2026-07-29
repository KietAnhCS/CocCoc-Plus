/**
 * Stack (LIFO) tu cai dat, dung cho lich su back/forward cua trinh duyet
 * (xem historyStore.ts). Day la cau truc du lieu kinh dien: moi thao tac
 * push/pop/peek deu O(1).
 *
 * Ben trong dung mang JS lam bo nho lien tuc (primitive co san, giong nhu
 * ArrayList trong Java) — nhung noi goi (historyStore) KHONG duoc goi
 * truc tiep array.push/pop, ma phai qua cac method cua class nay, de
 * dung dan the hien tinh dong goi cua cau truc Stack.
 */
export class Stack<T> {
  private items: T[] = []

  push(item: T): void {
    this.items.push(item)
  }

  pop(): T | undefined {
    return this.items.pop()
  }

  peek(): T | undefined {
    return this.items[this.items.length - 1]
  }

  isEmpty(): boolean {
    return this.items.length === 0
  }

  size(): number {
    return this.items.length
  }

  clear(): void {
    this.items = []
  }

  /** Chi dung de hien thi debug/demo — khong lam thay doi stack. */
  toArray(): T[] {
    return [...this.items]
  }
}
