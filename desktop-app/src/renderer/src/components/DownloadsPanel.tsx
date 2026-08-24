import { useEffect, type JSX } from 'react'
import { useDownloadStore, formatBytes, timeRemaining, type MucTaiXuong } from '../store/downloadStore'
import { useSessionStore } from '../store/sessionStore'
import { hostOf } from '../lib/site'
import { CloseIcon, DownloadIcon } from './icons'

/**
 * Bảng "Tải xuống" — nay có dữ liệu thật.
 *
 * <p>Trước đây đây là một khung rỗng với dòng chữ "Chưa có tệp nào được tải
 * xuống", và nó sẽ mãi rỗng vì không có gì lắng nghe sự kiện tải. Nay nó đọc
 * từ {@code downloadStore}, vốn gộp hai nguồn: tiến trình chính Electron (thời
 * gian thực, có tiến độ) và downloads-service (các máy khác).
 */
function DownloadsPanel(): JSX.Element {
  const items = useDownloadStore((s) => s.items)
  const loi = useDownloadStore((s) => s.loi)
  const init = useDownloadStore((s) => s.init)
  const dongBoLai = useDownloadStore((s) => s.dongBoLai)
  const xoaDaXong = useDownloadStore((s) => s.xoaDaXong)
  const user = useSessionStore((s) => s.user)

  useEffect(() => {
    init()
  }, [init])

  // HAI hiệu ứng chứ không phải một, và đó là điểm mấu chốt: `init` tự chốt
  // bằng cờ `initialized` nên nó chỉ chạy ĐÚNG MỘT LẦN trong cả vòng đời ứng
  // dụng. Lượt gọi `dongBoLai` nằm trong đó vì thế cũng chỉ chạy một lần —
  // thường là lúc bảng mở lần đầu, khi người dùng CHƯA đăng nhập. Lúc đó
  // `downloadsApi.list` ném `NotAuthenticated` ngay trước khi chạm mạng, và
  // `dongBoLai` nuốt lỗi đó.
  //
  // Hậu quả nếu gộp lại: đăng nhập xong, bảng KHÔNG BAO GIỜ kéo lại sổ tải
  // xuống từ máy chủ — nó trống cho tới khi khởi động lại ứng dụng, và không
  // có thông báo lỗi nào để lần ra. Khoá theo `user` để mỗi lần đổi tài khoản
  // (đăng nhập, đăng xuất) đều kéo lại đúng dữ liệu của người đang đăng nhập.
  useEffect(() => {
    void dongBoLai()
  }, [user, dongBoLai])

  const coMucDaXong = items.some((item) => item.state !== 'IN_PROGRESS' && item.state !== 'PAUSED')

  if (items.length === 0) {
    return (
      <div className="flex flex-col items-center px-8 py-14 text-center">
        <span className="flex h-14 w-14 items-center justify-center rounded-full bg-raised text-faint">
          <DownloadIcon className="h-7 w-7" />
        </span>
        <p className="mt-4 text-[13px] text-muted">Chưa có tệp nào được tải xuống.</p>
        <p className="mt-1.5 text-[12px] leading-relaxed text-faint">
          Các tệp bạn tải về sẽ hiện ở đây, kèm tiến độ và nút mở nhanh.
        </p>
      </div>
    )
  }

  return (
    <div className="p-2">
      {loi && (
        <p
          className="mb-2 rounded-lg bg-danger/10 px-3 py-2 text-[12px] leading-relaxed text-danger"
          role="alert"
        >
          {loi}
        </p>
      )}

      <ul>
        {items.map((item) => (
          <DownloadRow key={item.id} item={item} />
        ))}
      </ul>

      {coMucDaXong && (
        <button
          onClick={xoaDaXong}
          className="mt-2 w-full rounded-lg py-2 text-[12px] text-muted transition
                     hover:bg-raised hover:text-ink focus-visible:outline-none
                     focus-visible:ring-2 focus-visible:ring-brand/60"
        >
          Xoá danh sách đã tải xong
        </button>
      )}
    </div>
  )
}

