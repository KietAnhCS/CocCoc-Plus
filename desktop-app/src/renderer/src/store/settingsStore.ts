import { create } from 'zustand'
import { settingsApi, NotAuthenticated } from '../lib/userDataApi'

/**
 * Tuỳ chọn người dùng — đồng bộ giữa các máy qua settings-service.
 *
 * <h2>Hai tầng lưu, và vì sao cần cả hai</h2>
 *
 * <pre>
 *   localStorage      áp NGAY khi mở ứng dụng, kể cả khi chưa đăng nhập
 *                     hoặc backend chưa chạy
 *   settings-service  nguồn sự thật, đồng bộ giữa các máy
 * </pre>
 *
 * <p>Chỉ dùng máy chủ thì giao diện nhấp nháy mỗi lần khởi động: nó vẽ bằng
 * giá trị mặc định trước, rồi đổi sang chủ đề thật khi request về — người dùng
 * chọn nền tối sẽ thấy một khoảnh khắc trắng loá mỗi lần mở. Chỉ dùng
 * localStorage thì mất tính đồng bộ, tức là mất lý do tồn tại của
 * settings-service.
 *
 * <p>Thứ tự: đọc localStorage → vẽ ngay → hỏi máy chủ → gộp → vẽ lại nếu khác.
 *
 * <h2>Chống ghi đè giữa các máy</h2>
 *
 * <p>Mỗi lần ghi mang theo {@code version} đang giữ trong header
 * {@code If-Match}. Máy chủ trả <b>409</b> nếu máy khác đã sửa — lúc đó ta
 * nhận khối mới, gộp, và thử lại đúng một lần. Không có cơ chế này thì người
 * dùng đổi chủ đề trên máy A, máy B đồng bộ lên và xoá mất thay đổi đó, âm
 * thầm.
 */

export interface TuyChon {
  /** `he-thong` = theo cài đặt của hệ điều hành. */
  theme: 'sang' | 'toi' | 'he-thong'
  /** Trang mở khi bấm nút Trang chủ. */
  homePage: string
  /** Có gợi ý từ lịch sử tìm kiếm của chính mình không. */
  goiYTuLichSu: boolean
  /** Có ghi lịch sử duyệt web lên máy chủ không. */
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
  version: number
  dangDongBo: boolean
  /** Không đăng nhập được thì vẫn dùng bản cục bộ — không phải lỗi. */
  chiCucBo: boolean
  loi: string | null

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
  version: 0,
  dangDongBo: false,
  chiCucBo: false,
  loi: null,

  nap: async () => {
    set({ dangDongBo: true, loi: null })
    try {
      const khoi = await settingsApi.read()
      // Máy chủ là nguồn sự thật, nhưng vẫn gộp lên MẶC ĐỊNH: khối trên máy
      // chủ có thể thiếu những khoá mới thêm ở phiên bản ứng dụng này.
      const tuyChon = { ...MAC_DINH, ...(khoi.settings as Partial<TuyChon>) }
      writeLocal(tuyChon)
      set({ tuyChon, version: khoi.version, dangDongBo: false, chiCucBo: false })
    } catch (error) {
      if (error instanceof NotAuthenticated) {
        set({ dangDongBo: false, chiCucBo: true, loi: null })
        return
      }
      set({
        dangDongBo: false,
        chiCucBo: true,
        loi: 'Không đồng bộ được tuỳ chọn. Đang dùng bản lưu trên máy này.'
      })
    }
  },

  dat: async (khoa, giaTri) => {
    // Áp NGAY, không chờ máy chủ. Một ô chuyển chủ đề mà phải đợi một lượt gọi
    // mạng mới đổi màu là một ô chuyển trông như bị hỏng.
    const tuyChon = { ...get().tuyChon, [khoa]: giaTri }
    writeLocal(tuyChon)
    set({ tuyChon })

    try {
      const khoi = await settingsApi.merge({ [khoa]: giaTri }, get().version)
      set({ version: khoi.version, chiCucBo: false })
    } catch (error) {
      if (error instanceof NotAuthenticated) {
        set({ chiCucBo: true })
        return
      }
      // 409 = máy khác vừa sửa. Nạp lại rồi ghi đè đúng khoá này — thay đổi
      // của người dùng vừa thực hiện thắng, còn những khoá khác giữ theo bản
      // mới nhất. THỬ LẠI ĐÚNG MỘT LẦN: một vòng lặp thử lại giữa hai máy
      // đang cùng sửa sẽ chạy mãi không dừng.
      try {
        await get().nap()
        const khoi = await settingsApi.merge({ [khoa]: giaTri }, get().version)
        set({ version: khoi.version })
      } catch {
        set({ chiCucBo: true })
      }
    }
  },

  resetToDefaults: async () => {
    writeLocal(MAC_DINH)
    set({ tuyChon: MAC_DINH, version: 0 })
    try {
      await settingsApi.resetToDefaults()
    } catch {
      set({ chiCucBo: true })
    }
  }
}))
