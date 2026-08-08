import { create } from 'zustand'

/**
 * Hai chế độ xem kết quả — chính là hai tab dưới thanh tìm kiếm.
 *
 * `'web'`    danh sách liên kết, như từ trước tới nay
 * `'images'` lưới ảnh, mỗi ảnh kèm tiêu đề và liên kết tới trang chứa nó
 */
export type SearchMode = 'web' | 'images'

interface SearchViewState {
  query: string | null
  mode: SearchMode
  runSearch: (query: string) => void
  setMode: (mode: SearchMode) => void
  clear: () => void
}

export const useSearchViewStore = create<SearchViewState>((set) => ({
  query: null,
  mode: 'web',

  /**
   * Một truy vấn MỚI luôn quay về tab Web.
   *
   * Vì sao không giữ nguyên tab đang mở: người dùng gõ một truy vấn mới là
   * đang bắt đầu một việc khác. Giữ họ ở tab Hình ảnh có thể cho ra một lưới
   * rỗng — không phải vì truy vấn sai mà vì những trang khớp chưa được Image
   * Download Service xử lý — và họ sẽ kết luận nhầm rằng máy tìm kiếm không
   * có kết quả nào.
   *
   * Đổi tab vẫn giữ nguyên truy vấn, nên chuyển qua lại không mất gì.
   */
  runSearch: (query) => {
    const trimmed = query.trim()
    set({ query: trimmed || null, mode: 'web' })
  },

  setMode: (mode) => set({ mode }),

  clear: () => set({ query: null, mode: 'web' })
}))
