import { create } from 'zustand'
import type { DownloadInfo } from '../../../main/downloadManager'
import { downloadsApi, type DownloadDto } from '../lib/userDataApi'

/**
 * Một mục trong bảng Tải xuống — gộp từ HAI nguồn.
 *
 * <pre>
 *   local   tiến trình chính Electron   thứ đang tải TRÊN MÁY NÀY, có tiến độ
 *   remote  downloads-service            thứ đã tải TRÊN MỌI MÁY, không tiến độ
 * </pre>
 *
 * <p>Nguồn `local` luôn thắng khi trùng `id`: nó là thời gian thực, còn bản
 * trên máy chủ có thể chậm vài giây.
 */
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
  /** Tệp nằm trên máy này — quyết định có hiện nút "Mở tệp" hay không. */
  onThisDevice: boolean
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
  dongBoLai: () => Promise<void>
}

/**
 * Trạng thái cuối cùng, không đổi được nữa.
 *
 * <p>Dùng để quyết định lúc nào đồng bộ lần cuối lên máy chủ. Một lượt tải
 * đang chạy được đồng bộ theo nhịp (xem {@link NHIP_DONG_BO_MS}); một lượt đã
 * kết thúc phải được đồng bộ NGAY, vì sẽ không còn sự kiện nào nữa.
 */
const TRANG_THAI_CUOI: ReadonlySet<DownloadInfo['state']> = new Set([
  'COMPLETED',
  'CANCELLED',
  'INTERRUPTED'
])

/**
 * Nhịp đồng bộ tiến độ lên máy chủ.
 *
 * <p>Tiến trình chính bắn sự kiện `updated` rất dày — có thể vài chục lần mỗi
 * giây với một tệp lớn trên mạng nhanh. Gửi từng cái lên máy chủ sẽ tạo ra một
 * trận request cho một con số mà không ai đang nhìn (tiến độ đã hiện sẵn từ
 * nguồn local, tức thời và chính xác hơn).
 *
 * <p>Ba giây là đủ để một thiết bị khác thấy "đang tải, 40%" mà không biến
 * đồng bộ thành nguồn tải chính của downloads-service.
 */
const NHIP_DONG_BO_MS = 3000

/** Lần đồng bộ gần nhất của mỗi mục, để giãn nhịp. */
const lanDongBoCuoi = new Map<string, number>()

/** Những mục đã báo "bắt đầu" lên máy chủ — tránh gọi POST hai lần. */
const daBaoBatDau = new Set<string>()

function fromLocal(info: DownloadInfo): MucTaiXuong {
  return { ...info, onThisDevice: true }
}

/**
 * Bản ghi từ máy chủ.
 *
 * <p>Không có `savePath` và không có tốc độ: máy chủ cố tình KHÔNG lưu đường
 * dẫn cục bộ ra ngoài (nó lộ cấu trúc thư mục và tên người dùng hệ điều hành —
 * xem chú thích cột `local_path` trong V1__so_tai_xuong.sql).
 */
function fromRemote(dto: DownloadDto): MucTaiXuong {
  return {
    id: dto.id,
    url: dto.sourceUrl,
    fileName: dto.fileName,
    mimeType: dto.mimeType ?? '',
    savePath: '',
    totalBytes: dto.totalBytes,
    receivedBytes: dto.receivedBytes,
    state: dto.state,
    startedAt: Date.parse(dto.startedAt) || 0,
    speedBytesPerSecond: null,
    onThisDevice: dto.onThisDevice,
    incognito: false
  }
}

