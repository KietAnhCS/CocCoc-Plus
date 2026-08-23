import { useEffect, useState, type JSX } from 'react'
import { useBrowsingStore, nhomTheoNgay, gioPhut, type Khoang } from '../store/browsingStore'
import { useOverlayStore } from '../store/overlayStore'
import { usePagesStore } from '../store/pagesStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import { CloseIcon, ClockIcon, SearchIcon, TrashIcon } from './icons'

/**
 * Trang "Nhật ký" — lịch sử duyệt web đầy đủ.
 *
 * <p>Trước đây chức năng này chỉ là một menu con hiện 8 địa chỉ gần nhất, lấy
 * từ chồng back/forward trong bộ nhớ — nên nó mất sạch mỗi lần đóng ứng dụng
 * và không tìm kiếm được. Nay nó đọc từ history-service.
 *
 * <h2>Vì sao là một lớp phủ chứ không phải một thẻ mới</h2>
 *
 * <p>Chrome mở {@code chrome://history} thành một thẻ, và đó là lựa chọn hợp
 * lý ở đó. Ở đây thì không: nội dung của thẻ được vẽ bởi một
 * {@code WebContentsView} riêng nằm NGOÀI cây React, nên một trang React không
 * hiển thị trong thẻ được. Lớp phủ giữ đúng một cách vẽ giao diện nội bộ.
 */
