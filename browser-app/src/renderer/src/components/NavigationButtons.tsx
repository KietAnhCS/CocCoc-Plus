import type { JSX } from 'react'
import { useTabStore, HOME_URL } from '../store/tabStore'
import { useSearchViewStore } from '../store/searchViewStore'
import { ArrowLeftIcon, ArrowRightIcon, HomeIcon, ReloadIcon } from './icons'

function NavigationButtons(): JSX.Element {
  const goBack = useTabStore((state) => state.goBack)
  const goForward = useTabStore((state) => state.goForward)
  const reload = useTabStore((state) => state.reload)
  const navigate = useTabStore((state) => state.navigate)
  const canGoBack = useTabStore((state) => state.canGoBack())
  const canGoForward = useTabStore((state) => state.canGoForward())
  const clearSearch = useSearchViewStore((state) => state.clear)

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
      <button
        onClick={() => reload()}
        className="icon-btn"
        aria-label="Tải lại"
        title="Tải lại (F5)"
      >
        <ReloadIcon className="h-[18px] w-[18px]" />
      </button>
      <button
        onClick={() => {
          clearSearch()
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
