/**
 * Dropdown goi y ben duoi o tim kiem. Dieu huong bang phim mui ten va
 * chon bang Enter duoc xu ly o component cha (SearchHomePage) qua
 * highlightedIndex/onSelect - o day chi la phan hien thi thuan tuy va bat
 * su kien chuot.
 */
interface Props {
  suggestions: string[]
  highlightedIndex: number
  onSelect: (value: string) => void
  onHighlight: (index: number) => void
}

function AutocompleteDropdown({ suggestions, highlightedIndex, onSelect, onHighlight }: Props): JSX.Element | null {
  if (suggestions.length === 0) {
    return null
  }

  return (
    <ul className="absolute left-0 right-0 top-full z-10 mt-1 max-h-72 overflow-auto rounded-lg border border-gray-200 bg-white shadow-lg">
      {suggestions.map((suggestion, index) => (
        <li
          key={suggestion}
          onMouseEnter={() => onHighlight(index)}
          // onMouseDown (khong phai onClick) de chay TRUOC su kien blur cua input,
          // tranh dropdown bien mat truoc khi kip nhan duoc lua chon.
          onMouseDown={(e) => {
            e.preventDefault()
            onSelect(suggestion)
          }}
          className={
            'cursor-pointer px-4 py-2 text-sm ' +
            (index === highlightedIndex ? 'bg-blue-50 text-blue-700' : 'text-gray-700 hover:bg-gray-50')
          }
        >
          {suggestion}
        </li>
      ))}
    </ul>
  )
}

export default AutocompleteDropdown
