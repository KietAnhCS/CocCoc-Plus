import { useEffect, useState, type JSX } from 'react'
import type { AdminCredential, ManagedAccount } from '../../lib/adminApi'
import { useDashboardStore } from '../../store/dashboardStore'
import { useSessionStore } from '../../store/sessionStore'
import { dateTime } from '../../lib/format'
import { AlertIcon, ShieldCheckIcon, SpinnerIcon, TrashIcon, UserIcon } from '../icons'

/**
 * Danh sách tài khoản + nâng/hạ vai trò.
 *
 * ĐÂY LÀ CHỖ CÂU HỎI "AI LÀ ADMIN, AI LÀ NGƯỜI DÙNG" CÓ CÂU TRẢ LỜI NHÌN THẤY
 * ĐƯỢC: một bảng liệt kê từng tài khoản kèm vai trò thật của nó, đọc từ máy chủ.
 *
 * BA THỨ ĐƯỢC NÓI THẲNG TRONG GIAO DIỆN, vì chúng là hệ quả người bấm cần biết
 * TRƯỚC khi bấm:
 *   1. đổi vai trò sẽ ĐÓNG mọi phiên của người đó — họ phải đăng nhập lại;
 *   2. không tự đổi vai trò của chính mình (máy chủ chặn, nút ở đây tắt sẵn);
 *   3. hạ người quản trị cuối cùng là tự khoá hệ thống — cảnh báo trước.
 *
 * Điểm 3 là *cảnh báo*, không phải *chặn*: máy chủ không cấm, và một giao diện
 * tự đặt thêm luật mà máy chủ không có sẽ lệch khỏi hành vi thật ngay lần đầu
 * ai đó dùng `curl`.
 *
 * Trạng thái nằm ở `dashboardStore` chứ không phải `useState` — cùng lý do đã
 * ghi ở store đó: effect khởi động không được `setState` ngay trong thân nó.
 */
