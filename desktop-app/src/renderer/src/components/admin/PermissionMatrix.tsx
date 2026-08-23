import type { JSX } from 'react'
import { useAdminCredential } from '../../store/adminStore'
import { useSessionStore } from '../../store/sessionStore'

/**
 * Bảng PHÂN QUYỀN: endpoint nào mở cho ai.
 *
 * VÌ SAO MỘT BẢNG NHƯ THẾ NÀY NẰM TRONG SẢN PHẨM, KHÔNG PHẢI TRONG TÀI LIỆU.
 * Luật phân quyền thật nằm trong `SecurityConfig.java`. Bảng này là bản chép
 * lại của nó cho người đọc — và một bản chép lại thì luôn có nguy cơ lệch khỏi
 * bản gốc. Đổi lại là điều mà tài liệu không làm được: người vận hành nhìn
 * thấy ranh giới quyền ngay tại nơi họ đang đứng, cùng lúc với việc thấy vai
 * trò hiện tại của chính mình.
 *
 * Hàng cuối cùng — "vai trò của bạn" — là phần khiến bảng này khác một hình
 * chụp tài liệu: nó đọc trạng thái thật của phiên đang chạy.
 */

interface Rule {
  method: string
  path: string
  /** Chưa đăng nhập. */
  guest: boolean
  /** Đã đăng nhập, vai trò USER. */
  user: boolean
  /** Vai trò ADMIN — dù đến từ tài khoản hay từ khoá X-API-Key. */
  admin: boolean
  note: string
}

const RULES: Rule[] = [
  {
    method: 'GET',
    path: '/api/search',
    guest: true,
    user: true,
    admin: true,
    note: 'Tìm kiếm — chức năng chính, KHÔNG đòi đăng nhập'
  },
  {
    method: 'GET',
    path: '/api/suggest · /api/images · /api/feed',
    guest: true,
    user: true,
    admin: true,
    note: 'Gợi ý, ảnh, dòng tin'
  },
  {
    method: 'GET',
    path: '/api/health',
    guest: true,
    user: true,
    admin: true,
    note: 'Sức khoẻ — Docker phải gọi được mà không có gì cả'
  },
  {
    method: 'POST',
    path: '/api/events',
    guest: true,
    user: true,
    admin: true,
    note: 'GHI số liệu: đóng lại thì không còn số liệu nào để đọc'
  },
  {
    method: 'POST',
    path: '/api/auth/register · login',
    guest: true,
    user: true,
    admin: true,
    note: 'Cửa vào — người chưa có tài khoản phải gõ được'
  },
  {
    method: 'GET',
    path: '/api/auth/me · logout',
    guest: false,
    user: true,
    admin: true,
    note: 'Chỉ cần ĐÃ đăng nhập, không phân biệt vai trò'
  },
  {
    method: 'POST',
    path: '/api/auth/password',
    guest: false,
    user: true,
    admin: true,
    note: 'Đổi mật khẩu. Vẫn phải nhập mật khẩu hiện tại'
  },
  {
    method: 'POST',
    path: '/api/auth/logout-all',
    guest: false,
    user: true,
    admin: true,
    note: 'Đóng mọi phiên, kể cả phiên đang dùng'
  },
  {
    method: 'GET',
    path: '/api/admin/analytics',
    guest: false,
    user: false,
    admin: true,
    note: 'ĐỌC số liệu — phơi bày người dùng đang tìm gì'
  },
  {
    method: 'GET',
    path: '/api/admin/users',
    guest: false,
    user: false,
    admin: true,
    note: 'Danh sách tài khoản (không kèm hash mật khẩu)'
  },
  {
    method: 'POST',
    path: '/api/admin/users/{tên}/role',
    guest: false,
    user: false,
    admin: true,
    note: 'Nâng/hạ vai trò. Không tự hạ quyền chính mình'
  },
  {
    method: 'POST',
    path: '/api/admin/users/{tên}/disable · enable',
    guest: false,
    user: false,
    admin: true,
    note: 'Khoá/mở tài khoản mà vẫn giữ dữ liệu'
  },
  {
    method: 'DELETE',
    path: '/api/admin/users/{tên}',
    guest: false,
    user: false,
    admin: true,
    note: 'Xoá hẳn. Không hồi lại được, không tự xoá chính mình'
  },
  {
    method: 'GET',
    path: '/api/admin/stats',
    guest: false,
    user: false,
    admin: true,
    note: 'Chi tiết vận hành của chỉ mục'
  },
  {
    method: 'POST',
    path: '/api/admin/crawl',
    guest: false,
    user: false,
    admin: true,
    note: 'Khiến máy chủ tải URL tuỳ ý — endpoint rủi ro nhất'
  },
  {
    method: 'POST',
    path: '/api/admin/reindex',
    guest: false,
    user: false,
    admin: true,
    note: 'Lập lại chỉ mục, tốn tài nguyên'
  }
]