function DownloadRow({ item }: { item: MucTaiXuong }): JSX.Element {
  const tamDung = useDownloadStore((s) => s.tamDung)
  const tiepTuc = useDownloadStore((s) => s.tiepTuc)
  const huy = useDownloadStore((s) => s.huy)
  const moTep = useDownloadStore((s) => s.moTep)
  const moThuMuc = useDownloadStore((s) => s.moThuMuc)
  const deleteVisit = useDownloadStore((s) => s.deleteVisit)

  const dangChay = item.state === 'IN_PROGRESS' || item.state === 'PAUSED'
  // `null` = chưa biết tổng số byte (máy chủ không gửi Content-Length). Phải
  // phân biệt với 0%: một thanh tiến độ đứng im ở vạch đầu trông như đang
  // hỏng, còn thanh chạy không xác định thì nói đúng rằng "đang tải, chưa biết
  // còn bao lâu".
  const phanTram =
    item.totalBytes === null || item.totalBytes === 0
      ? null
      : Math.min(100, Math.round((item.receivedBytes / item.totalBytes) * 100))

  return (
    <li className="group rounded-lg px-2 py-2.5 hover:bg-raised">
      <div className="flex items-start gap-2.5">
        <span
          className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg
                     bg-brand-soft text-brand"
        >
          <DownloadIcon className="h-4 w-4" />
        </span>

        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium text-ink" title={item.fileName}>
            {item.fileName || 'Đang xác định tên tệp…'}
          </p>
          <p className="truncate text-[11px] text-faint" title={item.url}>
            {hostOf(item.url)}
          </p>

          {dangChay && (
            <>
              <div
                className="mt-2 h-1 w-full overflow-hidden rounded-full bg-line"
                role="progressbar"
                aria-valuenow={phanTram ?? undefined}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label={`Tiến độ tải ${item.fileName}`}
              >
                <div
                  className={
                    'h-full rounded-full bg-brand transition-[width] duration-300 ' +
                    // Không rõ tổng dung lượng thì hiện một dải chạy qua lại
                    // thay vì một con số bịa ra.
                    (phanTram === null ? 'w-1/3 animate-pulse' : '')
                  }
                  style={phanTram === null ? undefined : { width: `${phanTram}%` }}
                />
              </div>
              <p className="mt-1 text-[11px] text-faint">
                {formatBytes(item.receivedBytes)}
                {item.totalBytes !== null && ` / ${formatBytes(item.totalBytes)}`}
                {item.state === 'PAUSED' && ' — đã tạm dừng'}
                {item.speedBytesPerSecond !== null &&
                  item.state === 'IN_PROGRESS' &&
                  ` — ${formatBytes(item.speedBytesPerSecond)}/giây`}
                {timeRemaining(item) && ` — còn ${timeRemaining(item)}`}
              </p>
            </>
          )}

          {!dangChay && (
            <p className="mt-1 text-[11px] text-faint">
              {item.state === 'COMPLETED' && (
                <>
                  {formatBytes(item.receivedBytes)}
                  {!item.onThisDevice && ' — đã tải trên máy khác'}
                </>
              )}
              {item.state === 'CANCELLED' && 'Đã huỷ'}
              {item.state === 'INTERRUPTED' && 'Bị gián đoạn'}
            </p>
          )}

          <div className="mt-1.5 flex flex-wrap gap-2 text-[12px]">
            {item.state === 'IN_PROGRESS' && (
              <SmallButton onClick={() => tamDung(item.id)}>Tạm dừng</SmallButton>
            )}
            {item.state === 'PAUSED' && <SmallButton onClick={() => tiepTuc(item.id)}>Tiếp tục</SmallButton>}
            {dangChay && <SmallButton onClick={() => huy(item.id)}>Huỷ</SmallButton>}

            {/* Chỉ hiện "Mở tệp" khi tệp THẬT SỰ nằm trên máy này. Một nút mở
                một tệp không tồn tại là nút chỉ biết báo lỗi. */}
            {item.state === 'COMPLETED' && item.onThisDevice && (
              <>
                <SmallButton onClick={() => void moTep(item.id)}>Mở tệp</SmallButton>
                <SmallButton onClick={() => moThuMuc(item.id)}>Mở thư mục</SmallButton>
              </>
            )}
            {item.state === 'INTERRUPTED' && (
              <SmallButton onClick={() => tiepTuc(item.id)}>Thử lại</SmallButton>
            )}
          </div>
        </div>

        <button
          onClick={() => deleteVisit(item.id)}
          className="hidden h-6 w-6 shrink-0 items-center justify-center rounded-full text-faint
                     transition hover:bg-danger/15 hover:text-danger focus-visible:outline-none
                     group-hover:flex"
          aria-label={`Xoá ${item.fileName} khỏi danh sách`}
          // Nói rõ nó KHÔNG xoá tệp: đây là chỗ người dùng dễ hiểu nhầm nhất,
          // và hiểu nhầm theo hướng "tưởng đã xoá tệp mà thật ra chưa".
          title="Xoá khỏi danh sách (không xoá tệp trên đĩa)"
        >
          <CloseIcon className="h-3 w-3" strokeWidth={2.2} />
        </button>
      </div>
    </li>
  )
}

function SmallButton({
  onClick,
  children
}: {
  onClick: () => void
  children: React.ReactNode
}): JSX.Element {
  return (
    <button
      onClick={onClick}
      className="rounded-md px-2 py-0.5 text-brand transition hover:bg-brand-soft
                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
    >
      {children}
    </button>
  )
}

export default DownloadsPanel
