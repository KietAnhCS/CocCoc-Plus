import { afterEach, describe, expect, it, vi } from 'vitest'
import { search, searchImages, suggest } from './searchApi'

/**
 * Tầng gọi API là nơi frontend gặp backend, và nó tồn tại chủ yếu để CHỊU
 * ĐƯỢC những phản hồi không hoàn hảo: trường thiếu, kiểu sai, máy chủ đời cũ.
 * Đó cũng chính là thứ đáng kiểm nhất ở đây — không phải "đường thành công",
 * mà những nhánh mà mắt thường không nhìn ra khi đọc mã.
 *
 * <p>`fetch` được thay bằng bản giả: bộ test này không được phép cần một máy
 * chủ đang chạy. Một bài test đòi hạ tầng bên ngoài là một bài test sẽ bị tắt
 * đi vào ngày nó đỏ vì lý do không liên quan.
 */
function mockFetchJson(payload: unknown, ok = true, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      Promise.resolve({
        ok,
        status,
        statusText: ok ? 'OK' : 'Internal Server Error',
        json: async () => Promise.resolve(payload)
      } as Response)
    )
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('search', () => {
  it('dựng đúng URL và tham số truy vấn', async () => {
    mockFetchJson({ query: 'máy tính', results: [] })
    await search('máy tính', 2, 15)

    const calledWith = vi.mocked(fetch).mock.calls[0][0] as URL
    expect(calledWith.pathname).toBe('/api/search')
    expect(calledWith.searchParams.get('q')).toBe('máy tính')
    expect(calledWith.searchParams.get('page')).toBe('2')
    expect(calledWith.searchParams.get('size')).toBe('15')
  })

  it('điền giá trị mặc định cho mọi trường bị thiếu', async () => {
    // Máy chủ đời cũ (hoặc một lỗi) trả về gần như rỗng. Giao diện vẫn phải vẽ
    // được, không được ném lỗi ở giữa lúc render.
    mockFetchJson({})
    const response = await search('gì đó')

    expect(response.query).toBe('gì đó')
    expect(response.results).toEqual([])
    expect(response.totalResults).toBe(0)
    expect(response.droppedTerms).toEqual([])
    expect(response.timeTakenMs).toBe(0)
  })

  it('dùng title thay bằng url khi tài liệu không có tiêu đề', async () => {
    mockFetchJson({
      results: [{ url: 'https://vi-du.com/bai-viet' }]
    })
    const response = await search('x')

    expect(response.results[0].title).toBe('https://vi-du.com/bai-viet')
    expect(response.results[0].score).toBe(0)
  })

  it('TIN pageSize của máy chủ chứ không tin giá trị vừa gửi lên', async () => {
    // Máy chủ ép `size` ngoài khoảng 1..100 về mặc định 20 (SearchController).
    // Nếu giao diện giữ giá trị của chính nó thì phần phân trang tính sai.
    mockFetchJson({ pageSize: 20 })
    const response = await search('x', 1, 5000)

    expect(response.pageSize).toBe(20)
  })

  it('ném lỗi khi máy chủ trả mã lỗi', async () => {
    mockFetchJson({}, false, 500)
    await expect(search('x')).rejects.toThrow('500')
  })
})

describe('searchImages', () => {
  it('loại bỏ mục không có địa chỉ ảnh', async () => {
    // Một mục không có imageUrl chỉ tạo ra một ô vỡ trong lưới.
    mockFetchJson({
      results: [
        { imageUrl: 'https://vi-du.com/a.jpg' },
        { imageUrl: '' },
        { pageUrl: 'https://vi-du.com/b' }
      ]
    })
    const response = await searchImages('mèo')

    expect(response.results).toHaveLength(1)
    expect(response.results[0].imageUrl).toBe('https://vi-du.com/a.jpg')
  })

  it('giữ hasMore của máy chủ, không suy từ số phần tử', async () => {
    mockFetchJson({ results: [], hasMore: true, pagesScanned: 12 })
    const response = await searchImages('mèo')

    expect(response.hasMore).toBe(true)
    expect(response.pagesScanned).toBe(12)
  })

  it('mặc định width/height là -1 khi trang không khai báo', async () => {
    mockFetchJson({ results: [{ imageUrl: 'https://vi-du.com/a.jpg' }] })
    const response = await searchImages('mèo')

    expect(response.results[0].width).toBe(-1)
    expect(response.results[0].height).toBe(-1)
  })
})

describe('suggest', () => {
  it('trả về rỗng NGAY cho truy vấn trắng, không gọi mạng', async () => {
    mockFetchJson([])
    expect(await suggest('   ')).toEqual([])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('nhận cả hai dạng phản hồi: mảng trần và bọc trong { suggestions }', async () => {
    mockFetchJson(['máy tính', 'máy ảnh'])
    expect(await suggest('má')).toEqual(['máy tính', 'máy ảnh'])

    mockFetchJson({ suggestions: ['màn hình'] })
    expect(await suggest('mà')).toEqual(['màn hình'])
  })

  it('lọc bỏ phần tử không phải chuỗi', async () => {
    mockFetchJson(['hợp lệ', 42, null, { a: 1 }])
    expect(await suggest('h')).toEqual(['hợp lệ'])
  })

  it('NUỐT lỗi mạng và trả về rỗng', async () => {
    // Gợi ý là tính năng phụ trợ: máy chủ chết thì ô tìm kiếm vẫn phải gõ
    // được. Ném lỗi ở đây sẽ làm hỏng cả thanh địa chỉ.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new Error('mạng hỏng')))
    )
    expect(await suggest('má')).toEqual([])
  })
})
