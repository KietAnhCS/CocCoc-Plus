/**
 * TODO (PHASE 8): O nhap URL + nut bookmark. Enter -> browser.navigate(id, url).
 */
function AddressBar(): JSX.Element {
  return (
    <input
      className="flex-1 rounded-full border border-gray-300 px-3 py-1 text-sm text-gray-400"
      placeholder="AddressBar (PHASE 8) — nhap URL hoac tu khoa tim kiem"
      disabled
    />
  )
}

export default AddressBar
