import { useState } from 'react'
import {
  useSidePanelStore,
  normalizeUrl,
  PANEL_WIDTH,
  type PanelKind
} from '../store/sidePanelStore'
import { useTabStore } from '../store/tabStore'
import { useBookmarkStore, type BookmarkNode } from '../store/bookmarkStore'
import { APP_GROUPS, AppTile, findApp } from '../lib/apps'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import { CloseIcon, DownloadIcon, PinIcon, SparkleIcon } from './icons'

/** Tiêu đề hiện trên đầu bảng, theo loại nội dung đang mở. */
const TITLES: Record<PanelKind, string> = {
  'add-site': 'Thêm trang web vào thanh bên',
  ai: 'Hỏi AI',
  downloads: 'Tải xuống',
  bookmarks: 'Tất cả dấu trang',
  app: 'Ứng dụng'
}

/**
 * Bảng trượt ra từ mép phải, neo cạnh cột biểu tượng.
 *
 * Bảng KHÔNG phủ lên trang: App.tsx báo bề ngang xuống main process để trang
 * ngoài co lại đúng chừng ấy (xem tabManager.setPanelWidth). Nhờ vậy vẫn đọc
 * được trang trong lúc thao tác trên bảng — cũng là cách thanh bên của Cốc
 * Cốc và Edge hoạt động.
 */
function SidePanel(): JSX.Element | null {
  const open = useSidePanelStore((s) => s.open)
  const pinned = useSidePanelStore((s) => s.pinned)
  const setPinned = useSidePanelStore((s) => s.setPinned)
  const closePanel = useSidePanelStore((s) => s.closePanel)

  if (!open) {
    return null
  }

  return (
    <section
      className="flex shrink-0 flex-col border-l border-line bg-surface"
      style={{ width: PANEL_WIDTH }}
      aria-label={TITLES[open]}
    >
      <header className="flex h-11 shrink-0 items-center gap-2 border-b border-line pl-4 pr-2">
        <h2 className="min-w-0 flex-1 truncate text-[13px] font-semibold text-ink">
          {TITLES[open]}
        </h2>
        <button
          onClick={() => setPinned(!pinned)}
          className={'icon-btn ' + (pinned ? 'bg-brand-soft text-brand hover:text-brand' : '')}
          aria-label={pinned ? 'Bỏ ghim bảng' : 'Ghim bảng'}
          title={pinned ? 'Bỏ ghim — bảng sẽ tự đóng khi mở liên kết' : 'Ghim — giữ bảng luôn mở'}
          aria-pressed={pinned}
        >
          <PinIcon className="h-[17px] w-[17px]" filled={pinned} />
        </button>
        <button onClick={closePanel} className="icon-btn" aria-label="Đóng bảng" title="Đóng">
          <CloseIcon className="h-[15px] w-[15px]" strokeWidth={2.2} />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {open === 'add-site' && <AddSiteBody />}
        {open === 'app' && <AppBody />}
        {open === 'downloads' && <DownloadsBody />}
        {open === 'bookmarks' && <BookmarksBody />}
        {open === 'ai' && <AskAiBody />}
      </div>
    </section>
  )
}

