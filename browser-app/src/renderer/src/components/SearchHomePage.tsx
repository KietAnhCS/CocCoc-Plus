/**
 * TODO (PHASE 9): Trang chu mac dinh kieu Google/Coc Coc. O tim kiem lon,
 * go -> debounce 200ms -> goi /api/suggest -> AutocompleteDropdown. Enter
 * -> chuyen sang SearchResultList (goi searchApi.ts).
 */
function SearchHomePage(): JSX.Element {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4">
      <h1 className="text-3xl font-semibold text-blue-600">VnSearch</h1>
      <input
        className="w-full max-w-xl rounded-full border border-gray-300 px-5 py-3 shadow-sm"
        placeholder="Tim kiem... (PHASE 9 se noi vao backend that)"
        disabled
      />
      <p className="text-xs text-gray-400">PHASE 1 skeleton — chua noi API that</p>
    </div>
  )
}

export default SearchHomePage
