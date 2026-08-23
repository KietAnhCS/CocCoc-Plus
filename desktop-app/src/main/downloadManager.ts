import {
  app,
  shell,
  type BrowserWindow,
  type DownloadItem,
  type Session,
  type WebContents
} from 'electron'
import { randomUUID } from 'crypto'
import { basename } from 'path'

/**
 * Trạng thái một lượt tải, khớp CHÍNH XÁC với enum `DownloadState` của
 * downloads-service.
 *
 * Lệch một chữ ở đây thì máy chủ trả 400 cho mọi lần đồng bộ, và triệu chứng
 * duy nhất là sổ tải xuống trên máy khác không bao giờ cập nhật.
 */
export type DownloadState = 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'CANCELLED' | 'INTERRUPTED'

export interface DownloadInfo {
  /** UUID do CHÍNH TIẾN TRÌNH NÀY sinh — xem chú thích ở `startTracking`. */
  id: string
  url: string
  fileName: string
  mimeType: string
  savePath: string
  totalBytes: number | null
  receivedBytes: number
  state: DownloadState
  startedAt: number
  /** Byte/giây, tính trượt. `null` khi chưa đủ dữ liệu để ước lượng. */
  speedBytesPerSecond: number | null
  /** Lượt tải này thuộc một tab ẩn danh — KHÔNG được đồng bộ lên máy chủ. */
  incognito: boolean
}

/**
 * Quản lý tải xuống thật.
 *
 * <h2>Vì sao lớp này tồn tại</h2>
 *
 * Trước đây bảng "Tải xuống" trong giao diện là một khung rỗng với dòng chữ
 * "Chưa có tệp nào được tải xuống" — và nó sẽ mãi rỗng, vì không có gì lắng
 * nghe sự kiện tải. Bấm vào một liên kết tệp trong tab thì Electron mở hộp
 * thoại lưu tệp của hệ điều hành rồi tải im lặng, không ai biết.
 *
 * <h2>Nơi duy nhất bắt được sự kiện</h2>
 *
 * `session.on('will-download')` là điểm móc DUY NHẤT. Nó bắn ở tầng
 * {@link Session}, không phải ở tầng cửa sổ hay tab — nghĩa là phải đăng ký
 * cho MỌI session đang dùng, kể cả session ẩn danh. Quên một session thì
 * những lượt tải từ đó biến mất khỏi giao diện mà không có lỗi nào.
 *
 * <h2>Về `item.setSavePath` và hộp thoại lưu tệp</h2>
 *
 * KHÔNG gọi `setSavePath`. Gọi nó sẽ tắt hộp thoại "Lưu ở đâu" của hệ điều
 * hành và tải thẳng vào thư mục ta chọn — nghe tiện, nhưng nó lấy mất quyền
 * quyết định của người dùng và là hành vi mà không trình duyệt nào làm mặc
 * định. Để trống thì Electron tự hiện hộp thoại, và người dùng bấm Huỷ sẽ
 * sinh ra trạng thái `cancelled` — được xử lý như một kết quả bình thường, vì
 * nó đúng là bình thường.
 */
export class DownloadManager {
  private readonly items = new Map<string, DownloadItem>()
  private readonly infos = new Map<string, DownloadInfo>()
  /** Mốc đo tốc độ gần nhất: [thời điểm ms, số byte đã nhận]. */
  private readonly speedSamples = new Map<string, [number, number]>()
  private readonly sessions = new WeakSet<Session>()

  constructor(private readonly chrome: WebContents) {}

  /**
   * Đăng ký lắng nghe cho một session.
   *
   * Gọi lại nhiều lần với cùng một session là an toàn — `WeakSet` chặn đăng ký
   * trùng. Điều đó cần thiết vì mỗi tab mới có thể mang một session mới, và
   * nơi gọi không nên phải tự nhớ đã đăng ký cái nào.
   *
   * Dùng `WeakSet` chứ không `Set`: session của một cửa sổ ẩn danh đã đóng
   * phải được thu hồi bộ nhớ, và một `Set` thường sẽ giữ nó sống mãi.
   */
  watch(session: Session, incognito: boolean): void {
    if (this.sessions.has(session)) {
      return
    }
    this.sessions.add(session)
    session.on('will-download', (_event, item) => this.startTracking(item, incognito))
  }