/** Ô nhập URL + lưới ứng dụng gợi ý, chia theo nhóm có nhãn. */
function AddSiteBody(): JSX.Element {
  const [url, setUrl] = useState('')
  const items = useSidePanelStore((s) => s.items)
  const addSite = useSidePanelStore((s) => s.addSite)
  const addApp = useSidePanelStore((s) => s.addApp)

  // Nút "Thêm" chỉ bật khi chuỗi đang gõ thật sự dùng được làm địa chỉ —
  // rẻ hơn nhiều so với để người dùng bấm rồi mới báo lỗi.
  const valid = normalizeUrl(url) !== null

  function submit(e: React.FormEvent): void {
    e.preventDefault()
    if (!valid) {
      return
    }
    addSite(url)
    setUrl('')
  }

  return (
    <div className="p-4">
      <form onSubmit={submit} className="flex items-center gap-2">
        <input
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          className="h-9 min-w-0 flex-1 rounded-lg border border-line bg-omni px-3 text-[13px]
                     text-ink placeholder:text-faint transition
                     focus:border-brand/50 focus:outline-none focus:ring-2 focus:ring-brand/15"
          placeholder="Nhập vào URL"
          spellCheck={false}
          aria-label="Địa chỉ trang web cần thêm"
        />
        <button
          type="submit"
          disabled={!valid}
          className="h-9 shrink-0 rounded-lg px-4 text-[13px] font-medium transition
                     enabled:bg-brand enabled:text-white enabled:hover:brightness-110
                     disabled:cursor-not-allowed disabled:bg-raised disabled:text-faint
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
        >
          Thêm
        </button>
      </form>

      {APP_GROUPS.map((group) => (
        <div key={group.label} className="mt-6">
          <h3 className="mb-3 text-[12px] font-semibold uppercase tracking-wide text-faint">
            {group.label}
          </h3>
          <div className="grid grid-cols-4 gap-2">
            {group.apps.map((app) => {
              const added = items.some((i) => i.id === app.id)
              return (
                <button
                  key={app.id}
                  onClick={() => addApp(app.id)}
                  disabled={added}
                  className="flex flex-col items-center gap-2 rounded-xl p-2 transition
                             enabled:hover:bg-raised disabled:opacity-45
                             focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
                  title={added ? `${app.name} đã có trên thanh bên` : `Thêm ${app.name}`}
                >
                  <AppTile app={app} size={44} />
                  <span className="w-full truncate text-center text-[11px] text-muted">
                    {app.name}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Nội dung khi bấm một ô đã ghim. Bảng đóng vai trò bệ phóng chứ không nhúng
 * trang vào trong: nhúng được thì phải tạo thêm một WebContentsView lồng
 * trong vùng bảng ở main process, nằm ngoài phạm vi phần giao diện này.
 */
function AppBody(): JSX.Element {
  const activeItemId = useSidePanelStore((s) => s.activeItemId)
  const items = useSidePanelStore((s) => s.items)
  const pinned = useSidePanelStore((s) => s.pinned)
  const closePanel = useSidePanelStore((s) => s.closePanel)
  const newTab = useTabStore((s) => s.newTab)

  const item = items.find((i) => i.id === activeItemId)
  if (!item) {
    return <EmptyState text="Ô này không còn trên thanh bên." />
  }

  const app = item.url ? undefined : findApp(item.id)
  const url = app?.url ?? item.url ?? ''
  const name = app?.name ?? item.name ?? hostOf(url)

  function open(): void {
    newTab(url)
    if (!pinned) {
      closePanel()
    }
  }

  return (
    <div className="flex flex-col items-center px-6 py-10 text-center">
      {app ? (
        <AppTile app={app} size={72} />
      ) : (
        <span
          className="flex h-[72px] w-[72px] items-center justify-center rounded-full text-2xl font-bold text-white"
          style={{ background: siteGradient(url) }}
        >
          {siteInitial(url)}
        </span>
      )}

      <h3 className="mt-4 text-[15px] font-semibold text-ink">{name}</h3>
      <p className="mt-1 break-all text-[12px] text-faint">{hostOf(url)}</p>

      <button
        onClick={open}
        className="mt-6 rounded-full bg-brand px-5 py-2 text-[13px] font-medium text-white
                   transition hover:brightness-110 focus-visible:outline-none
                   focus-visible:ring-2 focus-visible:ring-brand/60"
      >
        Mở trong thẻ mới
      </button>
    </div>
  )
}

/**
 * Danh sách dấu trang đã lưu. Ô lọc gọi thẳng `searchByPrefix` của
 * bookmarkStore — tức là chạy qua BookmarkTrie tự cài (lib/BookmarkTrie.ts)
 * chứ không phải `Array.filter`.
 */
function BookmarksBody(): JSX.Element {
  const [filter, setFilter] = useState('')
  const root = useBookmarkStore((s) => s.root)
  const searchByPrefix = useBookmarkStore((s) => s.searchByPrefix)
  const removeNode = useBookmarkStore((s) => s.removeNode)
  const pinned = useSidePanelStore((s) => s.pinned)
  const closePanel = useSidePanelStore((s) => s.closePanel)
  const navigate = useTabStore((s) => s.navigate)

  const all = flattenBookmarks(root)
  const shown = filter.trim() ? searchByPrefix(filter.trim()) : all

  function open(url: string): void {
    navigate(url)
    if (!pinned) {
      closePanel()
    }
  }

  return (
    <div className="p-3">
      <input
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        className="h-9 w-full rounded-lg border border-line bg-omni px-3 text-[13px] text-ink
                   placeholder:text-faint transition focus:border-brand/50 focus:outline-none
                   focus:ring-2 focus:ring-brand/15"
        placeholder="Lọc theo tiền tố tiêu đề…"
        spellCheck={false}
        aria-label="Lọc dấu trang"
      />

      {all.length === 0 ? (
        <EmptyState
          text="Chưa có dấu trang nào."
          hint="Bấm ngôi sao ở cuối ô địa chỉ (hoặc Ctrl+D) để lưu trang đang xem."
        />
      ) : shown.length === 0 ? (
        <p className="px-1 py-8 text-center text-[12px] text-faint">
          Không có dấu trang nào bắt đầu bằng “{filter.trim()}”.
        </p>
      ) : (
        <ul className="mt-2">
          {shown.map((bookmark) => (
            <li key={bookmark.id} className="group flex items-center gap-2 rounded-lg pr-1 hover:bg-raised">
              <button
                onClick={() => open(bookmark.url ?? '')}
                className="flex min-w-0 flex-1 items-center gap-2.5 px-2 py-2 text-left
                           focus-visible:outline-none"
                title={bookmark.url}
              >
                <span
                  className="flex h-5 w-5 shrink-0 items-center justify-center rounded-[6px]
                             text-[10px] font-bold text-white"
                  style={{ background: siteGradient(bookmark.url ?? '') }}
                >
                  {siteInitial(bookmark.url ?? '')}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] text-ink">{bookmark.title}</span>
                  <span className="block truncate text-[11px] text-faint">
                    {hostOf(bookmark.url ?? '')}
                  </span>
                </span>
              </button>
              <button
                onClick={() => removeNode(bookmark.id)}
                className="hidden h-6 w-6 shrink-0 items-center justify-center rounded-full text-faint
                           transition hover:bg-danger/15 hover:text-danger focus-visible:outline-none
                           group-hover:flex"
                aria-label={`Xoá dấu trang ${bookmark.title}`}
                title="Xoá dấu trang"
              >
                <CloseIcon className="h-3 w-3" strokeWidth={2.2} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Duyệt cây dấu trang, lấy ra các nút lá (nút có url). */
function flattenBookmarks(node: BookmarkNode): BookmarkNode[] {
  const out: BookmarkNode[] = []
  const walk = (current: BookmarkNode): void => {
    if (current.url) {
      out.push(current)
    }
    for (const child of current.children ?? []) {
      walk(child)
    }
  }
  walk(node)
  return out
}

function DownloadsBody(): JSX.Element {
  return (
    <EmptyState
      icon={<DownloadIcon className="h-7 w-7" />}
      text="Chưa có tệp nào được tải xuống."
      hint="Các tệp bạn tải về sẽ hiện ở đây."
    />
  )
}

function AskAiBody(): JSX.Element {
  return (
    <EmptyState
      icon={<SparkleIcon className="h-7 w-7" />}
      text="Chưa nối với mô hình ngôn ngữ nào."
      hint="Khung này dành sẵn cho phần hỏi đáp; hiện VnSearch chỉ chạy tìm kiếm theo từ khoá."
    />
  )
}

function EmptyState({
  icon,
  text,
  hint
}: {
  icon?: JSX.Element
  text: string
  hint?: string
}): JSX.Element {
  return (
    <div className="flex flex-col items-center px-8 py-14 text-center">
      {icon && (
        <span className="flex h-14 w-14 items-center justify-center rounded-full bg-raised text-faint">
          {icon}
        </span>
      )}
      <p className="mt-4 text-[13px] text-muted">{text}</p>
      {hint && <p className="mt-1.5 text-[12px] leading-relaxed text-faint">{hint}</p>}
    </div>
  )
}

export default SidePanel
