import { useEffect, useState, type JSX } from 'react'
import { useAdminCredential, useAdminStore } from '../../store/adminStore'
import { useDashboardStore } from '../../store/dashboardStore'
import { useOverlayStore } from '../../store/overlayStore'
import { useSessionStore } from '../../store/sessionStore'
import type { AdminCredential, DashboardDto } from '../../lib/adminApi'
import { coverageRatio, foldTail, headShare, normalizedEntropy, ratio } from '../../lib/analysis'
import {
  bytes,
  compact,
  count,
  dateTime,
  dayLabel,
  millis,
  percent,
  shortUrl
} from '../../lib/format'
import AdminLogin from './AdminLogin'
import PermissionMatrix from './PermissionMatrix'
import AccountsTable from './AccountsTable'
import { BarList, ChartCard, ColumnChart, ShareBar, StatTile, TrendChart } from './charts'
import {
  AlertIcon,
  ChartIcon,
  CloseIcon,
  DatabaseIcon,
  ExitIcon,
  ReloadIcon,
  ShieldCheckIcon,
  SpinnerIcon,
  TrashIcon,
  UsersIcon
} from '../icons'

/** Chu kỳ tự làm mới. Đủ nhanh để thấy hoạt động, đủ chậm để không đốt pin. */
const REFRESH_MS = 10_000

/**
 * Bảng điều khiển quản trị — toàn màn hình, chỉ vai trò ADMIN thấy nội dung.
 *
 * BA THỨ ĐƯỢC TRÌNH BÀY, THEO ĐÚNG THỨ TỰ CÂU HỎI NGƯỜI VẬN HÀNH ĐẶT RA:
 *
 *   1. Người dùng đang làm gì   → lưu lượng, truy vấn, liên kết được bấm
 *   2. Máy tìm kiếm biết những gì → corpus đã crawl
 *   3. Ai được xem những thứ trên → bảng phân quyền
 *
 * Mục 3 không phải phần trang trí: cả tính năng này tồn tại để thể hiện ranh
 * giới quyền, nên ranh giới đó phải nhìn thấy được ngay trong sản phẩm.
 */
function AdminPanel(): JSX.Element | null {
  const open = useAdminStore((state) => state.dashboardOpen)
  const close = useAdminStore((state) => state.closeDashboard)
  // Bằng chứng quyền: token của một tài khoản ADMIN, hoặc khoá tĩnh. Cái nào
  // có thì dùng cái đó — xem `useAdminCredential`.
  const credential = useAdminCredential()
  const acquire = useOverlayStore((state) => state.acquire)
  const release = useOverlayStore((state) => state.release)

  useEffect(() => {
    if (!open) {
      return undefined
    }
    // Khung nội dung web là một WebContentsView của Electron — nó nằm TRÊN mọi
    // thứ React vẽ ra. Không giành lấy lớp phủ thì bảng điều khiển bị trang web
    // đang mở che mất một nửa.
    acquire()
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        close()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      release()
    }
  }, [open, acquire, release, close])

  if (!open) {
    return null
  }

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-surface"
      role="dialog"
      aria-modal="true"
      aria-label="Bảng điều khiển quản trị"
    >
      {credential ? (
        <Dashboard credential={credential} onClose={close} />
      ) : (
        <GuestView onClose={close} />
      )}
    </div>
  )
}

/** Người chưa có quyền: một cửa xác thực, và nói rõ vì sao cửa này tồn tại. */
function GuestView({ onClose }: { onClose: () => void }): JSX.Element {
  return (
    <>
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-line px-4">
        <h1 className="flex-1 text-[13px] font-semibold text-ink">Bảng điều khiển quản trị</h1>
        <button onClick={onClose} className="icon-btn" aria-label="Đóng" title="Đóng (Esc)">
          <CloseIcon className="h-4 w-4" strokeWidth={2.2} />
        </button>
      </header>
      <div className="min-h-0 flex-1">
        <AdminLogin />
      </div>
    </>
  )
}