function HistoryPage(): JSX.Element | null {
  const dangMo = usePagesStore((s) => s.page) === 'history'
  const dong = usePagesStore((s) => s.dong)

  const items = useBrowsingStore((s) => s.items)
  const tuKhoa = useBrowsingStore((s) => s.tuKhoa)
  const tong = useBrowsingStore((s) => s.tong)
  const dangTai = useBrowsingStore((s) => s.dangTai)
  const loi = useBrowsingStore((s) => s.loi)
  const chuaDangNhap = useBrowsingStore((s) => s.chuaDangNhap)
  const nap = useBrowsingStore((s) => s.nap)
  const xoaMuc = useBrowsingStore((s) => s.xoaMuc)

  const navigate = useTabStore((s) => s.navigate)
  const acquire = useOverlayStore((s) => s.acquire)
  const release = useOverlayStore((s) => s.release)

  const [oTim, setOTim] = useState('')

  // Giữ chỗ trong bộ đếm lớp phủ: tiến trình chính ẩn WebContentsView của thẻ
  // khi bộ đếm > 0. Không giữ thì nội dung trang web vẽ ĐÈ lên lớp phủ này —
  // WebContentsView là một khung gốc của hệ điều hành, nó không tuân theo
  // z-index của CSS.
  useEffect(() => {
    if (!dangMo) {
      return
    }
    acquire()
    void nap('')
    return () => release()
  }, [dangMo, acquire, release, nap])

  // Giãn nhịp gõ 250 ms. Tìm ngay mỗi phím sẽ bắn một request cho mỗi ký tự,
  // và với một truy vấn regex trên Mongo thì đó là chi phí thật ở phía máy chủ.
  useEffect(() => {
    if (!dangMo) {
      return
    }
    const timer = window.setTimeout(() => void nap(oTim), 250)
    return () => window.clearTimeout(timer)
  }, [oTim, dangMo, nap])

  if (!dangMo) {
    return null
  }

  const nhom = nhomTheoNgay(items)

  return (
    <div className="absolute inset-0 z-30 flex flex-col bg-surface">
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line px-5">
        <ClockIcon className="h-5 w-5 shrink-0 text-muted" />
        <h1 className="shrink-0 text-[15px] font-semibold text-ink">Nhật ký</h1>

        <div className="relative ml-4 min-w-0 flex-1">
          <SearchIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
          <input
            value={oTim}
            onChange={(event) => setOTim(event.target.value)}
            className="h-9 w-full rounded-lg border border-line bg-omni pl-9 pr-3 text-[13px]
                       text-ink placeholder:text-faint transition focus:border-brand/50
                       focus:outline-none focus:ring-2 focus:ring-brand/15"
            placeholder="Tìm trong nhật ký…"
            spellCheck={false}
            aria-label="Tìm trong nhật ký"
          />
        </div>

        <span className="shrink-0 text-[12px] tabular-nums text-faint">
          {tong.toLocaleString('vi-VN')} mục
        </span>

        <NutXoaDuLieu />

        <button
          onClick={dong}
          className="icon-btn shrink-0"
          aria-label="Đóng nhật ký"
          title="Đóng (Esc)"
        >
          <CloseIcon className="h-4 w-4" strokeWidth={2.2} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
        {chuaDangNhap && (
          <TrangThaiRong
            tieuDe="Đăng nhập để xem nhật ký"
            moTa="Lịch sử duyệt web được lưu theo tài khoản và đồng bộ giữa các máy.
                  Bấm avatar trên thanh công cụ để đăng nhập."
          />
        )}
        {loi && !chuaDangNhap && (
          <p className="rounded-lg bg-danger/10 px-4 py-3 text-[13px] text-danger" role="alert">
            {loi}
          </p>
        )}
        {!chuaDangNhap && !loi && dangTai && items.length === 0 && (
          <p className="py-10 text-center text-[13px] text-faint">Đang tải…</p>
        )}
        {!chuaDangNhap && !loi && !dangTai && items.length === 0 && (
          <TrangThaiRong
            tieuDe={tuKhoa ? 'Không tìm thấy gì' : 'Nhật ký còn trống'}
            moTa={
              tuKhoa
                ? `Không có mục nào khớp “${tuKhoa}”.`
                : 'Các trang bạn ghé thăm sẽ hiện ở đây. Thẻ ẩn danh không được ghi lại.'
            }
          />
        )}

        {nhom.map(([nhan, danhSach]) => (
          <section key={nhan} className="mb-6">
            <h2
              className="sticky top-0 z-10 bg-surface py-2 text-[12px] font-semibold
                           uppercase tracking-wide text-faint"
            >
              {nhan}
            </h2>
            <ul>
              {danhSach.map((item) => (
                <li
                  key={item.id}
                  className="group flex items-center gap-3 rounded-lg pr-2 hover:bg-raised"
                >
                  <span className="w-14 shrink-0 pl-2 text-[12px] tabular-nums text-faint">
                    {gioPhut(item.visitedAt)}
                  </span>
                  <button
                    onClick={() => {
                      void navigate(item.url)
                      dong()
                    }}
                    className="flex min-w-0 flex-1 items-center gap-2.5 py-2 text-left
                               focus-visible:outline-none"
                    title={item.url}
                  >
                    <span
                      className="flex h-5 w-5 shrink-0 items-center justify-center rounded-[6px]
                                 text-[10px] font-bold text-white"
                      style={{ background: siteGradient(item.url) }}
                    >
                      {siteInitial(item.url)}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] text-ink">
                        {item.title || item.url}
                      </span>
                      <span className="block truncate text-[11px] text-faint">
                        {hostOf(item.url)}
                        {/* Ghé nhiều lần thì gộp thành một dòng — xem
                            HistoryService.ghiLuotGhe. Hiện số lần để người
                            dùng hiểu vì sao chỉ có một dòng. */}
                        {item.visitCount > 1 && ` — ${item.visitCount} lần`}
                      </span>
                    </span>
                  </button>
                  <button
                    onClick={() => void xoaMuc(item.id)}
                    className="hidden h-7 w-7 shrink-0 items-center justify-center rounded-full
                               text-faint transition hover:bg-danger/15 hover:text-danger
                               focus-visible:outline-none group-hover:flex"
                    aria-label={`Xoá ${item.title || item.url} khỏi nhật ký`}
                  >
                    <CloseIcon className="h-3 w-3" strokeWidth={2.2} />
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}

/**
 * Nút "Xoá dữ liệu duyệt web" kèm hộp xác nhận.
 *
 * <h2>Vì sao PHẢI hỏi lại</h2>
 *
 * <p>Đây là thao tác không hoàn tác được, và nó xoá thứ mà người dùng không
 * nhìn thấy hết (họ chỉ thấy 100 mục đầu, còn lệnh xoá chạm tới tất cả). Hộp
 * xác nhận nói rõ CON SỐ sẽ bị xoá — "Xoá 1.247 mục?" là một câu hỏi trả lời
 * được, còn "Bạn có chắc không?" thì không.
 */
function NutXoaDuLieu(): JSX.Element {
  const [moHop, setMoHop] = useState(false)
  const [khoang, setKhoang] = useState<Khoang>('gio-qua')
  const [dangXoa, setDangXoa] = useState(false)
  const tong = useBrowsingStore((s) => s.tong)
  const xoaTheoKhoang = useBrowsingStore((s) => s.xoaTheoKhoang)

  async function xacNhan(): Promise<void> {
    setDangXoa(true)
    try {
      await xoaTheoKhoang(khoang)
      setMoHop(false)
    } finally {
      setDangXoa(false)
    }
  }

  return (
    <>
      <button
        onClick={() => setMoHop(true)}
        className="icon-btn shrink-0"
        aria-label="Xoá dữ liệu duyệt web"
        title="Xoá dữ liệu duyệt web (Ctrl+Shift+Del)"
      >
        <TrashIcon className="h-4 w-4" />
      </button>

      {moHop && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-6"
          role="dialog"
          aria-modal="true"
          aria-label="Xoá dữ liệu duyệt web"
        >
          <div className="w-full max-w-md rounded-2xl border border-line bg-surface p-6 shadow-pop">
            <h2 className="text-[15px] font-semibold text-ink">Xoá dữ liệu duyệt web</h2>
            <p className="mt-2 text-[13px] leading-relaxed text-muted">
              Thao tác này <b>không hoàn tác được</b>. Nó xoá lịch sử ghé thăm và lịch sử tìm kiếm
              trên <b>mọi thiết bị</b> dùng tài khoản này. Dấu trang và mật khẩu không bị ảnh hưởng.
            </p>

            <fieldset className="mt-4">
              <legend className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-faint">
                Khoảng thời gian
              </legend>
              {(
                [
                  ['gio-qua', 'Một giờ qua'],
                  ['hom-nay', 'Hôm nay'],
                  ['tuan-qua', 'Bảy ngày qua'],
                  ['tat-ca', `Toàn bộ (${tong.toLocaleString('vi-VN')} mục)`]
                ] as Array<[Khoang, string]>
              ).map(([value, nhan]) => (
                <label
                  key={value}
                  className="flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5
                             text-[13px] text-ink hover:bg-raised"
                >
                  <input
                    type="radio"
                    name="khoang-xoa"
                    checked={khoang === value}
                    onChange={() => setKhoang(value)}
                    className="accent-brand"
                  />
                  {nhan}
                </label>
              ))}
            </fieldset>

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setMoHop(false)}
                className="rounded-lg px-4 py-2 text-[13px] text-muted transition
                           hover:bg-raised hover:text-ink focus-visible:outline-none
                           focus-visible:ring-2 focus-visible:ring-brand/60"
              >
                Huỷ
              </button>
              <button
                onClick={() => void xacNhan()}
                disabled={dangXoa}
                className="rounded-lg bg-danger px-4 py-2 text-[13px] font-medium text-white
                           transition hover:brightness-110 disabled:opacity-60
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-danger/60"
              >
                {dangXoa ? 'Đang xoá…' : 'Xoá'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function TrangThaiRong({ tieuDe, moTa }: { tieuDe: string; moTa: string }): JSX.Element {
  return (
    <div className="flex flex-col items-center px-8 py-20 text-center">
      <span className="flex h-14 w-14 items-center justify-center rounded-full bg-raised text-faint">
        <ClockIcon className="h-7 w-7" />
      </span>
      <p className="mt-4 text-[14px] font-medium text-ink">{tieuDe}</p>
      <p className="mt-1.5 max-w-sm text-[12px] leading-relaxed text-faint">{moTa}</p>
    </div>
  )
}

export default HistoryPage
