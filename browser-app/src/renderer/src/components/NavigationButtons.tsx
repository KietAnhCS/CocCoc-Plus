import { useTabStore, HOME_URL } from '../store/tabStore'
import { useSearchViewStore } from '../store/searchViewStore'
import { ArrowLeftIcon, ArrowRightIcon, HomeIcon, ReloadIcon } from './icons'

/**
 * Cụm back / forward / reload / home. Trạng thái bật-tắt của back/forward
 * đọc từ historyStore.ts (Stack tự cài) chứ không hỏi Electron, nên hai nút
 * này mờ đi đúng lúc ngay cả khi tab đang ở trang chủ nội bộ.
 */
function NavigationButtons(): JSX.Element {
  const goBack = useTabStore((s) => s.goBack)
  const goForward = useTabStore((s) => s.goForward)
  const reload = useTabStore((s) => s.reload)
  const navigate = useTabStore((s) => s.navigate)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const canGoBack = useTabStore((s) => s.canGoBack())
  const canGoForward = useTabStore((s) => s.canGoForward())

  return (
    <div className="flex shrink-0 items-center gap-0.5">
      <button
        onClick={() => goBack()}
        disabled={!canGoBack}
        className="icon-btn"
        aria-label="Quay lại"
        title="Quay lại (Alt+←)"
      >
        <ArrowLeftIcon className="h-[18px] w-[18px]" />
      </button>
      <button
        onClick={() => goForward()}
        disabled={!canGoForward}
        className="icon-btn"
        aria-label="Tiến tới"
        title="Tiến tới (Alt+→)"
      >
        <ArrowRightIcon className="h-[18px] w-[18px]" />
      </button>
      <button onClick={() => reload()} className="icon-btn" aria-label="Tải lại" title="Tải lại (F5)">
        <ReloadIcon className="h-[18px] w-[18px]" />
      </button>
      <button
        onClick={() => {
          setQuery(null)
          navigate(HOME_URL)
        }}
        className="icon-btn"
        aria-label="Trang chủ"
        title="Trang chủ VnSearch"
      >
        <HomeIcon className="h-[18px] w-[18px]" />
      </button>
    </div>
  )
}

export default NavigationButtons