function Dashboard({
  credential,
  onClose
}: {
  credential: AdminCredential
  onClose: () => void
}): JSX.Element {
  const clearKey = useAdminStore((state) => state.clearKey)
  const signOutAccount = useSessionStore((state) => state.signOut)
  const currentUser = useSessionStore((state) => state.user)

  const data = useDashboardStore((state) => state.data)
  const error = useDashboardStore((state) => state.error)
  const loading = useDashboardStore((state) => state.loading)
  const loadDashboard = useDashboardStore((state) => state.load)
  const clearDashboard = useDashboardStore((state) => state.clear)
  const resetTrafficData = useDashboardStore((state) => state.resetTraffic)

  const [autoRefresh, setAutoRefresh] = useState(true)

  // Lần tải đầu: `false` = không hiện con quay, vì khung xương đã nói lên điều đó.
  useEffect(() => {
    loadDashboard(credential, false)
    return clearDashboard
  }, [credential, loadDashboard, clearDashboard])

  useEffect(() => {
    if (!autoRefresh) {
      return undefined
    }
    const timer = window.setInterval(() => loadDashboard(credential), REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [autoRefresh, credential, loadDashboard])

  return (
    <>
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-line px-4">
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-brand">
          <ShieldCheckIcon className="h-4 w-4" />
        </span>
        <h1 className="text-[13px] font-semibold text-ink">Bảng điều khiển quản trị</h1>
        {/* Nói rõ quyền này đến từ ĐÂU. Hai đường xác thực có hệ quả khác
            nhau (một cái ghi được 'ai đã làm gì', cái kia không), nên người
            đang xem cần biết mình đang đứng ở đường nào. */}
        {credential.kind === 'session' ? (
          <span className="rounded-full bg-success/15 px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-success">
            admin · {currentUser?.username}
          </span>
        ) : (
          <span
            className="rounded-full bg-warn/15 px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-warn"
            title="Đang dùng khoá tĩnh X-API-Key: không có tài khoản nào đứng sau, nên không ghi lại được ai đã thao tác."
          >
            admin · khoá API
          </span>
        )}

        <span className="ml-3 min-w-0 truncate text-[11.5px] text-faint">
          {data ? `Cập nhật ${dateTime(data.generatedAt)}` : 'Đang tải…'}
        </span>

        <div className="ml-auto flex shrink-0 items-center gap-1">
          <label className="mr-1 flex cursor-pointer items-center gap-1.5 text-[11.5px] text-muted">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(event) => setAutoRefresh(event.target.checked)}
              className="accent-[var(--color-brand)]"
            />
            Tự làm mới 10s
          </label>
          <button
            onClick={() => loadDashboard(credential)}
            className="icon-btn"
            aria-label="Làm mới"
            title="Làm mới ngay"
          >
            {loading ? (
              <SpinnerIcon className="h-[17px] w-[17px]" />
            ) : (
              <ReloadIcon className="h-[17px] w-[17px]" />
            )}
          </button>
          <button
            onClick={() => resetTrafficData(credential)}
            className="icon-btn hover:text-danger"
            aria-label="Đặt lại số liệu lưu lượng"
            title="Đặt lại số liệu lưu lượng (không đụng tới chỉ mục)"
          >
            <TrashIcon className="h-[17px] w-[17px]" />
          </button>
          <button
            onClick={() => {
              // Thoát CẢ HAI đường: bỏ khoá tĩnh trong bộ nhớ, và nếu đang
              // đăng nhập bằng tài khoản thì đăng xuất luôn. Bỏ một đường mà
              // giữ đường kia sẽ khiến bảng vẫn mở như chưa có gì xảy ra.
              clearKey()
              if (credential.kind === 'session') {
                void signOutAccount()
              }
            }}
            className="icon-btn"
            aria-label="Thoát quyền quản trị"
            title="Thoát quyền quản trị"
          >
            <ExitIcon className="h-[17px] w-[17px]" />
          </button>
          <div className="mx-1 h-5 w-px bg-line" />
          <button onClick={onClose} className="icon-btn" aria-label="Đóng" title="Đóng (Esc)">
            <CloseIcon className="h-4 w-4" strokeWidth={2.2} />
          </button>
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto bg-chrome/40 px-5 py-5">
        <div className="mx-auto max-w-6xl">
          {error && (
            <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-warn/30 bg-warn/5 px-3.5 py-2.5">
              <AlertIcon className="mt-0.5 h-4 w-4 shrink-0 text-warn" />
              <p className="text-[12.5px] leading-relaxed text-warn">{error}</p>
            </div>
          )}

          {data === null ? (
            <LoadingSkeleton />
          ) : (
            // Giữ nguyên bố cục khi đang tải lại, chỉ mờ đi: dựng lại khung
            // xương mỗi 10 giây sẽ làm trang nhảy và chớp liên tục.
            <div className={loading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
              <DashboardBody data={data} credential={credential} />
            </div>
          )}
        </div>
      </div>
    </>
  )
}

function DashboardBody({
  data,
  credential
}: {
  data: DashboardDto
  credential: AdminCredential
}): JSX.Element {
  const { traffic, crawl, index, accounts } = data

  const queryCounts = traffic.topQueries.map((item) => item.count)
  const diversity = normalizedEntropy(queryCounts)
  const concentration = headShare(queryCounts, 3)
  const coverage = coverageRatio(crawl.documents, crawl.distinctLinkTargets)
  const danglingShare = ratio(crawl.danglingDocuments, crawl.documents)

  return (
    <div className="flex flex-col gap-5">
      {traffic.truncated && (
        <p className="rounded-xl border border-line bg-raised px-3.5 py-2.5 text-[11.5px] leading-relaxed text-muted">
          Một bảng thống kê đã chạm trần bộ nhớ, nên các bảng xếp hạng bên dưới bị thiếu phần đuôi.
          Các con số tổng vẫn đầy đủ. Trần này là có chủ ý — xem <code>UsageAnalyticsService</code>.
        </p>
      )}

      {/* ---- 1. Người dùng đang làm gì ---- */}
      <SectionTitle icon={<ChartIcon className="h-4 w-4" />} text="Lưu lượng sử dụng" />

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <StatTile
          label="Người truy cập"
          value={compact(traffic.visitors)}
          hint={`${count(traffic.activeVisitors)} đang hoạt động · ${count(traffic.signedInVisitors)} đã đăng nhập`}
          accent={0}
        />
        <StatTile
          label="Lượt tìm kiếm"
          value={compact(traffic.searches)}
          hint={`${(traffic.searches / Math.max(1, traffic.visitors)).toFixed(1)} lượt/người`}
          accent={1}
        />
        <StatTile
          label="Lượt bấm liên kết"
          value={compact(traffic.clicks)}
          hint={`Tỉ lệ bấm (CTR) ${percent(traffic.clickThroughRate)}`}
          accent={2}
        />
        <StatTile
          label="Độ trễ trung bình"
          value={millis(traffic.avgLatencyMs)}
          hint="Do máy chủ đo, giao diện báo lại"
        />
        <StatTile
          label="Trang đã crawl"
          value={compact(crawl.documents)}
          hint={`${count(crawl.distinctHosts)} tên miền`}
        />
        <StatTile
          label="Liên kết đã phát hiện"
          value={compact(crawl.totalOutlinks)}
          hint={`${compact(crawl.distinctLinkTargets)} đích phân biệt`}
        />
      </div>

      <ChartCard
        title="Lưu lượng 24 giờ qua"
        subtitle="Ba chuỗi cùng đơn vị (số đếm) nên chung một trục. Rê chuột hoặc dùng phím ←/→ để đọc từng giờ."
      >
        <TrendChart
          labels={traffic.hourly.map((point) => point.hour)}
          series={[
            { name: 'Phiên truy cập', values: traffic.hourly.map((point) => point.visitors) },
            { name: 'Lượt tìm kiếm', values: traffic.hourly.map((point) => point.searches) },
            { name: 'Lượt bấm', values: traffic.hourly.map((point) => point.clicks) }
          ]}
          emptyText="Chưa có hoạt động nào trong 24 giờ qua. Hãy thử tìm kiếm vài lượt rồi quay lại."
        />
      </ChartCard>

      <div className="grid gap-5 lg:grid-cols-2">
        <ChartCard
          title="Truy vấn phổ biến nhất"
          subtitle="Đã chuẩn hoá hoa/thường và khoảng trắng trước khi gộp."
        >
          <BarList
            rows={traffic.topQueries.map((item) => ({ label: item.label, value: item.count }))}
            emptyText="Chưa có truy vấn nào được ghi nhận."
          />
        </ChartCard>

        <ChartCard
          title="Liên kết người dùng truy cập"
          subtitle="Kèm thứ hạng trung bình lúc được bấm — hạng càng thấp, bộ xếp hạng càng đặt đúng chỗ."
        >
          <BarList
            rows={traffic.topLinks.map((item) => ({
              label: shortUrl(item.url),
              value: item.count,
              sub: item.position > 0 ? `hạng TB ${item.position.toFixed(1)}` : undefined,
              title: item.url
            }))}
            emptyText="Chưa có lượt bấm nào. Bấm vào một kết quả tìm kiếm rồi quay lại."
          />
        </ChartCard>

        <ChartCard
          title="Phân bố độ trễ truy vấn"
          subtitle="Các khoảng tăng theo cấp số nhân, vì phân bố độ trễ có đuôi rất dài — chia đều thì mọi thứ dồn vào cột đầu."
        >
          <ColumnChart
            data={traffic.latency.map((bucket) => ({
              label: bucket.label,
              value: bucket.count
            }))}
            emptyText="Chưa có phép đo độ trễ nào."
          />
        </ChartCard>

        <ChartCard
          title="Tên miền được bấm nhiều nhất"
          subtitle="Gộp từ bảng liên kết — một tên miền thường có nhiều URL."
        >
          <BarList
            rows={traffic.topHosts.map((item) => ({ label: item.label, value: item.count }))}
            emptyText="Chưa có lượt bấm nào."
          />
        </ChartCard>
      </div>

      {/* ---- 2. Máy tìm kiếm biết những gì ---- */}
      <SectionTitle icon={<DatabaseIcon className="h-4 w-4" />} text="Dữ liệu đã thu thập" />

      <div className="grid gap-5 lg:grid-cols-2">
        <ChartCard
          title="Trang crawl được theo ngày"
          subtitle={`14 ngày gần nhất. Ngày không crawl vẫn hiện, để trục thời gian không nói dối.`}
        >
          <ColumnChart
            data={crawl.crawledPerDay.map((day) => ({
              label: dayLabel(day.date),
              value: day.count
            }))}
            labelEvery={2}
            emptyText="Corpus chưa có mốc thời gian crawl nào."
          />
        </ChartCard>

        <ChartCard
          title="Ngôn ngữ của corpus"
          subtitle="Phần trăm số trang. Phần đuôi được gộp thành “khác” thay vì cấp thêm màu."
        >
          <ShareBar
            parts={foldTail(crawl.languages, 3)}
            emptyText="Chưa có trang nào trong chỉ mục."
          />
        </ChartCard>

        <ChartCard title="Tên miền có nhiều trang nhất trong chỉ mục">
          <BarList
            rows={crawl.topHosts.map((item) => ({ label: item.label, value: item.count }))}
            emptyText="Chưa có trang nào trong chỉ mục."
          />
        </ChartCard>

        <ChartCard title="Chỉ mục và corpus">
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2.5 text-[12.5px]">
            <Fact label="Tài liệu trong chỉ mục" value={count(index.documents)} />
            <Fact label="Số term phân biệt" value={count(index.terms)} />
            <Fact label="Kích thước tệp chỉ mục" value={bytes(index.sizeBytes)} />
            <Fact label="Tỉ lệ trúng cache" value={percent(index.cacheHitRate)} />
            <Fact label="Bộ chấm điểm" value={index.scorer} />
            <Fact
              label="Bloom Filter"
              value={index.bloomFilterBits > 0 ? `${compact(index.bloomFilterBits)} bit` : '—'}
            />
            <Fact label="Trang crawl cũ nhất" value={dateTime(crawl.oldestCrawledAt)} />
            <Fact label="Trang crawl mới nhất" value={dateTime(crawl.newestCrawledAt)} />
            <Fact label="Độ dài tài liệu trung bình" value={`${count(crawl.avgDocLength)} token`} />
            <Fact label="Trung vị" value={`${count(crawl.medianDocLength)} token`} />
          </dl>
        </ChartCard>
      </div>

      {/* ---- 3. Phân tích rút ra từ số liệu ---- */}
      <SectionTitle icon={<ChartIcon className="h-4 w-4" />} text="Phân tích" />

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Độ đa dạng truy vấn"
          value={percent(diversity, 0)}
          hint="Entropy Shannon chuẩn hoá trên bảng top. 0% = mọi người tìm cùng một thứ, 100% = tản mát đều."
        />
        <StatTile
          label="Tập trung ở top 3"
          value={percent(concentration, 0)}
          hint="Phần lưu lượng do 3 truy vấn lớn nhất chiếm. Đầu càng nặng, cache LRU càng hiệu quả."
        />
        <StatTile
          label="Truy vấn không kết quả"
          value={percent(traffic.zeroResultRate, 0)}
          hint={`${count(traffic.zeroResultSearches)} lượt. Cao = chỉ mục thiếu, không phải người dùng gõ sai.`}
        />
        <StatTile
          label="Phiên trung bình"
          value={`${traffic.avgSessionMinutes.toFixed(1)} phút`}
          hint="Từ sự kiện đầu tới sự kiện cuối của cùng một phiên."
        />
        <StatTile
          label="Độ phủ crawl"
          value={percent(coverage, 1)}
          hint="Số trang đã tải / số đích liên kết đã nhìn thấy. Thấp là bình thường khi maxPages có hạn."
        />
        <StatTile
          label="Trang nút cụt"
          value={percent(danglingShare, 1)}
          hint={`${count(crawl.danglingDocuments)} trang không có liên kết ra. PageRank phải xử lý riêng nhóm này.`}
        />
        <StatTile
          label="Liên kết trung bình mỗi trang"
          value={crawl.avgOutlinks.toFixed(1)}
          hint="Bậc ra trung bình của đồ thị web đã thu thập."
        />
        <StatTile
          label="Lượt bấm mỗi lượt tìm"
          value={traffic.clickThroughRate.toFixed(2)}
          hint="Dưới 1 là bình thường: nhiều truy vấn kết thúc mà không ai bấm gì."
        />
      </div>

      {/* ---- 4. Tài khoản ---- */}
      <SectionTitle icon={<UsersIcon className="h-4 w-4" />} text="Tài khoản" />

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatTile label="Tổng tài khoản" value={count(accounts.total)} />
        <StatTile
          label="Quản trị viên"
          value={count(accounts.admins)}
          hint={
            accounts.admins === 0
              ? 'Chưa có tài khoản ADMIN nào — hiện chỉ vào được bằng khoá API.'
              : undefined
          }
        />
        <StatTile
          label="Phiên đăng nhập đang mở"
          value={count(accounts.activeSessions)}
          hint="Token còn hiệu lực. Khác với số phiên theo dõi số liệu ở trên."
        />
        <StatTile
          label="Tài khoản bị vô hiệu hoá"
          value={count(accounts.disabled)}
          hint="Bị khoá nhưng dữ liệu vẫn giữ nguyên."
        />
      </div>

      <ChartCard
        title="Danh sách tài khoản"
        subtitle="Vai trò đọc trực tiếp từ máy chủ. Đây là câu trả lời cho “tài khoản nào là admin, tài khoản nào là người dùng thường”."
      >
        <AccountsTable credential={credential} />
      </ChartCard>

      <ChartCard
        title="Tài khoản tìm kiếm nhiều nhất"
        subtitle="Chỉ tên và số lượt — cố ý KHÔNG kèm truy vấn của từng người. Bảng này trả lời “ai dùng nhiều”, không trả lời “người này tìm gì”."
      >
        <BarList
          rows={traffic.topUsers.map((item) => ({ label: item.label, value: item.count }))}
          emptyText="Chưa có lượt tìm nào từ người dùng đã đăng nhập. Người dùng ẩn danh không được quy về tài khoản nào."
        />
      </ChartCard>

      {/* ---- 5. Ranh giới quyền ---- */}
      <SectionTitle icon={<ShieldCheckIcon className="h-4 w-4" />} text="Phân quyền truy cập" />
      <div className="rounded-2xl border border-line bg-surface p-4">
        <PermissionMatrix />
      </div>
    </div>
  )
}

function SectionTitle({ icon, text }: { icon: JSX.Element; text: string }): JSX.Element {
  return (
    <div className="mt-1 flex items-center gap-2 text-muted">
      {icon}
      <h2 className="text-[12px] font-semibold uppercase tracking-wide">{text}</h2>
      <div className="h-px flex-1 bg-line" />
    </div>
  )
}

function Fact({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div className="min-w-0">
      <dt className="truncate text-[11.5px] text-faint">{label}</dt>
      <dd className="truncate text-ink">{value}</dd>
    </div>
  )
}

function LoadingSkeleton(): JSX.Element {
  return (
    <div className="flex flex-col gap-5" aria-hidden="true">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="skeleton h-[86px] rounded-2xl" />
        ))}
      </div>
      <div className="skeleton h-64 rounded-2xl" />
      <div className="grid gap-5 lg:grid-cols-2">
        <div className="skeleton h-72 rounded-2xl" />
        <div className="skeleton h-72 rounded-2xl" />
      </div>
    </div>
  )
}

export default AdminPanel
