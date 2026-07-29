import { useTabStore, HOME_URL } from '../store/tabStore'

/** Icon back/forward/reload/home. Back/forward doc trang thai tu historyStore.ts (Stack tu cai). */
function NavigationButtons(): JSX.Element {
  const goBack = useTabStore((s) => s.goBack)
  const goForward = useTabStore((s) => s.goForward)
  const reload = useTabStore((s) => s.reload)
  const navigate = useTabStore((s) => s.navigate)
  // Doc truc tiep tu historyStore (qua canGoBack/canGoForward) de re-render dung khi stack thay doi.
  const canGoBack = useTabStore((s) => s.canGoBack())
  const canGoForward = useTabStore((s) => s.canGoForward())

  const buttonClass = (enabled: boolean): string =>
    'flex h-7 w-7 items-center justify-center rounded-full text-lg ' +
    (enabled ? 'text-gray-600 hover:bg-gray-200' : 'text-gray-300 cursor-default')

  return (
    <div className="flex items-center gap-0.5">
      <button onClick={() => goBack()} disabled={!canGoBack} className={buttonClass(canGoBack)} aria-label="Quay lại">
        ←
      </button>
      <button
        onClick={() => goForward()}
        disabled={!canGoForward}
        className={buttonClass(canGoForward)}
        aria-label="Tiến tới"
      >
        →
      </button>
      <button onClick={() => reload()} className={buttonClass(true)} aria-label="Tải lại">
        ⟳
      </button>
      <button onClick={() => navigate(HOME_URL)} className={buttonClass(true)} aria-label="Trang chủ">
        ⌂
      </button>
    </div>
  )
}

export default NavigationButtons
