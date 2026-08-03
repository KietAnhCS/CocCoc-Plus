import { useLayoutEffect, useRef, useState } from 'react'
import Popover from './Popover'
import { useBookmarkStore, type BookmarkNode } from '../store/bookmarkStore'
import { useSidePanelStore } from '../store/sidePanelStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import { ChevronsRightIcon, FolderIcon, GridAppsIcon, StarIcon } from './icons'

/** Chừa sẵn chỗ cho nút ">>" khi tính xem bao nhiêu dấu trang lọt vào thanh. */
const OVERFLOW_BUTTON_WIDTH = 34

/**
 * Thanh dấu trang, nằm ngay dưới thanh công cụ và chìm hơn một bậc.
 *
 * Số mục hiển thị được tính theo bề ngang thật: một hàng bản sao vô hình
 * (`measure`) giữ đủ MỌI mục để đo, còn hàng nhìn thấy chỉ vẽ những mục lọt
 * vào. Phải đo trên bản sao chứ không đo trực tiếp hàng đang hiện, vì cắt
 * bớt mục sẽ làm phép đo lần sau sai đi và hai bên giằng nhau vô tận.
 */
function BookmarksBar(): JSX.Element {
  const root = useBookmarkStore((s) => s.root)
  const openPanel = useSidePanelStore((s) => s.openPanel)
  const panelOpen = useSidePanelStore((s) => s.open)

  const nodes = root.children ?? []
  const [visibleCount, setVisibleCount] = useState(nodes.length)
  const [overflowOpen, setOverflowOpen] = useState(false)

  const trackRef = useRef<HTMLDivElement>(null)
  const measureRef = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    const track = trackRef.current
    const measure = measureRef.current
    if (!track || !measure) {
      return undefined
    }

    const recompute = (): void => {
      const available = track.clientWidth
      const children = Array.from(measure.children) as HTMLElement[]

      let used = 0
      let fit = 0
      for (const child of children) {
        used += child.offsetWidth
        if (used > available) {
          break
        }
        fit++
      }

      // Còn mục bị cắt thì nút ">>" cũng chiếm chỗ -> có thể phải bỏ thêm một mục.
      if (fit < children.length) {
        let usedWithButton = OVERFLOW_BUTTON_WIDTH
        fit = 0
        for (const child of children) {
          usedWithButton += child.offsetWidth
          if (usedWithButton > available) {
            break
          }
          fit++
        }
      }
      setVisibleCount(fit)
    }

    recompute()
    const observer = new ResizeObserver(recompute)
    observer.observe(track)
    return () => observer.disconnect()
  }, [nodes])

  const visible = nodes.slice(0, visibleCount)
  const hidden = nodes.slice(visibleCount)

  return (
    <div className="flex h-[34px] shrink-0 items-center gap-1 border-b border-line bg-bookmarks px-2">
      <button
        className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-muted
                   transition-colors hover:bg-raised hover:text-ink focus-visible:outline-none
                   focus-visible:ring-2 focus-visible:ring-brand/60"
        aria-label="Ứng dụng"
        title="Ứng dụng"
      >
        <GridAppsIcon className="h-[15px] w-[15px]" />
      </button>

      <div className="mx-0.5 h-4 w-px shrink-0 bg-line" />

      {/* Hàng nhìn thấy. `relative` để hàng đo vô hình bám vào đây. */}
      <div ref={trackRef} className="relative flex min-w-0 flex-1 items-center overflow-hidden">
        {nodes.length === 0 ? (
          <span className="truncate pl-1 text-[12px] text-faint">
            Bấm ngôi sao ở ô địa chỉ để lưu trang vào đây.
          </span>
        ) : (
          visible.map((node) => <BookmarkChip key={node.id} node={node} />)
        )}

        {/* Bản sao dùng để đo: nằm ngoài luồng bố cục nên không đẩy gì cả. */}
        <div
          ref={measureRef}
          aria-hidden="true"
          className="pointer-events-none absolute left-0 top-0 flex opacity-0"
        >
          {nodes.map((node) => (
            <BookmarkChip key={node.id} node={node} measuring />
          ))}
        </div>
      </div>

      {hidden.length > 0 && (
        <div className="relative shrink-0">
          <button
            onClick={() => setOverflowOpen((v) => !v)}
            className={
              'flex h-7 w-7 items-center justify-center rounded-lg text-muted transition-colors ' +
              'hover:bg-raised hover:text-ink focus-visible:outline-none focus-visible:ring-2 ' +
              'focus-visible:ring-brand/60 ' + (overflowOpen ? 'bg-raised text-ink' : '')
            }
            aria-label={`Xem thêm ${hidden.length} dấu trang`}
            title={`Còn ${hidden.length} dấu trang nữa`}
            aria-expanded={overflowOpen}
          >
            <ChevronsRightIcon className="h-[15px] w-[15px]" />
          </button>
          <Popover
            open={overflowOpen}
            onClose={() => setOverflowOpen(false)}
            width={250}
            label="Dấu trang khác"
          >
            {hidden.map((node) => (
              <BookmarkRow key={node.id} node={node} onDone={() => setOverflowOpen(false)} />
            ))}
          </Popover>
        </div>
      )}

      <div className="mx-0.5 h-4 w-px shrink-0 bg-line" />

      <button
        onClick={() => openPanel('bookmarks')}
        className={
          'flex h-7 shrink-0 items-center gap-1.5 rounded-lg px-2 text-[12px] text-muted ' +
          'transition-colors hover:bg-raised hover:text-ink focus-visible:outline-none ' +
          'focus-visible:ring-2 focus-visible:ring-brand/60 ' +
          (panelOpen === 'bookmarks' ? 'bg-raised text-ink' : '')
        }
        title="Mở toàn bộ dấu trang"
      >
        <StarIcon className="h-[15px] w-[15px]" />
        Tất cả dấu trang
      </button>
    </div>
  )
}

