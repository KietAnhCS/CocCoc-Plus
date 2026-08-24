import { create } from 'zustand'
import { historyApi } from '../lib/userDataApi'
import { useSettingsStore } from './settingsStore'
import { useTabStore } from './tabStore'

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

    if (!trimmed) {
      return
    }

    // Ghi truy vấn lên history-service, để lần sau ô địa chỉ gợi ý lại được.
    //
    // BA điều kiện chặn, cùng bộ luật với lịch sử ghé thăm:
    //   1. thẻ đang mở là thẻ ẩn danh   -> không gửi
    //   2. người dùng tắt lưu lịch sử   -> tôn trọng lựa chọn đó
    //   3. (trong userDataApi) chưa đăng nhập -> không có chỗ để lưu
    //
    // `resultCount` gửi 0 ở đây vì thời điểm này chưa có kết quả nào; máy chủ
    // ghi nhận truy vấn trước, và con số đó chỉ dùng để đánh giá chất lượng
    // máy tìm kiếm chứ không hiện cho người dùng.
    const tabStore = useTabStore.getState()
    const tabHienTai = tabStore.tabs.find((tab) => tab.id === tabStore.activeTabId)
    const anDanh = tabHienTai?.incognito === true
    if (anDanh || !useSettingsStore.getState().tuyChon.luuLichSu) {
      return
    }
    void historyApi.recordSearch(trimmed, 0)
  },

  setMode: (mode) => set({ mode }),

  clear: () => set({ query: null, mode: 'web' })
}))
