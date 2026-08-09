import { describe, expect, it } from 'vitest'
import { Stack } from './Stack'

/**
 * `Stack` là cấu trúc đỡ lịch sử duyệt web (nút Lùi / Tiến), nên hai tính chất
 * đáng kiểm nhất là thứ tự LIFO và hành vi khi rỗng — chỗ mà một lỗi sẽ hiện ra
 * dưới dạng "bấm Lùi thì ứng dụng đứng hình".
 */
describe('Stack', () => {
  it('lấy ra theo thứ tự ngược với lúc đẩy vào (LIFO)', () => {
    const stack = new Stack<string>()
    stack.push('a')
    stack.push('b')
    stack.push('c')

    expect(stack.pop()).toBe('c')
    expect(stack.pop()).toBe('b')
    expect(stack.pop()).toBe('a')
  })

  it('trả về undefined thay vì ném lỗi khi rỗng', () => {
    const stack = new Stack<number>()
    expect(stack.pop()).toBeUndefined()
    expect(stack.peek()).toBeUndefined()
    expect(stack.isEmpty()).toBe(true)
    expect(stack.size()).toBe(0)
  })

  it('peek xem được đỉnh mà KHÔNG lấy ra', () => {
    const stack = new Stack<number>()
    stack.push(1)
    stack.push(2)

    expect(stack.peek()).toBe(2)
    expect(stack.size()).toBe(2) // vẫn còn nguyên hai phần tử
    expect(stack.peek()).toBe(2) // gọi lại vẫn ra đúng cái đó
  })

  it('clear đưa về rỗng', () => {
    const stack = new Stack<number>()
    stack.push(1)
    stack.push(2)
    stack.clear()

    expect(stack.isEmpty()).toBe(true)
    expect(stack.toArray()).toEqual([])
  })

  it('toArray trả về BẢN SAO, không phải mảng nội bộ', () => {
    // Nếu trả thẳng mảng nội bộ thì bên gọi sửa được ruột của stack — đúng
    // loại lỗi mà `InvertedIndex.getAllDocuments()` ở backend chặn bằng
    // `unmodifiableMap`.
    const stack = new Stack<number>()
    stack.push(1)

    const snapshot = stack.toArray()
    snapshot.push(999)

    expect(stack.size()).toBe(1)
    expect(stack.toArray()).toEqual([1])
  })

  it('giữ đúng thứ tự đáy → đỉnh trong toArray', () => {
    const stack = new Stack<string>()
    stack.push('day')
    stack.push('giua')
    stack.push('dinh')

    expect(stack.toArray()).toEqual(['day', 'giua', 'dinh'])
  })
})
