import { useTabStore } from '../store/tabStore'

/** Thanh tab kieu Chrome: tab active noi len, nut "x" de dong, nut "+" de mo tab moi. */
function TabBar(): JSX.Element {
  const tabs = useTabStore((s) => s.tabs)
  const activeTabId = useTabStore((s) => s.activeTabId)
  const switchTab = useTabStore((s) => s.switchTab)
  const closeTab = useTabStore((s) => s.closeTab)
  const newTab = useTabStore((s) => s.newTab)

  return (
    <div className="flex h-9 items-end gap-1 bg-gray-200 px-2 pt-1">
      {tabs.map((tab) => {
        const isActive = tab.id === activeTabId
        return (
          <div
            key={tab.id}
            onClick={() => switchTab(tab.id)}
            className={
              'flex h-8 max-w-[200px] min-w-[120px] cursor-pointer items-center gap-2 rounded-t-md px-3 text-sm ' +
              (isActive ? 'bg-white text-gray-900 shadow-sm' : 'bg-gray-300 text-gray-600 hover:bg-gray-100')
            }
          >
            <span className="flex-1 truncate">{tab.loading ? 'Đang tải...' : tab.title || tab.url}</span>
            <button
              onClick={(e) => {
                e.stopPropagation()
                closeTab(tab.id)
              }}
              className="rounded px-1 text-gray-400 hover:bg-gray-200 hover:text-gray-700"
              aria-label="Đóng tab"
            >
              ×
            </button>
          </div>
        )
      })}
      <button
        onClick={() => newTab()}
        className="mb-1 flex h-6 w-6 items-center justify-center rounded-full text-gray-500 hover:bg-gray-300"
        aria-label="Tab mới"
      >
        +
      </button>
    </div>
  )
}

export default TabBar
