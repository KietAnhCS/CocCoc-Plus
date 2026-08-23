import { describe, expect, it } from 'vitest'
import { BookmarkTrie } from './BookmarkTrie'

/**
 * `BookmarkTrie` là bản song sinh phía frontend của `Trie` trong backend: cùng
 * một cấu trúc, cùng một mục đích (gợi ý theo tiền tố), khác ngôn ngữ.
 *
 * <p>Điểm cần canh kỹ nhất là tiếng Việt CÓ DẤU. Trie duyệt theo từng ký tự,
 * và với chuỗi UTF-16 thì "ký tự" là một khái niệm trơn trượt — `for...of` đi
 * theo điểm mã (code point) chứ không theo đơn vị mã (code unit), nên chữ có
 * dấu hoạt động đúng. Bài test dưới ghim hành vi đó lại.
 */
describe('BookmarkTrie', () => {
  it('tìm được theo tiền tố', () => {
    const trie = new BookmarkTrie()
    trie.insert('vnexpress', 'bm-1')
    trie.insert('vnedu', 'bm-2')
    trie.insert('tuoitre', 'bm-3')

    expect(trie.searchByPrefix('vn').sort()).toEqual(['bm-1', 'bm-2'])
    expect(trie.searchByPrefix('vne').sort()).toEqual(['bm-1', 'bm-2'])
    expect(trie.searchByPrefix('vnex')).toEqual(['bm-1'])
    expect(trie.searchByPrefix('tu')).toEqual(['bm-3'])
  })

  it('trả về mảng rỗng khi không có tiền tố nào khớp', () => {
    const trie = new BookmarkTrie()
    trie.insert('vnexpress', 'bm-1')

    expect(trie.searchByPrefix('zz')).toEqual([])
    expect(trie.searchByPrefix('vnexpressssss')).toEqual([])
  })

  it('tiền tố rỗng trả về TẤT CẢ', () => {
    const trie = new BookmarkTrie()
    trie.insert('a', 'bm-1')
    trie.insert('b', 'bm-2')

    expect(trie.searchByPrefix('').sort()).toEqual(['bm-1', 'bm-2'])
  })

  it('không phân biệt hoa thường ở cả lúc chèn lẫn lúc tìm', () => {
    const trie = new BookmarkTrie()
    trie.insert('VnExpress', 'bm-1')

    expect(trie.searchByPrefix('vnexp')).toEqual(['bm-1'])
    expect(trie.searchByPrefix('VNEXP')).toEqual(['bm-1'])
  })

  it('xử lý đúng tiếng Việt có dấu', () => {
    const trie = new BookmarkTrie()
    trie.insert('máy tính', 'bm-1')
    trie.insert('màn hình', 'bm-2')

    expect(trie.searchByPrefix('má')).toEqual(['bm-1'])
    expect(trie.searchByPrefix('màn')).toEqual(['bm-2'])
    // 'm' là tiền tố chung của cả hai
    expect(trie.searchByPrefix('m').sort()).toEqual(['bm-1', 'bm-2'])
  })

  it('gộp nhiều bookmark trên cùng một từ khoá, không trùng lặp', () => {
    const trie = new BookmarkTrie()
    trie.insert('tin tức', 'bm-1')
    trie.insert('tin tức', 'bm-2')
    trie.insert('tin tức', 'bm-1') // chèn lại chính nó

    expect(trie.searchByPrefix('tin').sort()).toEqual(['bm-1', 'bm-2'])
  })

  it('bỏ qua từ khoá rỗng thay vì tạo node rác', () => {
    const trie = new BookmarkTrie()
    trie.insert('', 'bm-rac')
    trie.insert('thật', 'bm-that')

    expect(trie.searchByPrefix('')).toEqual(['bm-that'])
  })

  it('một từ vừa là từ hoàn chỉnh vừa là tiền tố của từ khác', () => {
    // Ca này bắt lỗi "chỉ trả về lá": 'tin' là từ đầy đủ, đồng thời nằm trên
    // đường đi tới 'tin tức'. Cả hai đều phải ra.
    const trie = new BookmarkTrie()
    trie.insert('tin', 'bm-ngan')
    trie.insert('tin tức', 'bm-dai')

    expect(trie.searchByPrefix('tin').sort()).toEqual(['bm-dai', 'bm-ngan'])
  })
})