  private startTracking(item: DownloadItem, incognito: boolean): void {
    // UUID sinh Ở ĐÂY, không lấy từ máy chủ. Đây là khoá idempotent mà
    // downloads-service dùng: nếu mạng rớt giữa lúc đồng bộ, lần thử lại mang
    // đúng id này và máy chủ cập nhật dòng cũ thay vì tạo dòng thứ hai. Lấy id
    // từ máy chủ thì lần thử lại đầu tiên đã không có id để gửi.
    const id = randomUUID()

    const info: DownloadInfo = {
      id,
      url: item.getURL(),
      fileName: item.getFilename(),
      mimeType: item.getMimeType(),
      savePath: '',
      // getTotalBytes() trả 0 khi máy chủ không gửi Content-Length. Dịch sang
      // `null` = KHÔNG BIẾT, khác hẳn "tệp rỗng": giao diện phải hiện thanh
      // chạy không xác định chứ không phải 100%.
      totalBytes: item.getTotalBytes() > 0 ? item.getTotalBytes() : null,
      receivedBytes: 0,
      state: 'IN_PROGRESS',
      startedAt: Date.now(),
      speedBytesPerSecond: null,
      incognito
    }

    this.items.set(id, item)
    this.infos.set(id, info)
    this.speedSamples.set(id, [Date.now(), 0])
    this.emit()

    item.on('updated', (_e, state) => {
      const current = this.infos.get(id)
      if (!current) {
        return
      }
      current.receivedBytes = item.getReceivedBytes()
      current.savePath = item.getSavePath()
      if (current.totalBytes === null && item.getTotalBytes() > 0) {
        current.totalBytes = item.getTotalBytes()
      }
      current.state =
        state === 'interrupted' ? 'INTERRUPTED' : item.isPaused() ? 'PAUSED' : 'IN_PROGRESS'
      current.speedBytesPerSecond = this.measureSpeed(id, current.receivedBytes)
      this.emit()
    })

    item.once('done', (_e, state) => {
      const current = this.infos.get(id)
      if (!current) {
        return
      }
      current.receivedBytes = item.getReceivedBytes()
      current.savePath = item.getSavePath()
      current.speedBytesPerSecond = null
      current.state =
        state === 'completed' ? 'COMPLETED' : state === 'cancelled' ? 'CANCELLED' : 'INTERRUPTED'

      // Bỏ tham chiếu tới DownloadItem nhưng GIỮ LẠI thông tin: người dùng vẫn
      // muốn thấy tệp đã tải trong danh sách. Giữ cả DownloadItem thì mỗi lượt
      // tải để lại một đối tượng gốc C++ sống mãi.
      this.items.delete(id)
      this.speedSamples.delete(id)
      this.emit()
    })
  }

  /**
   * Tốc độ tải, tính theo hai mốc gần nhau.
   *
   * Không dùng trung bình từ lúc bắt đầu: con số đó bị kéo lệch bởi những giây
   * đầu (kết nối, chuyển hướng) và không bao giờ phản ứng khi mạng đổi tốc độ.
   * Người dùng nhìn con số này để ước lượng còn bao lâu, nên nó phải nói về
   * HIỆN TẠI.
   *
   * Trả `null` khi hai mốc cách nhau dưới 500 ms: chia cho một khoảng quá ngắn
   * cho ra những con số nhảy loạn xạ.
   */
  private measureSpeed(id: string, receivedBytes: number): number | null {
    const sample = this.speedSamples.get(id)
    const now = Date.now()
    if (!sample) {
      this.speedSamples.set(id, [now, receivedBytes])
      return null
    }
    const [lastTime, lastBytes] = sample
    const elapsed = now - lastTime
    if (elapsed < 500) {
      return null
    }
    this.speedSamples.set(id, [now, receivedBytes])
    return Math.max(0, Math.round(((receivedBytes - lastBytes) * 1000) / elapsed))
  }

