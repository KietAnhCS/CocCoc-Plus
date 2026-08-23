import { create } from 'zustand'

/**
 * Ô "Tìm trong trang" (Ctrl+F).
 *
 * <h2>Vì sao cần một store thay vì trạng thái cục bộ trong component</h2>
 *
 * <p>Ba nơi cùng tác động lên ô này: phím tắt Ctrl+F (đến từ tiến trình chính
 * qua IPC), chính component ô tìm, và phép chuyển thẻ (đổi thẻ phải đóng ô tìm
 * của thẻ cũ). Trạng thái mà ba nơi cùng sửa thì thuộc về một store.
 */
interface FindState {
  /** Ô tìm đang mở hay không. */
  open: boolean
  /** Từ khoá đang gõ. */
  query: string
  /** Kết quả thứ mấy đang được đánh dấu, tính từ 1. `0` = chưa có kết quả nào. */
  activeMatch: number
  /** Tổng số kết quả trên trang. */
  matches: number

  moO: () => void
  dongO: (tabId: string | null) => void
  datTuKhoa: (tabId: string | null, query: string) => void
  ketQuaTiepTheo: (tabId: string | null) => void
  ketQuaTruocDo: (tabId: string | null) => void
  nhanKetQua: (activeMatch: number, matches: number) => void
  /** Gọi khi chuyển thẻ hoặc điều hướng: mọi kết quả cũ mất hiệu lực. */
  datLai: () => void
}

export const useFindStore = create<FindState>((set, get) => ({
  open: false,
  query: '',
  activeMatch: 0,
  matches: 0,

  moO: () => set({ open: true }),

  dongO: (tabId) => {
    if (tabId) {
      void window.browser.stopFindInPage(tabId)
    }
    set({ open: false, query: '', activeMatch: 0, matches: 0 })
  },

  /**
   * Gõ một ký tự mới = một lần tìm MỚI, không phải "tìm tiếp".
   *
   * <p>`findNext: false` là chi tiết quyết định trải nghiệm ở đây. Gửi `true`
   * cho mỗi ký tự sẽ khiến Electron coi mỗi lần gõ là một lệnh "tới kết quả
   * sau", và con trỏ nhảy dần xuống cuối trang trong lúc người dùng mới chỉ
   * đang gõ dở từ khoá.
   */
  datTuKhoa: (tabId, query) => {
    set({ query })
    if (!tabId) {
      return
    }
    if (!query) {
      set({ activeMatch: 0, matches: 0 })
      void window.browser.stopFindInPage(tabId)
      return
    }
    void window.browser.findInPage(tabId, query, true, false)
  },

  ketQuaTiepTheo: (tabId) => {
    const { query } = get()
    if (tabId && query) {
      void window.browser.findInPage(tabId, query, true, true)
    }
  },

  ketQuaTruocDo: (tabId) => {
    const { query } = get()
    if (tabId && query) {
      void window.browser.findInPage(tabId, query, false, true)
    }
  },

  nhanKetQua: (activeMatch, matches) => set({ activeMatch, matches }),

  datLai: () => set({ activeMatch: 0, matches: 0 })
}))
