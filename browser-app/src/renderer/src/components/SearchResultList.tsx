import { useEffect, useState } from 'react'
import { search, type SearchResponseDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'

const PAGE_SIZE = 10

/**
 * Hien thi title/url/snippet (co <mark>), dong dau "Khoảng N kết quả
 * (t giây)", phan trang, che do debug hien diem TF-IDF/PageRank tung
 * ket qua (rat huu ich khi demo cho giang vien).
 */
function SearchResultList(): JSX.Element | null {
  const query = useSearchViewStore((s) => s.query)
  const setQuery = useSearchViewStore((s) => s.setQuery)
  const navigate = useTabStore((s) => s.navigate)

  const [page, setPage] = useState(1)
  const [response, setResponse] = useState<SearchResponseDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [debugMode, setDebugMode] = useState(false)

  useEffect(() => {
    setPage(1)
  }, [query])

  useEffect(() => {
    if (!query) {
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    search(query, page, PAGE_SIZE)
      .then((res) => {
        if (!cancelled) {
          setResponse(res)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError('Không thể kết nối tới máy chủ tìm kiếm (http://localhost:8080). Hãy chắc chắn backend đang chạy.')
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [query, page])

  if (!query) {
    return null
  }

  const totalPages = response ? Math.max(1, Math.ceil(response.totalResults / PAGE_SIZE)) : 1

  return (
    <div className="mx-auto max-w-2xl px-4 py-6">
      <div className="mb-4 flex items-center justify-between">
        <p className="text-sm text-gray-500">
          {response &&
            `Khoảng ${response.totalResults} kết quả (${(response.timeTakenMs / 1000).toFixed(3)} giây)`}
        </p>
        <label className="flex items-center gap-1 text-xs text-gray-400">
          <input type="checkbox" checked={debugMode} onChange={(e) => setDebugMode(e.target.checked)} />
          Debug
        </label>
      </div>

      {loading && <p className="text-sm text-gray-400">Đang tìm kiếm...</p>}
      {error && <p className="text-sm text-red-500">{error}</p>}

      <ul className="flex flex-col gap-5">
        {response?.results.map((result) => (
          <li key={result.url}>
            <button
              onClick={() => {
                navigate(result.url)
                setQuery(null)
              }}
              className="text-left text-lg text-blue-700 hover:underline"
            >
              {result.title}
            </button>
            <div className="text-xs text-green-700">{result.url}</div>
            {/* eslint-disable-next-line react/no-danger -- snippet chua the <mark> do backend sinh (an toan, khong tu du lieu nguoi dung nhap) */}
            <p
              className="text-sm text-gray-700 [&_mark]:bg-yellow-200"
              dangerouslySetInnerHTML={{ __html: result.snippet }}
            />
            {debugMode && (
              <p className="mt-1 text-xs text-gray-400">
                score={result.score.toFixed(4)} · tfidf={result.tfidfScore.toFixed(4)} · pageRank=
                {result.pageRankScore.toFixed(6)}
              </p>
            )}
          </li>
        ))}
      </ul>

      {response && response.results.length === 0 && !loading && !error && (
        <p className="text-sm text-gray-400">Không tìm thấy kết quả nào.</p>
      )}

      {response && totalPages > 1 && (
        <div className="mt-6 flex items-center justify-center gap-3 text-sm">
          <button
            disabled={page <= 1}
            onClick={() => setPage((p) => p - 1)}
            className="text-blue-600 disabled:text-gray-300"
          >
            « Trước
          </button>
          <span className="text-gray-500">
            Trang {page}/{totalPages}
          </span>
          <button
            disabled={page >= totalPages}
            onClick={() => setPage((p) => p + 1)}
            className="text-blue-600 disabled:text-gray-300"
          >
            Sau »
          </button>
        </div>
      )}
    </div>
  )
}

export default SearchResultList
