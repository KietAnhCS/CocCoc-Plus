import { useState, type FormEvent, type JSX, type ReactNode } from 'react'
import { fetchStatus, saveApiKey, type FootballStatus } from '../../lib/footballApi'
import { useFootballStore } from '../../store/footballStore'
import { useSessionStore } from '../../store/sessionStore'
import {
  AlertIcon,
  BoltIcon,
  DatabaseIcon,
  ExitIcon,
  KeyIcon,
  ShieldIcon,
  SpinnerIcon
} from '../icons'
import { ACCENT, ScreenTitle } from './glass'
import { useResource } from './useResource'

/**
 * Tab Hồ sơ — bản chuyển của `ProfileView`.
 *
 * <h3>Ai là "người dùng" ở đây</h3>
 *
 * Bản gốc có hệ tài khoản riêng của FootballTracker. Bản này KHÔNG dựng hệ thứ
 * hai — nó đọc thẳng phiên đăng nhập sẵn có của VnSearch (`/api/auth/*`). Vì
 * vậy football-service không hề biết người dùng là ai, và đó là chủ ý: nó chỉ
 * là một tầng đệm trước API-Football, không giữ danh tính của ai cả.
 *
 * <h3>Vì sao có ô hạn mức</h3>
 *
 * Gói miễn phí cho 100 lượt gọi ra ngoài mỗi ngày, và mọi hành vi lạ của phần
 * bóng đá — tỉ số đứng im, danh sách rỗng bất thường — đều quy về đúng con số
 * ấy. Đây là chỗ duy nhất trong giao diện nói ra nó, nên khi có gì đó không ổn
 * thì người dùng có một nơi để nhìn thay vì phải đoán.
 */