function AccountsTable({ credential }: { credential: AdminCredential }): JSX.Element {
  const currentUser = useSessionStore((state) => state.user)
  const accounts = useDashboardStore((state) => state.accounts)
  const error = useDashboardStore((state) => state.accountsError)
  const pending = useDashboardStore((state) => state.pendingAccount)
  const loadAccounts = useDashboardStore((state) => state.loadAccounts)
  const setAccountRole = useDashboardStore((state) => state.setAccountRole)
  const removeAccount = useDashboardStore((state) => state.removeAccount)

  // Tài khoản đang chờ XÁC NHẬN xoá. Không dùng `window.confirm`: hộp thoại đó
  // do Electron vẽ ở tầng hệ điều hành, nằm ngoài giao diện và không theo giao
  // diện sáng/tối — và quan trọng hơn, nó không nói được HẬU QUẢ cụ thể của
  // đúng tài khoản này.
  const [confirming, setConfirming] = useState<string | null>(null)

  useEffect(() => {
    loadAccounts(credential)
  }, [credential, loadAccounts])

  if (accounts === null && error === null) {
    return <div className="skeleton h-32 rounded-xl" aria-hidden="true" />
  }

  const admins = (accounts ?? []).filter((account) => account.role === 'ADMIN').length

  return (
    <div>
      {error && (
        <div className="mb-3 flex items-start gap-2.5 rounded-xl border border-danger/25 bg-danger/5 px-3.5 py-2.5">
          <AlertIcon className="mt-0.5 h-4 w-4 shrink-0 text-danger" />
          <p className="text-[12.5px] leading-relaxed text-danger">{error}</p>
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] text-left text-[12.5px]">
          <thead>
            <tr className="border-b border-line text-[11px] uppercase tracking-wide text-faint">
              <th className="py-2 pr-3 font-medium">Tài khoản</th>
              <th className="w-28 py-2 font-medium">Vai trò</th>
              <th className="w-36 py-2 font-medium">Đăng nhập gần nhất</th>
              <th className="w-64 py-2 text-right font-medium">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {(accounts ?? []).map((account) => (
              <AccountRow
                key={account.username}
                account={account}
                isSelf={currentUser?.username.toLowerCase() === account.username.toLowerCase()}
                lastAdmin={account.role === 'ADMIN' && admins === 1}
                pending={pending === account.username}
                busy={pending !== null}
                confirming={confirming === account.username}
                onSwitch={() =>
                  setAccountRole(
                    credential,
                    account.username,
                    account.role === 'ADMIN' ? 'USER' : 'ADMIN'
                  )
                }
                onAskDelete={() => setConfirming(account.username)}
                onCancelDelete={() => setConfirming(null)}
                onConfirmDelete={() => {
                  setConfirming(null)
                  void removeAccount(credential, account.username)
                }}
              />
            ))}
          </tbody>
        </table>
      </div>

      <p className="mt-3 text-[11.5px] leading-relaxed text-faint">
        Đổi vai trò sẽ <b>đóng mọi phiên đăng nhập</b> của tài khoản đó — kể cả khi nâng quyền.
        Không làm vậy thì phiên cũ vẫn mang vai trò cũ, tức quyền bị đổi trên giấy nhưng còn hiệu
        lực thêm nhiều giờ.
      </p>
    </div>
  )
}

function AccountRow({
  account,
  isSelf,
  lastAdmin,
  pending,
  busy,
  confirming,
  onSwitch,
  onAskDelete,
  onCancelDelete,
  onConfirmDelete
}: {
  account: ManagedAccount
  isSelf: boolean
  lastAdmin: boolean
  pending: boolean
  busy: boolean
  confirming: boolean
  onSwitch: () => void
  onAskDelete: () => void
  onCancelDelete: () => void
  onConfirmDelete: () => void
}): JSX.Element {
  const isAdmin = account.role === 'ADMIN'

  return (
    <tr className="border-b border-line/60">
      <td className="py-2 pr-3">
        <span className="flex items-center gap-2">
          <span
            className={
              'flex h-6 w-6 shrink-0 items-center justify-center rounded-full ' +
              'text-[10px] font-bold text-white ' +
              (isAdmin
                ? 'bg-linear-to-br from-emerald-500 to-lime-400'
                : 'bg-linear-to-br from-teal-500 to-emerald-400')
            }
          >
            {account.username.slice(0, 2).toUpperCase()}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-ink">{account.username}</span>
            {isSelf && <span className="text-[11px] text-faint">chính bạn</span>}
            {!account.enabled && <span className="text-[11px] text-warn">đã bị vô hiệu hoá</span>}
          </span>
        </span>
      </td>
      <td className="py-2">
        {/* Vai trò có ICON kèm chữ, không chỉ có màu: màu một mình không đọc
            được với người mù màu, và đây là thông tin quan trọng nhất bảng. */}
        <span
          className={
            'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ' +
            (isAdmin ? 'bg-success/15 text-success' : 'bg-raised text-muted')
          }
        >
          {isAdmin ? (
            <ShieldCheckIcon className="h-3.5 w-3.5" />
          ) : (
            <UserIcon className="h-3.5 w-3.5" />
          )}
          {isAdmin ? 'Quản trị' : 'Người dùng'}
        </span>
      </td>
      <td className="py-2 text-faint">{dateTime(account.lastLoginAt)}</td>
      <td className="py-2 text-right">
        {confirming ? (
          // Xác nhận NGAY TRONG HÀNG, kèm tên tài khoản: người bấm nhầm hàng
          // vẫn còn một bước để nhận ra mình đang xoá ai.
          <span className="inline-flex items-center gap-2">
            <span className="text-[11.5px] text-danger">Xoá hẳn {account.username}?</span>
            <button
              onClick={onConfirmDelete}
              className="h-7 rounded-full bg-danger px-2.5 text-[11.5px] font-medium text-white
                         transition hover:brightness-110 focus-visible:outline-none
                         focus-visible:ring-2 focus-visible:ring-danger/50"
            >
              Xoá
            </button>
            <button
              onClick={onCancelDelete}
              className="h-7 rounded-full border border-line px-2.5 text-[11.5px] text-muted
                         transition hover:bg-raised hover:text-ink focus-visible:outline-none"
            >
              Huỷ
            </button>
          </span>
        ) : (
          <span className="inline-flex items-center gap-1.5">
            <button
              onClick={onSwitch}
              disabled={isSelf || busy}
              className="inline-flex h-7 items-center gap-1.5 rounded-full border border-line
                     px-2.5 text-[11.5px] text-muted transition
                     enabled:hover:bg-raised enabled:hover:text-ink
                     disabled:cursor-not-allowed disabled:opacity-45
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
              title={
                isSelf
                  ? 'Không thể tự đổi vai trò của chính mình — nếu người quản trị cuối cùng tự hạ quyền thì không ai nâng lại được.'
                  : lastAdmin
                    ? 'CẢNH BÁO: đây là quản trị viên duy nhất. Hạ vai trò thì không còn ai quản trị được bằng tài khoản (vẫn còn lối vào bằng khoá API).'
                    : `Đổi thành ${isAdmin ? 'Người dùng' : 'Quản trị'}. Mọi phiên đăng nhập của tài khoản này sẽ bị đóng.`
              }
            >
              {pending ? (
                <SpinnerIcon className="h-3.5 w-3.5" />
              ) : isAdmin ? (
                'Hạ xuống Người dùng'
              ) : (
                'Nâng lên Quản trị'
              )}
            </button>
            <button
              onClick={onAskDelete}
              disabled={isSelf || busy}
              className="flex h-7 w-7 items-center justify-center rounded-full border border-line
                         text-faint transition enabled:hover:border-danger/40
                         enabled:hover:bg-danger/10 enabled:hover:text-danger
                         disabled:cursor-not-allowed disabled:opacity-40
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-danger/40"
              aria-label={`Xoá tài khoản ${account.username}`}
              title={
                isSelf
                  ? 'Không thể tự xoá tài khoản đang đăng nhập.'
                  : 'Xoá hẳn tài khoản. Khác vô hiệu hoá: không hồi lại được, và tên được giải phóng cho người khác đăng ký.'
              }
            >
              <TrashIcon className="h-3.5 w-3.5" />
            </button>
          </span>
        )}
      </td>
    </tr>
  )
}

export default AccountsTable
