import { create } from 'zustand'
import type { DownloadInfo } from '../../../main/downloadManager'

/** Một mục trong bảng Tải xuống — lấy từ tiến trình chính Electron. */
export interface MucTaiXuong {
  id: string
  url: string
  fileName: string
  mimeType: string
  savePath: string
  totalBytes: number | null
  receivedBytes: number
  state: DownloadInfo['state']
  startedAt: number
  speedBytesPerSecond: number | null
  incognito: boolean
}

interface DownloadStoreState {
  items: MucTaiXuong[]
  dangTai: number
  loi: string | null
  initialized: boolean

  init: () => void
  tamDung: (id: string) => void
  tiepTuc: (id: string) => void
  huy: (id: string) => void
  moTep: (id: string) => Promise<void>
  moThuMuc: (id: string) => void
  deleteVisit: (id: string) => void
  xoaDaXong: () => void
}

function fromLocal(info: DownloadInfo): MucTaiXuong {
  return { ...info }
}

export const useDownloadStore = create<DownloadStoreState>((set, get) => ({
  items: [],
  dangTai: 0,
  loi: null,
  initialized: false,

  init: () => {
    if (get().initialized) {
      return
    }
    set({ initialized: true })

    const apply = (infos: DownloadInfo[]): void => {
      const items = infos.map(fromLocal).sort((a, b) => b.startedAt - a.startedAt)
      set({
        items,
        dangTai: items.filter((item) => item.state === 'IN_PROGRESS' || item.state === 'PAUSED')
          .length
      })
    }

    window.downloads.onChanged(apply)
    void window.downloads.list().then(apply)
  },

  tamDung: (id) => void window.downloads.pause(id),
  tiepTuc: (id) => void window.downloads.resume(id),
  huy: (id) => void window.downloads.cancel(id),

  moTep: async (id) => {
    // shell.openPath trả chuỗi RỖNG khi thành công và một thông báo lỗi khi
    // không — một API dễ dùng ngược. Hiện nguyên thông báo đó: nó nói đúng lý
    // do (tệp đã bị xoá, không có ứng dụng mở được kiểu tệp này), thứ mà một
    // câu "Không mở được tệp" tự viết ra sẽ không nói được.
    const loi = await window.downloads.open(id)
    set({ loi: loi || null })
  },

  moThuMuc: (id) => void window.downloads.showInFolder(id),

  deleteVisit: (id) => {
    void window.downloads.remove(id)
    set((state) => ({ items: state.items.filter((item) => item.id !== id) }))
  },

  xoaDaXong: () => {
    void window.downloads.clearFinished()
    set((state) => ({
      items: state.items.filter((item) => item.state === 'IN_PROGRESS' || item.state === 'PAUSED')
    }))
  }
}))

/** Số byte thành chuỗi đọc được. Dùng chung cho tiến độ và tốc độ. */
export function formatBytes(bytes: number | null): string {
  if (bytes === null || !Number.isFinite(bytes)) {
    return '—'
  }
  const donVi = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let index = 0
  while (value >= 1024 && index < donVi.length - 1) {
    value /= 1024
    index += 1
  }
  // Số nguyên cho byte, một chữ số thập phân cho phần còn lại: "1,5 MB" hữu
  // ích, còn "1536,0 B" thì không.
  return `${index === 0 ? value : value.toFixed(1)} ${donVi[index]}`
}

/** Thời gian còn lại, hoặc `null` khi chưa ước lượng được. */
export function timeRemaining(item: MucTaiXuong): string | null {
  if (item.totalBytes === null || !item.speedBytesPerSecond || item.state !== 'IN_PROGRESS') {
    return null
  }
  const giay = Math.round((item.totalBytes - item.receivedBytes) / item.speedBytesPerSecond)
  if (giay < 60) {
    return `${giay} giây`
  }
  if (giay < 3600) {
    return `${Math.round(giay / 60)} phút`
  }
  return `${(giay / 3600).toFixed(1)} giờ`
}