function ProfileTab(): JSX.Element {
  const user = useSessionStore((s) => s.user)
  const signOut = useSessionStore((s) => s.signOut)
  const openScreen = useSessionStore((s) => s.openScreen)

  const favourites = useFootballStore((s) => s.favourites)

  const status = useResource<FootballStatus>('football-status', fetchStatus)
  const quota = status.data

  const name = user?.username ?? 'Khách'

  return (
    <>
      <header className="mb-6 flex flex-col items-center gap-3.5">
        <ScreenTitle>Hồ sơ</ScreenTitle>

        <span
          className="mt-2 flex h-[84px] w-[84px] items-center justify-center rounded-full font-display text-[32px] font-bold text-white"
          style={{
            background: 'linear-gradient(135deg, rgba(0,200,83,0.35), rgba(21,101,192,0.35))'
          }}
          aria-hidden="true"
        >
          {name.slice(0, 1).toUpperCase()}
        </span>

        <div className="text-center">
          <p className="font-display text-[20px] font-bold text-white">{name}</p>
          <p className="mt-0.5 text-[13px] text-white/50">
            {user ? `Tài khoản VnSearch · ${user.role}` : 'Chưa đăng nhập'}
          </p>
        </div>
      </header>

      <div className="flex flex-col gap-3">
        <InfoCard
          icon={<ShieldIcon className="h-4 w-4" />}
          title="Đội đã ghim"
          value={favourites.length === 0 ? 'Chưa ghim đội nào' : `${favourites.length} đội`}
        />
        <InfoCard
          icon={<DatabaseIcon className="h-4 w-4" />}
          title="Nguồn dữ liệu"
          value={
            status.loading
              ? 'Đang kiểm tra…'
              : status.failed
                ? 'Không kết nối được service'
                : quota?.sampleOnly
                  ? 'Chưa có khoá API — không có dữ liệu'
                  : 'API-Football (gói miễn phí)'
          }
        />

        {quota && !quota.sampleOnly && (
          <InfoCard
            icon={<BoltIcon className="h-4 w-4" />}
            title="Hạn mức hôm nay"
            value={`${quota.used}/${quota.budget} lượt gọi · còn ${quota.remaining}`}
          >
            <span className="mt-2 block h-[5px] w-full overflow-hidden rounded-full bg-white/12">
              <span
                className="block h-full rounded-full transition-[width] duration-500"
                style={{
                  width: `${Math.min(100, (quota.used / Math.max(1, quota.budget)) * 100)}%`,
                  // Đỏ khi đã dùng quá 80%: qua mốc đó thì phần còn lại của
                  // ngày rất dễ rơi vào cảnh chỉ còn dữ liệu cũ, và đó là thứ
                  // đáng báo TRƯỚC chứ không phải sau khi đã hết.
                  background: quota.used > quota.budget * 0.8 ? '#FF5252' : ACCENT
                }}
              />
            </span>
          </InfoCard>
        )}

        {quota?.sampleOnly === true && <ApiKeyCard onSaved={status.reload} />}
      </div>

      <div className="mt-7">
        {user ? (
          <button
            onClick={() => void signOut()}
            className="glass flex w-full items-center justify-center gap-2.5 rounded-full py-3
                       text-[14px] font-bold text-[#FF8A80] transition hover:brightness-110
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60"
          >
            <ExitIcon className="h-4 w-4" />
            Đăng xuất
          </button>
        ) : (
          <button
            onClick={() => openScreen('signin')}
            className="glass-tinted flex w-full items-center justify-center gap-2.5 rounded-full py-3
                       text-[14px] font-bold text-white transition hover:brightness-110
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60"
          >
            Đăng nhập VnSearch
          </button>
        )}
      </div>
    </>
  )
}

/**
 * Ô dán khoá API.
 *
 * <h3>Vì sao dán ở đây chứ không sửa `.env`</h3>
 *
 * Con đường kia đòi người dùng rời ứng dụng, mở một tệp ẩn ở thư mục gốc, rồi
 * gõ một câu lệnh Docker. Ba bước ấy là chỗ phần lớn người thử repo này bỏ
 * cuộc, rồi kết luận phần bóng đá chỉ có dữ liệu giả. Dán vào đây thì service
 * tự kiểm tra khoá, tự lưu, và dữ liệu thật về ngay — không khởi động lại gì.
 *
 * Chỉ hiện khi CHƯA có khoá. Có rồi mà vẫn để một ô nhập khoá nằm đó thì nó
 * chỉ còn là một chỗ để lỡ tay dán nhầm.
 */
function ApiKeyCard({ onSaved }: { onSaved: () => void }): JSX.Element {
  const [key, setKey] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function submit(event: FormEvent): void {
    event.preventDefault()
    if (key.trim() === '' || busy) {
      return
    }
    setBusy(true)
    setError(null)

    void saveApiKey(key)
      .then(() => {
        setKey('')
        onSaved()
      })
      .catch((cause: Error) => setError(cause.message))
      .finally(() => setBusy(false))
  }

  return (
    <form onSubmit={submit} className="glass flex flex-col gap-3 rounded-2xl px-4 py-4">
      <div className="flex items-start gap-3.5">
        <span
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl"
          style={{ background: 'rgba(0,200,83,0.12)', color: ACCENT }}
        >
          <KeyIcon className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[14.5px] font-semibold text-white">Khoá API-Football</span>
          <span className="mt-0.5 block text-[12px] leading-relaxed text-white/55">
            Dán khoá vào đây là có tỉ số thật ngay — không cần sửa tệp hay khởi động lại.
          </span>
        </span>
      </div>

      <input
        value={key}
        onChange={(event) => setKey(event.target.value)}
        placeholder="Dán khoá vào đây"
        spellCheck={false}
        autoComplete="off"
        aria-label="Khoá API-Football"
        className="glass-inner h-10 w-full rounded-xl px-3 font-mono text-[12px] text-white
                   placeholder:text-white/35 focus:outline-none focus:ring-2 focus:ring-white/40"
      />

      {error !== null && (
        <p className="flex items-start gap-2 text-[12px] leading-relaxed text-[#FF8A80]">
          <AlertIcon className="mt-px h-3.5 w-3.5 shrink-0" />
          <span className="min-w-0">{error}</span>
        </p>
      )}

      <button
        type="submit"
        disabled={key.trim() === '' || busy}
        className="glass-tinted flex items-center justify-center gap-2 rounded-full py-2.5
                   text-[13.5px] font-bold text-white transition hover:brightness-110
                   disabled:cursor-not-allowed disabled:opacity-45 focus-visible:outline-none
                   focus-visible:ring-2 focus-visible:ring-white/60"
      >
        {busy && <SpinnerIcon className="h-4 w-4" />}
        {busy ? 'Đang kiểm tra khoá…' : 'Lưu khoá'}
      </button>

      <p className="text-[11.5px] leading-relaxed text-white/45">
        Chưa có khoá? Lấy miễn phí (100 lượt/ngày) ở api-football.com, hoặc trên RapidAPI thì nhớ
        bấm <span className="text-white/70">Subscribe to Test → Basic $0</span> trước — chưa đăng ký
        thì khoá đúng vẫn bị trả về 403.
      </p>
    </form>
  )
}

function InfoCard({
  icon,
  title,
  value,
  children
}: {
  icon: ReactNode
  title: string
  value: string
  children?: ReactNode
}): JSX.Element {
  return (
    <div className="glass flex items-start gap-3.5 rounded-2xl px-4 py-3.5">
      <span
        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl"
        style={{ background: 'rgba(0,200,83,0.12)', color: ACCENT }}
      >
        {icon}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[12px] text-white/50">{title}</span>
        <span className="block truncate text-[14.5px] font-semibold text-white">{value}</span>
        {children}
      </span>
    </div>
  )
}

export default ProfileTab