  list(): DownloadInfo[] {
    // Mới nhất trước — cùng thứ tự mà downloads-service trả về, để giao diện
    // không phải sắp xếp lại theo hai luật khác nhau tuỳ nguồn dữ liệu.
    return [...this.infos.values()].sort((a, b) => b.startedAt - a.startedAt)
  }

  pause(id: string): void {
    this.items.get(id)?.pause()
  }

  resume(id: string): void {
    const item = this.items.get(id)
    // canResume() sai khi máy chủ không hỗ trợ tải tiếp (thiếu header
    // Accept-Ranges). Gọi resume() lúc đó không làm gì cả và cũng không báo
    // lỗi — nên phải kiểm trước, nếu không nút "Tiếp tục" trông như bị hỏng.
    if (item?.canResume()) {
      item.resume()
    }
  }

  cancel(id: string): void {
    this.items.get(id)?.cancel()
  }

  /** Mở tệp bằng ứng dụng mặc định của hệ điều hành. */
  async openFile(id: string): Promise<string> {
    const info = this.infos.get(id)
    if (!info || info.state !== 'COMPLETED' || !info.savePath) {
      return 'Tệp chưa tải xong.'
    }
    // shell.openPath trả về chuỗi RỖNG khi thành công và một thông báo lỗi khi
    // thất bại — một API dễ dùng ngược. Trả nguyên chuỗi đó lên giao diện để
    // hiện đúng lý do (tệp đã bị xoá, không có ứng dụng mở được...).
    return shell.openPath(info.savePath)
  }

  /** Mở thư mục chứa tệp và chọn sẵn nó. */
  showInFolder(id: string): void {
    const info = this.infos.get(id)
    if (info?.savePath) {
      shell.showItemInFolder(info.savePath)
    }
  }

  /**
   * Xoá một mục KHỎI DANH SÁCH, không xoá tệp trên đĩa.
   *
   * Đây là điều mọi trình duyệt làm và người dùng mong đợi: danh sách tải
   * xuống là một cuốn sổ, không phải trình quản lý tệp. Xoá luôn tệp sẽ là
   * một hành động phá huỷ mà không ai lường trước từ một nút mang tên "Xoá
   * khỏi danh sách".
   */
  remove(id: string): void {
    this.items.get(id)?.cancel()
    this.items.delete(id)
    this.infos.delete(id)
    this.speedSamples.delete(id)
    this.emit()
  }

  /** Xoá mọi mục ĐÃ KẾT THÚC. Mục đang tải được giữ lại — huỷ chúng là một
   *  thao tác khác, và người bấm "Xoá danh sách" không có ý huỷ chúng. */
  clearFinished(): void {
    for (const [id, info] of this.infos) {
      if (info.state !== 'IN_PROGRESS' && info.state !== 'PAUSED') {
        this.infos.delete(id)
        this.speedSamples.delete(id)
      }
    }
    this.emit()
  }

  /** Thư mục tải xuống mặc định của hệ điều hành — giao diện hiện cho người dùng. */
  defaultDirectory(): string {
    return app.getPath('downloads')
  }

  private emit(): void {
    if (!this.chrome.isDestroyed()) {
      this.chrome.send('downloads:changed', this.list())
    }
  }
}

/** Tên tệp từ một đường dẫn, dùng khi Electron chưa kịp điền `fileName`. */
export function fileNameOf(path: string): string {
  return path ? basename(path) : ''
}

/** Kiểu cửa sổ, để `index.ts` biết có phải mở ẩn danh không. */
export type WindowKind = 'normal' | 'incognito'

export type { BrowserWindow }