/**
 * Một mục trên thanh. `measuring` chỉ dùng cho bản sao vô hình — bản sao
 * không được bắt chuột và không cần trạng thái rê chuột.
 */
function BookmarkChip({ node, measuring }: { node: BookmarkNode; measuring?: boolean }): JSX.Element {
  const navigate = useTabStore((s) => s.navigate)
  const [folderOpen, setFolderOpen] = useState(false)
  const isFolder = !node.url

  const chip = (
    <button
      onClick={() => (isFolder ? setFolderOpen((v) => !v) : navigate(node.url as string))}
      tabIndex={measuring ? -1 : undefined}
      className="flex h-7 max-w-[150px] shrink-0 items-center gap-1.5 rounded-lg px-2 text-[12px]
                 text-muted transition-colors hover:bg-raised hover:text-ink
                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
      title={isFolder ? node.title : `${node.title}\n${node.url}`}
    >
      {isFolder ? (
        <FolderIcon className="h-[15px] w-[15px] shrink-0 text-amber-500" />
      ) : (
        <span
          className="flex h-[15px] w-[15px] shrink-0 items-center justify-center rounded-[4px]
                     text-[8px] font-bold text-white"
          style={{ background: siteGradient(node.url as string) }}
        >
          {siteInitial(node.url as string)}
        </span>
      )}
      <span className="truncate">{node.title}</span>
    </button>
  )

  if (measuring || !isFolder) {
    return chip
  }

  return (
    <div className="relative shrink-0">
      {chip}
      <Popover
        open={folderOpen}
        onClose={() => setFolderOpen(false)}
        align="left"
        width={250}
        label={node.title}
      >
        {(node.children ?? []).length === 0 ? (
          <p className="px-2.5 py-2 text-[12px] text-faint">Thư mục trống.</p>
        ) : (
          (node.children ?? []).map((child) => (
            <BookmarkRow key={child.id} node={child} onDone={() => setFolderOpen(false)} />
          ))
        )}
      </Popover>
    </div>
  )
}

/** Một hàng trong bảng thả xuống (phần tràn hoặc nội dung thư mục). */
function BookmarkRow({ node, onDone }: { node: BookmarkNode; onDone: () => void }): JSX.Element {
  const navigate = useTabStore((s) => s.navigate)
  const isFolder = !node.url

  return (
    <button
      onClick={() => {
        if (!isFolder) {
          navigate(node.url as string)
        }
        onDone()
      }}
      disabled={isFolder}
      className="menu-row"
      title={isFolder ? node.title : node.url}
    >
      {isFolder ? (
        <FolderIcon className="h-4 w-4 shrink-0 text-amber-500" />
      ) : (
        <span
          className="flex h-4 w-4 shrink-0 items-center justify-center rounded-[5px]
                     text-[9px] font-bold text-white"
          style={{ background: siteGradient(node.url as string) }}
        >
          {siteInitial(node.url as string)}
        </span>
      )}
      <span className="min-w-0 flex-1 truncate">{node.title}</span>
      {!isFolder && (
        <span className="shrink-0 text-[11px] text-faint">{hostOf(node.url as string)}</span>
      )}
    </button>
  )
}

export default BookmarksBar
