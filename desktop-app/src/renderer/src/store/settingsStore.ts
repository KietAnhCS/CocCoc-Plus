import { create } from 'zustand'

/** Tuỳ chọn người dùng — lưu trong localStorage, áp ngay khi mở ứng dụng. */
export interface TuyChon {
  /** `he-thong` = theo cài đặt của hệ điều hành. */
  theme: 'sang' | 'toi' | 'he-thong'
  /** Trang mở khi bấm nút Trang chủ. */
  homePage: string
  /** Có gợi ý từ lịch sử tìm kiếm của chính mình không. */
  goiYTuLichSu: boolean
  /** Có ghi lịch sử duyệt web không. */
  luuLichSu: boolean
  /** Hiện thanh dấu trang dưới thanh địa chỉ. */
  hienThanhDauTrang: boolean
  /** Hỏi vị trí lưu cho mỗi lượt tải xuống. */
  hoiChoLuuTep: boolean
}

export const MAC_DINH: TuyChon = {
  theme: 'he-thong',
  homePage: 'vnsearch://home',
  goiYTuLichSu: true,
  luuLichSu: true,
  hienThanhDauTrang: true,
  hoiChoLuuTep: true
}

const KHOA_CUC_BO = 'vnsearch-settings'

interface SettingsState {
  tuyChon: TuyChon

  nap: () => Promise<void>
  dat: <K extends keyof TuyChon>(khoa: K, giaTri: TuyChon[K]) => Promise<void>
  resetToDefaults: () => Promise<void>
}

function readLocal(): TuyChon {
  try {
    const raw = window.localStorage.getItem(KHOA_CUC_BO)
    if (!raw) {
      return MAC_DINH
    }
    // Gộp với mặc định thay vì tin hẳn: một phiên bản ứng dụng cũ có thể đã
    // lưu một khối thiếu khoá mới thêm, và `undefined` lọt vào giao diện sẽ
    // thành một ô chọn không có giá trị nào được chọn.
    return { ...MAC_DINH, ...(JSON.parse(raw) as Partial<TuyChon>) }
  } catch {
    return MAC_DINH
  }
}

function writeLocal(tuyChon: TuyChon): void {
  try {
    window.localStorage.setItem(KHOA_CUC_BO, JSON.stringify(tuyChon))
  } catch {
    // Cửa sổ bị hạn chế lưu trữ. Tuỳ chọn vẫn chạy trong phiên này.
  }
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  tuyChon: readLocal(),

  nap: async () => {
    set({ tuyChon: readLocal() })
  },

  dat: async (khoa, giaTri) => {
    const tuyChon = { ...get().tuyChon, [khoa]: giaTri }
    writeLocal(tuyChon)
    set({ tuyChon })
  },

  resetToDefaults: async () => {
    writeLocal(MAC_DINH)
    set({ tuyChon: MAC_DINH })
  }
}))
