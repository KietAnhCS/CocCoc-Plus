import { useEffect, useState, type JSX } from 'react'
import { useSettingsStore, type TuyChon } from '../store/settingsStore'
import { usePagesStore } from '../store/pagesStore'
import { useOverlayStore } from '../store/overlayStore'
import { CloseIcon, SettingsIcon } from './icons'

/**
 * Trang "Cài đặt".
 *
 * <p>Mục này trong menu trước đây bị vô hiệu hoá với chú thích "Chưa có trang
 * cài đặt". Nay nó đọc/ghi qua {@code settingsStore}, vốn lưu hai tầng:
 * localStorage để áp ngay lúc mở ứng dụng, và settings-service để đồng bộ giữa
 * các máy.
 */
function SettingsPage(): JSX.Element | null {
  const dangMo = usePagesStore((s) => s.page) === 'settings'
  const dong = usePagesStore((s) => s.dong)

  const tuyChon = useSettingsStore((s) => s.tuyChon)
  const chiCucBo = useSettingsStore((s) => s.chiCucBo)
  const loi = useSettingsStore((s) => s.loi)
  const nap = useSettingsStore((s) => s.nap)
  const dat = useSettingsStore((s) => s.dat)
  const resetToDefaults = useSettingsStore((s) => s.resetToDefaults)

  const acquire = useOverlayStore((s) => s.acquire)
  const release = useOverlayStore((s) => s.release)

  const [thuMucTai, setThuMucTai] = useState('')

  useEffect(() => {
    if (!dangMo) {
      return
    }
    acquire()
    void nap()
    void window.downloads.defaultDirectory().then(setThuMucTai)
    return () => release()
  }, [dangMo, acquire, release, nap])

  if (!dangMo) {
    return null
  }

  return (
    <div className="absolute inset-0 z-30 flex flex-col bg-surface">
      <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line px-5">
        <SettingsIcon className="h-5 w-5 shrink-0 text-muted" />
        <h1 className="flex-1 text-[15px] font-semibold text-ink">Cài đặt</h1>
        <button onClick={dong} className="icon-btn" aria-label="Đóng cài đặt" title="Đóng (Esc)">
          <CloseIcon className="h-4 w-4" strokeWidth={2.2} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto max-w-2xl px-6 py-6">
          {/* Nói THẲNG khi tuỳ chọn không đồng bộ được. Im lặng thì người dùng
              đổi cài đặt trên máy này, mở máy khác, thấy giá trị cũ, và không
              hiểu vì sao. */}
          {chiCucBo && (
            <p className="mb-5 rounded-lg bg-warning/10 px-4 py-3 text-[12px] leading-relaxed text-warning">
              {loi ?? 'Chưa đăng nhập — tuỳ chọn chỉ lưu trên máy này, không đồng bộ.'}
            </p>
          )}

          <Nhom tieuDe="Giao diện">
            <ChonMot
              nhan="Chủ đề"
              moTa="Áp cho vỏ trình duyệt. Trang web tự quyết định giao diện của chúng."
              giaTri={tuyChon.theme}
              luaChon={[
                ['he-thong', 'Theo hệ thống'],
                ['sang', 'Sáng'],
                ['toi', 'Tối']
              ]}
              onChange={(value) => void dat('theme', value as TuyChon['theme'])}
            />
            <CongTac
              nhan="Hiện thanh dấu trang"
              moTa="Dải dấu trang ngay dưới thanh địa chỉ."
              bat={tuyChon.hienThanhDauTrang}
              onChange={(value) => void dat('hienThanhDauTrang', value)}
            />
          </Nhom>

          <Nhom tieuDe="Khởi động và tìm kiếm">
            <OChu
              nhan="Trang chủ"
              moTa="Mở khi bấm nút Trang chủ hoặc Alt+Home."
              giaTri={tuyChon.homePage}
              onChange={(value) => void dat('homePage', value)}
            />
            <CongTac
              nhan="Gợi ý từ lịch sử tìm kiếm"
              moTa="Ô địa chỉ gợi ý những gì bạn đã tìm trước đó, bên cạnh gợi ý từ chỉ mục."
              bat={tuyChon.goiYTuLichSu}
              onChange={(value) => void dat('goiYTuLichSu', value)}
            />
          </Nhom>

          <Nhom tieuDe="Quyền riêng tư">
            <CongTac
              nhan="Lưu lịch sử duyệt web"
              moTa="Tắt thì trình duyệt không gửi lượt ghé thăm nào lên máy chủ — giống như
                    mọi thẻ đều ẩn danh. Lịch sử đã lưu trước đó không bị xoá."
              bat={tuyChon.luuLichSu}
              onChange={(value) => void dat('luuLichSu', value)}
            />
            <p className="px-1 pb-2 text-[12px] leading-relaxed text-faint">
              Lịch sử tự xoá sau <b>90 ngày</b> và lịch sử tìm kiếm sau <b>30 ngày</b>, kể cả khi
              bạn không làm gì. Thẻ ẩn danh không bao giờ được ghi lại.
            </p>
          </Nhom>

          <Nhom tieuDe="Tải xuống">
            <CongTac
              nhan="Hỏi vị trí lưu cho mỗi tệp"
              moTa="Tắt thì tệp tải thẳng vào thư mục mặc định."
              bat={tuyChon.hoiChoLuuTep}
              onChange={(value) => void dat('hoiChoLuuTep', value)}
            />
            <div className="px-1 py-2">
              <p className="text-[13px] text-ink">Thư mục mặc định</p>
              <p className="mt-0.5 break-all text-[12px] text-faint">
                {thuMucTai || 'Đang xác định…'}
              </p>
            </div>
          </Nhom>

          <div className="mt-8 border-t border-line pt-5">
            <button
              onClick={() => void resetToDefaults()}
              className="rounded-lg border border-line px-4 py-2 text-[13px] text-muted
                         transition hover:border-danger/40 hover:text-danger
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-danger/40"
            >
              Khôi phục cài đặt gốc
            </button>
            <p className="mt-2 text-[12px] text-faint">
              Đưa mọi tuỳ chọn ở trên về mặc định, trên mọi thiết bị. Không xoá dấu trang, lịch sử
              hay tệp đã tải.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

function Nhom({ tieuDe, children }: { tieuDe: string; children: React.ReactNode }): JSX.Element {
  return (
    <section className="mb-7">
      <h2 className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-faint">
        {tieuDe}
      </h2>
      <div className="rounded-xl border border-line px-3 py-1">{children}</div>
    </section>
  )
}

function CongTac({
  nhan,
  moTa,
  bat,
  onChange
}: {
  nhan: string
  moTa: string
  bat: boolean
  onChange: (value: boolean) => void
}): JSX.Element {
  return (
    <label className="flex cursor-pointer items-start gap-3 px-1 py-3">
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] text-ink">{nhan}</span>
        <span className="mt-0.5 block text-[12px] leading-relaxed text-faint">{moTa}</span>
      </span>
      {/* Ô chọn thật (`<input type="checkbox">`) chứ không phải một div bấm
          được: nó nhận tiêu điểm bằng bàn phím, đọc được bằng trình đọc màn
          hình, và bật/tắt bằng phím cách — cả ba thứ phải viết tay nếu tự vẽ. */}
      <input
        type="checkbox"
        checked={bat}
        onChange={(event) => onChange(event.target.checked)}
        className="mt-0.5 h-4 w-4 shrink-0 accent-brand"
      />
    </label>
  )
}

function ChonMot({
  nhan,
  moTa,
  giaTri,
  luaChon,
  onChange
}: {
  nhan: string
  moTa: string
  giaTri: string
  luaChon: Array<[string, string]>
  onChange: (value: string) => void
}): JSX.Element {
  return (
    <div className="flex items-start gap-3 px-1 py-3">
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] text-ink">{nhan}</span>
        <span className="mt-0.5 block text-[12px] leading-relaxed text-faint">{moTa}</span>
      </span>
      <select
        value={giaTri}
        onChange={(event) => onChange(event.target.value)}
        className="h-8 shrink-0 rounded-lg border border-line bg-omni px-2 text-[13px] text-ink
                   focus:border-brand/50 focus:outline-none focus:ring-2 focus:ring-brand/15"
        aria-label={nhan}
      >
        {luaChon.map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
    </div>
  )
}

function OChu({
  nhan,
  moTa,
  giaTri,
  onChange
}: {
  nhan: string
  moTa: string
  giaTri: string
  onChange: (value: string) => void
}): JSX.Element {
  const [nhap, setNhap] = useState(giaTri)
  const [giaTriTruoc, setGiaTriTruoc] = useState(giaTri)

  // Đồng bộ khi giá trị đổi từ NƠI KHÁC (máy chủ trả về, hoặc bấm "Khôi phục
  // cài đặt gốc"). Không đồng bộ thì ô chữ giữ nguyên giá trị cũ trong khi
  // trạng thái thật đã đổi.
  //
  // VÌ SAO KHÔNG DÙNG useEffect. Đặt setState trong một effect khiến React vẽ
  // MỘT LẦN với giá trị cũ rồi vẽ lại — người dùng thấy giá trị cũ nhấp nháy
  // một khung hình. Điều chỉnh ngay trong thân hàm là cách React khuyến nghị
  // cho đúng tình huống này ("adjusting state when a prop changes"): React
  // phát hiện setState lúc đang vẽ và vẽ lại NGAY, trước khi chạm tới màn
  // hình. Quy tắc eslint `react-hooks/set-state-in-effect` canh đúng chỗ này.
  if (giaTri !== giaTriTruoc) {
    setGiaTriTruoc(giaTri)
    setNhap(giaTri)
  }

  return (
    <div className="flex items-start gap-3 px-1 py-3">
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] text-ink">{nhan}</span>
        <span className="mt-0.5 block text-[12px] leading-relaxed text-faint">{moTa}</span>
      </span>
      <input
        value={nhap}
        onChange={(event) => setNhap(event.target.value)}
        // Ghi khi RỜI ô, không ghi theo từng phím: gõ một địa chỉ 30 ký tự sẽ
        // là 30 lượt gọi mạng, và 29 trong số đó ghi một địa chỉ dở dang.
        onBlur={() => onChange(nhap.trim())}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.currentTarget.blur()
          }
        }}
        className="h-8 w-64 shrink-0 rounded-lg border border-line bg-omni px-2 text-[13px]
                   text-ink focus:border-brand/50 focus:outline-none focus:ring-2 focus:ring-brand/15"
        spellCheck={false}
        aria-label={nhan}
      />
    </div>
  )
}

export default SettingsPage