/**
 * Dấu cho một ô.
 *
 * `deniedCode` nói rõ máy chủ trả mã nào, vì hai mã có nghĩa khác nhau:
 * 401 = "tôi không biết anh là ai" (chưa đăng nhập);
 * 403 = "tôi biết anh là ai, và anh không đủ quyền".
 */
function Mark({ allowed, deniedCode }: { allowed: boolean; deniedCode: 401 | 403 }): JSX.Element {
  return allowed ? (
    <span className="text-success" title="Được phép">
      ✓
    </span>
  ) : (
    <span className="text-faint" title={`Bị từ chối (${deniedCode})`}>
      ✕
    </span>
  )
}

function PermissionMatrix(): JSX.Element {
  const user = useSessionStore((state) => state.user)
  const credential = useAdminCredential()

  // Cột ứng với vai trò hiện tại được tô sáng — bảng này nói về HỆ THỐNG, còn
  // cột tô nói về BẠN, và hai thứ đó cần phân biệt được bằng mắt.
  const currentColumn = credential ? 'admin' : user ? 'user' : 'guest'
  const columnClass = (column: string): string =>
    column === currentColumn ? 'bg-brand-soft/60' : ''

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[520px] text-left text-[12px]">
        <thead>
          <tr className="border-b border-line text-[11px] uppercase tracking-wide text-faint">
            <th className="py-2 pr-3 font-medium">Endpoint</th>
            <th className={'w-20 py-2 text-center font-medium ' + columnClass('guest')}>Khách</th>
            <th className={'w-24 py-2 text-center font-medium ' + columnClass('user')}>
              Người dùng
            </th>
            <th className={'w-20 py-2 text-center font-medium ' + columnClass('admin')}>Admin</th>
            <th className="py-2 pl-3 font-medium">Vì sao</th>
          </tr>
        </thead>
        <tbody>
          {RULES.map((rule) => (
            <tr key={rule.method + rule.path} className="border-b border-line/60">
              <td className="py-1.5 pr-3">
                <span className="mr-1.5 rounded bg-raised px-1.5 py-0.5 font-mono text-[10.5px] text-muted">
                  {rule.method}
                </span>
                <span className="text-ink">{rule.path}</span>
              </td>
              <td className={'py-1.5 text-center ' + columnClass('guest')}>
                <Mark allowed={rule.guest} deniedCode={401} />
              </td>
              <td className={'py-1.5 text-center ' + columnClass('user')}>
                <Mark allowed={rule.user} deniedCode={403} />
              </td>
              <td className={'py-1.5 text-center ' + columnClass('admin')}>
                <Mark allowed={rule.admin} deniedCode={403} />
              </td>
              <td className="py-1.5 pl-3 text-faint">{rule.note}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <p className="mt-3 text-[11.5px] leading-relaxed text-faint">
        Cột được tô là vai trò của bạn lúc này:{' '}
        <span className="font-medium text-ink">
          {credential
            ? credential.kind === 'session'
              ? `ADMIN — tài khoản ${user?.username}`
              : 'ADMIN — khoá X-API-Key, không có tài khoản nào đứng sau'
            : user
              ? `NGƯỜI DÙNG — ${user.username}`
              : 'KHÁCH — chưa đăng nhập'}
        </span>
        .
      </p>
      <p className="mt-1.5 text-[11.5px] leading-relaxed text-faint">
        Luật thật nằm ở <code>SecurityConfig</code> phía máy chủ; bảng này chỉ là bản chép lại cho
        người đọc. Ẩn hay hiện nút ở giao diện không thay đổi được gì — một lời gọi{' '}
        <code>curl</code> không mang bằng chứng quyền vẫn nhận 401.
      </p>
    </div>
  )
}

export default PermissionMatrix