/** Gộp hai nguồn, local thắng khi trùng id. */
function merge(local: MucTaiXuong[], remote: MucTaiXuong[]): MucTaiXuong[] {
  const theoId = new Map<string, MucTaiXuong>()
  for (const item of remote) {
    theoId.set(item.id, item)
  }
  for (const item of local) {
    theoId.set(item.id, item)
  }
  return [...theoId.values()].sort((a, b) => b.startedAt - a.startedAt)
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
      const local = infos.map(fromLocal)
      const remote = get().items.filter((item) => !item.onThisDevice)
      const items = merge(local, remote)
      set({
        items,
        dangTai: local.filter((item) => item.state === 'IN_PROGRESS' || item.state === 'PAUSED')
          .length
      })
      for (const info of infos) {
        syncItem(info)
      }
    }

    window.downloads.onChanged(apply)
    void window.downloads.list().then(apply)
    void get().dongBoLai()
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
    // Xoá cả trên máy chủ, nhưng KHÔNG chờ: người dùng vừa bấm xoá và mục đã
    // biến mất khỏi danh sách local. Bắt họ chờ một lượt gọi mạng cho một
    // thao tác đã hiển nhiên là làm chậm giao diện không vì gì.
    void downloadsApi.remove(id).catch(() => undefined)
    set((state) => ({ items: state.items.filter((item) => item.id !== id) }))
  },

  xoaDaXong: () => {
    void window.downloads.clearFinished()
    void downloadsApi.clear().catch(() => undefined)
    set((state) => ({
      items: state.items.filter((item) => item.state === 'IN_PROGRESS' || item.state === 'PAUSED')
    }))
  },

  /**
   * Kéo sổ tải xuống từ máy chủ về, gộp với những gì đang có.
   *
   * <p>Nuốt lỗi: chưa đăng nhập hoặc backend chưa chạy đều là tình huống bình
   * thường, và bảng vẫn dùng được với riêng dữ liệu local. Hiện một thông báo
   * lỗi ở đây là làm phiền vì một chức năng phụ.
   */
  dongBoLai: async () => {
    try {
      const remote = (await downloadsApi.list()).map(fromRemote)
      set((state) => ({
        items: merge(
          state.items.filter((item) => item.onThisDevice),
          remote
        )
      }))
    } catch {
      // Có chủ ý.
    }
  }
}))

/**
 * Đẩy trạng thái một lượt tải lên máy chủ.
 *
 * <p>Ba luật, theo đúng thứ tự:
 *
 * <ol>
 *   <li><b>Ẩn danh thì KHÔNG gửi gì cả.</b> Đây là luật đầu tiên và không có
 *       ngoại lệ nào.</li>
 *   <li>Lần đầu thấy mục này thì gửi POST (bắt đầu theo dõi).</li>
 *   <li>Sau đó gửi PATCH, giãn nhịp {@link NHIP_DONG_BO_MS} — trừ khi mục vừa
 *       kết thúc, lúc đó gửi ngay vì sẽ không còn sự kiện nào nữa.</li>
 * </ol>
 */
function syncItem(info: DownloadInfo): void {
  if (info.incognito) {
    return
  }

  if (!daBaoBatDau.has(info.id)) {
    daBaoBatDau.add(info.id)
    void downloadsApi.start({
      id: info.id,
      sourceUrl: info.url,
      fileName: info.fileName,
      mimeType: info.mimeType,
      totalBytes: info.totalBytes,
      localPath: info.savePath || undefined
    })
    lanDongBoCuoi.set(info.id, Date.now())
    return
  }

  const daKetThuc = TRANG_THAI_CUOI.has(info.state)
  const truoc = lanDongBoCuoi.get(info.id) ?? 0
  if (!daKetThuc && Date.now() - truoc < NHIP_DONG_BO_MS) {
    return
  }
  lanDongBoCuoi.set(info.id, Date.now())

  void downloadsApi.update(info.id, {
    receivedBytes: info.receivedBytes,
    state: info.state,
    localPath: info.savePath || undefined
  })

  if (daKetThuc) {
    // Dọn hai bảng phụ. Không dọn thì chúng phình theo số lượt tải trong cả
    // vòng đời ứng dụng — một rò rỉ bộ nhớ nhỏ nhưng không có giới hạn trên.
    lanDongBoCuoi.delete(info.id)
    daBaoBatDau.delete(info.id)
  }
}

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
