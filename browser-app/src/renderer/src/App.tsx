import TabBar from './components/TabBar'
import NavigationButtons from './components/NavigationButtons'
import AddressBar from './components/AddressBar'
import SearchHomePage from './components/SearchHomePage'

/**
 * PHASE 1: chi la khung giao dien de kiem chung "npm run dev" mo duoc
 * cua so va render duoc React. Logic tab/dieu huong/tim kiem that se lam
 * o PHASE 8-9.
 */
function App(): JSX.Element {
  return (
    <div className="flex h-screen w-screen flex-col bg-gray-100 text-gray-900">
      <TabBar />
      <div className="flex items-center gap-2 border-b border-gray-200 bg-white px-2 py-1">
        <NavigationButtons />
        <AddressBar />
      </div>
      <main className="flex-1 overflow-auto">
        <SearchHomePage />
      </main>
    </div>
  )
}

export default App
