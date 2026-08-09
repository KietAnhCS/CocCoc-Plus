import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type JSX } from 'react'
import { searchImages, type ImageResultDto } from '../lib/searchApi'
import { useSearchViewStore } from '../store/searchViewStore'
import { useTabStore } from '../store/tabStore'
import { hostOf, siteGradient, siteInitial } from '../lib/site'
import {
  AlertIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CloseIcon,
  GlobeIcon,
  SearchIcon,
  SpinnerIcon
} from './icons'

/**
 * Số ảnh mỗi lô.
 *
 * <h3>Vì sao 24 chứ không phải 8 như bản lưới cột cũ</h3>
 *
 * Bố cục hàng ngang căn đều (xem {@link buildRows}) làm việc trên CẢ danh sách,
 * nên hàng cuối của mỗi lô gần như luôn là hàng LẺ — chưa đủ ảnh để căn cho
 * kín chiều rộng. Khi lô sau về, hàng lẻ đó được căn lại và các ảnh trong nó
 * đổi kích thước một chút.
 *
 * Đó là hành vi đúng, và Google Images cũng vậy. Nhưng tần suất thì do kích
 * thước lô quyết định: lô 8 ảnh với hàng ~5 ảnh nghĩa là cứ mỗi lần cuộn lại
 * có một lần căn lại; lô 24 ảnh thì thưa hơn ba lần, trong khi vẫn về đủ nhanh
 * để lô đầu hiện gần như tức thì.
 *
 * Đổi một chỗ duy nhất ở đây là đổi cả hành vi.
 */
const BATCH_SIZE = 24

/**
 * Nạp trước khi ô canh còn cách đáy 400px.
 *
 * Bằng 0 thì người dùng phải cuộn CHẠM đáy mới thấy vòng quay, rồi đứng chờ.
 * Nạp sớm 400px khiến ảnh mới thường đã sẵn sàng trước khi họ cuộn tới đó, nên
 * cảm giác là một dải liên tục chứ không phải từng nhịp dừng.
 */
const PREFETCH_MARGIN = '400px'

/**
 * Chiều cao hàng mong muốn, tính bằng px.
 *
 * Đây là chiều cao TRƯỚC khi căn: thuật toán gom ảnh vào hàng cho tới khi căn
 * kín chiều rộng, nên chiều cao thật của mỗi hàng luôn hơi thấp hơn con số này.
 * Google Images dùng khoảng 180px ở màn hình để bàn; giữ nguyên mốc đó.
 */
const TARGET_ROW_HEIGHT = 180

/** Khoảng cách giữa hai ảnh, cả chiều ngang lẫn dọc. */
const GAP = 8

/**
 * Tỉ lệ khung dùng tạm khi CHƯA biết kích thước thật.
 *
 * <h3>Vì sao phải có giá trị tạm</h3>
 *
 * Bố cục căn đều cần tỉ lệ rộng/cao của TỪNG ảnh để tính bề rộng của nó trong
 * hàng. Máy chủ chỉ có `declaredWidth`/`declaredHeight` — tức thuộc tính
 * `width`/`height` viết trong HTML — và phần lớn trang thật không khai báo,
 * nên hai trường đó thường là `-1`.
 *
 * Google không gặp chuyện này vì họ TẢI ảnh về nên biết kích thước thật. Hệ
 * thống này mặc định không tải ảnh (xem `ImageFound`), nên đường duy nhất còn
 * lại là đo `naturalWidth`/`naturalHeight` ngay trong trình duyệt, sau khi thẻ
 * `<img>` tải xong — tức là SAU khi đã phải quyết định chỗ đặt nó.
 *
 * 4:3 là tỉ lệ hay gặp nhất ở ảnh báo chí, nên chọn nó làm giá trị tạm khiến
 * bước hiệu chỉnh về sau nhỏ nhất.
 */
const FALLBACK_RATIO = 4 / 3

/**
 * Chặn tỉ lệ trong khoảng hợp lý.
 *
 * Ảnh banner 20:1 hay ảnh cột dọc 1:8 tồn tại thật trên trang thật. Không chặn
 * thì một tấm banner chiếm trọn một hàng và cao 30px — một vạch màu, không phải
 * một kết quả tìm kiếm. Chặn lại khiến ảnh bị cắt bớt (`object-cover`), đổi lại
 * là lưới giữ được nhịp.
 */
const MIN_RATIO = 0.4
const MAX_RATIO = 3.0

interface Props {
  onMeta?: (meta: ImageMeta) => void
}

export interface ImageMeta {
  /**
   * Truy vấn mà số liệu này thuộc về.
   *
   * Nhờ trường này mà component cha SUY RA được trạng thái "đang tải" thay vì
   * phải được BÁO: `meta.query !== query` nghĩa là số liệu còn của truy vấn
   * cũ, tức lô đầu chưa về.
   *
   * Vì sao phải làm vậy: báo bằng một lời gọi `onMeta({loading:true})` ngay
   * trong effect chính là gọi setState của cha một cách ĐỒNG BỘ, khiến React
   * render thêm một lượt ngay lập tức (cascading render) — ESLint chặn đúng ở
   * chỗ đó. Suy ra từ dữ liệu sẵn có thì không cần lượt render nào.
   */
  query: string
  total: number
  timeTakenMs: number
}

/** Một ảnh đã được xếp chỗ trong hàng: biết luôn bề rộng vẽ ra. */
interface PlacedImage {
  image: ImageResultDto
  /** Chỉ số trong danh sách phẳng — dùng để chọn và điều hướng trái/phải. */
  index: number
  width: number
}

interface Row {
  items: PlacedImage[]
  height: number
}

/**
 * Xếp danh sách ảnh thành các hàng CĂN ĐỀU — thuật toán của Google Images,
 * Flickr và Cốc Cốc.
 *
 * <h3>Bài toán</h3>
 *
 * Cho một dãy ảnh có tỉ lệ khung khác nhau và một khung rộng `width`, hãy chia
 * chúng thành các hàng sao cho:
 *
 *   1. thứ tự được giữ NGUYÊN — trái sang phải, rồi xuống hàng;
 *   2. mỗi hàng lấp KÍN chiều rộng, không thừa mép phải;
 *   3. chiều cao mỗi hàng gần `target` nhất có thể.
 *
 * <h3>Cách giải</h3>
 *
 * Với một hàng gồm n ảnh có tổng tỉ lệ `S = Σ(rộng/cao)`, nếu đặt cả hàng ở
 * chiều cao `h` thì bề rộng chiếm chỗ là `S·h + gap·(n−1)`. Ép nó bằng `width`
 * cho ra chiều cao DUY NHẤT khiến hàng vừa khít:
 *
 * <pre>
 *     h = (width − gap·(n−1)) / S
 * </pre>
 *
 * Nên chỉ cần gom ảnh vào hàng và tính lại `h` sau mỗi ảnh. Càng thêm ảnh, `S`
 * càng lớn, `h` càng nhỏ. Khi `h` tụt xuống dưới `target`, hàng đã đủ chật —
 * chốt nó lại ở đúng chiều cao đó và mở hàng mới.
 *
 * Đây là thuật toán THAM LAM một lượt, O(n). Có lời giải quy hoạch động cho ra
 * độ lệch chiều cao đều hơn, nhưng nó cần biết TOÀN BỘ danh sách trước — mà
 * danh sách ở đây dài thêm mỗi lần cuộn. Tham lam thì thêm ảnh vào cuối không
 * đụng tới hàng nào đã chốt, đúng thứ cuộn vô hạn cần.
 *
 * <h3>Hàng cuối được đối xử KHÁC</h3>
 *
 * Hàng cuối gần như không bao giờ đủ ảnh để căn kín. Ép nó căn kín thì 2 tấm
 * ảnh sẽ bị phóng to lấp cả màn hình — trông như lỗi. Nên hàng cuối giữ nguyên
 * `target` (và chỉ thu nhỏ nếu như vậy sẽ tràn), chấp nhận mép phải bỏ trống.
 *
 * <p><b>Hệ quả cần biết:</b> khi lô sau về, hàng cuối cũ hết là hàng cuối và
 * được căn lại — ảnh trong nó đổi kích thước một chút. Chỉ hàng đó thôi; mọi
 * hàng phía trên đã chốt thì đứng yên. Xem {@link BATCH_SIZE} về việc chọn kích
 * thước lô để chuyện này thưa đi.
 */
function buildRows(
  images: ImageResultDto[],
  ratioOf: (image: ImageResultDto) => number,
  width: number,
  target: number,
  gap: number
): Row[] {
  const rows: Row[] = []
  if (width <= 0) {
    return rows
  }

  let current: ImageResultDto[] = []
  let sumRatio = 0

  // Chỉ số của ảnh đầu tiên trong hàng đang gom. Đếm dồn thay vì cộng lại số
  // ảnh của mọi hàng đã chốt ở mỗi lần chốt hàng — cách kia là O(n·số hàng), và
  // hàm này chạy lại ở MỖI lần đổi bề rộng cửa sổ lẫn mỗi lần một ảnh tải xong
  // và báo về tỉ lệ thật.
  let placed = 0

  const flush = (height: number): void => {
    rows.push({
      items: current.map((image, i) => ({
        image,
        index: placed + i,
        width: ratioOf(image) * height
      })),
      height
    })
    placed += current.length
    current = []
    sumRatio = 0
  }

  for (const image of images) {
    const ratio = ratioOf(image)

    // Chiều cao hàng NẾU chốt ngay bây giờ, chưa nhận ảnh này. Tính trước khi
    // đẩy vào `current` vì sau đó không lấy lại được.
    const heightWithout =
      current.length > 0 ? (width - gap * (current.length - 1)) / sumRatio : Infinity

    current.push(image)
    sumRatio += ratio

    const heightWith = (width - gap * (current.length - 1)) / sumRatio
    if (heightWith > target) {
      continue // hàng còn rộng, nhận thêm ảnh nữa
    }

    // Tới đây hàng đã đủ chật. Nhưng "đủ chật" không có nghĩa là "chốt luôn":
    // ảnh vừa nhận có thể rất rộng và kéo chiều cao xuống thấp hơn hẳn mong
    // muốn. Khi đó chốt hàng KHÔNG kèm ảnh đó cho ra chiều cao cao hơn `target`
    // một chút — và gần `target` hơn.
    //
    // Đo trên 400 ảnh có tỉ lệ ngẫu nhiên trong [0.4, 3.0], target 180px, lấy
    // độ lệch TRUNG BÌNH so với target (bỏ hàng cuối vì nó tính theo luật
    // khác):
    //
    //     bề rộng khung | không so sánh | có so sánh
    //     --------------+---------------+------------
    //       800px       |     29.5px    |   21.1px    (−28%)
    //      1120px       |     24.6px    |   14.4px    (−41%)
    //      1600px       |     19.1px    |   10.6px    (−45%)
    //
    // Lưu ý khi đọc số: BIÊN ĐỘ min–max của chiều cao lại RỘNG ra, vì bản có so
    // sánh cho phép hàng cao hơn target chứ không chỉ thấp hơn. Nhìn biên độ sẽ
    // tưởng là tệ đi. Thứ mắt người thấy là các hàng có bám quanh một chiều cao
    // chung hay không — tức độ lệch trung bình, và nó giảm rõ rệt.
    if (heightWithout - target < target - heightWith) {
      // Chốt hàng KHÔNG có ảnh này, rồi mở hàng mới bắt đầu bằng chính nó.
      current.pop()
      sumRatio -= ratio
      flush(heightWithout)
      current.push(image)
      sumRatio = ratio
    } else {
      flush(heightWith)
    }
  }

  if (current.length > 0) {
    const available = width - gap * (current.length - 1)
    // `Math.min`: hàng cuối KHÔNG được kéo giãn, nhưng vẫn phải co lại nếu ở
    // chiều cao mong muốn nó đã tràn mép phải.
    flush(Math.min(target, available / sumRatio))
  }

  return rows
}

/**
 * Bề rộng thật của một phần tử, theo dõi bằng `ResizeObserver`.
 *
 * Không dùng `window.innerWidth`: khung chứa lưới nằm trong một bố cục có lề,
 * có thanh bên và có thể đổi bề rộng mà cửa sổ thì không (mở/đóng thanh bên,
 * chia đôi màn hình). Đo đúng phần tử là cách duy nhất không đoán.
 *
 * `useLayoutEffect` chứ không `useEffect`: đo và đặt state phải xong TRƯỚC khi
 * trình duyệt vẽ, nếu không lượt vẽ đầu tiên sẽ là lưới rỗng rồi mới nhảy sang
 * lưới thật — người dùng thấy một nháy trắng.
 */
function useContainerWidth(): [React.RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)

  useLayoutEffect(() => {
    const element = ref.current
    if (!element) {
      return undefined
    }
    setWidth(element.clientWidth)

    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width ?? 0
      // Làm tròn: `contentRect` trả về số thực và có thể rung ±0.01px khi có
      // thanh cuộn xuất hiện/biến mất, khiến state đổi liên tục và lưới tính
      // lại vô ích ở mỗi khung hình.
      setWidth((prev) => (Math.abs(prev - next) < 1 ? prev : next))
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  return [ref, width]
}

/**
 * Tab "Hình ảnh" — lưới căn đều theo hàng, cuộn tới đâu tải tới đó.
 *
 * <h3>Ảnh bám chặt thứ hạng của trang</h3>
 *
 * Không có mô hình xếp hạng riêng cho ảnh. Máy chủ xếp hạng TRANG bằng đúng
 * TF-IDF/BM25/PageRank của tab "Tất cả", rồi lấy ảnh theo thứ tự ấy. Nên nếu
 * truy vấn khớp 3 trang thì ảnh chỉ đến từ 3 trang đó, và ảnh của trang liên
 * quan nhất hiện trước.
 *
 * <h3>Vì sao bố cục là HÀNG chứ không phải CỘT</h3>
 *
 * Bản trước dùng `columns-4` của CSS — bố cục nhiều cột. Nó có hai khuyết điểm
 * mà không cách nào sửa được trong khuôn khổ đó:
 *
 *   1. <b>Thứ tự đọc sai.</b> CSS multi-column rót đầy cột 1 rồi mới sang cột
 *      2, nên đọc theo hàng ngang từ trên xuống là 1, 3, 5, 7 — trong khi thứ
 *      hạng của máy tìm kiếm là 1, 2, 3, 4. Ảnh liên quan thứ nhì nằm ở đầu
 *      cột hai, cách xa mắt người đọc.
 *   2. <b>Ảnh nhảy chỗ khi tải thêm.</b> Trình duyệt CÂN LẠI toàn bộ các cột
 *      mỗi khi nội dung dài ra, nên một lô mới về là mọi ảnh đang xem đều xê
 *      dịch — kể cả ảnh ở đầu trang.
 *
 * Bố cục căn đều theo hàng ({@link buildRows}) sửa cả hai: thứ tự trái→phải rồi
 * xuống dòng đúng bằng thứ hạng, và lô mới chỉ nối vào đáy.
 *
 * <h3>Cuộn vô hạn, không phải nút "Xem thêm"</h3>
 *
 * `IntersectionObserver` theo dõi một ô canh vô hình ở cuối lưới. Ô canh lọt
 * vào tầm nhìn nghĩa là người dùng đã cuộn gần tới đáy — lúc đó mới gọi lô
 * tiếp theo.
 *
 * Vì sao dùng nó thay vì nghe sự kiện `scroll`: sự kiện scroll bắn hàng chục
 * lần mỗi giây và mỗi lần đều phải đo lại vị trí phần tử, tức là ép trình
 * duyệt tính lại bố cục ngay giữa lúc đang cuộn — đúng thứ gây giật.
 * `IntersectionObserver` chạy ngoài luồng chính và chỉ báo khi trạng thái
 * thật sự đổi.
 *
 * <h3>Ảnh hỏng là trạng thái được thiết kế sẵn</h3>
 *
 * Hệ thống mặc định không tải nội dung ảnh, nên thẻ `<img>` trỏ thẳng về máy
 * chủ gốc và một phần sẽ trả 403 (chống hotlink) hoặc 404. `onError` chuyển ô
 * đó thành khối giữ chỗ có văn bản thay thế, vẫn bấm được để tới trang. Xoá
 * hẳn ô đi thì lưới nhảy chỗ ngay trước mắt người đang đọc.
 */
function ImageResultGrid({ onMeta }: Props): JSX.Element {
  const query = useSearchViewStore((state) => state.query)
  const clearSearch = useSearchViewStore((state) => state.clear)
  const navigate = useTabStore((state) => state.navigate)

  const [images, setImages] = useState<ImageResultDto[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [broken, setBroken] = useState<Set<string>>(new Set())

  /**
   * Tỉ lệ khung ĐO ĐƯỢC, khoá theo địa chỉ ảnh.
   *
   * Chỉ chứa ảnh đã tải xong; ảnh chưa có mặt ở đây thì dùng tỉ lệ khai báo,
   * không có nữa thì {@link FALLBACK_RATIO}. Xem chú thích ở hằng số đó về việc
   * vì sao phải đi đường vòng này.
   */
  const [measured, setMeasured] = useState<Map<string, number>>(new Map())

  /** Chỉ số ảnh đang mở khung chi tiết; `null` là không mở. */
  const [selected, setSelected] = useState<number | null>(null)

  const [gridRef, width] = useContainerWidth()
  const sentinelRef = useRef<HTMLDivElement>(null)

  // Chốt chống gọi chồng. `loading` là state nên trong cùng một lượt render nó
  // vẫn mang giá trị cũ — IntersectionObserver có thể bắn hai lần trước khi
  // React kịp render lại, và thế là hai request cho cùng một lô. Ref cập nhật
  // ĐỒNG BỘ nên nó chặn được ca đó.
  const inFlight = useRef(false)

  const loadNext = useCallback(async () => {
    if (!query || inFlight.current || !hasMore) {
      return
    }
    inFlight.current = true
    setLoading(true)

    const next = page + 1
    try {
      const response = await searchImages(query, next, BATCH_SIZE)
      setImages((prev) => {
        // Khử trùng phòng thủ. Máy chủ đã cắt lát trên một danh sách ổn định
        // nên về lý thuyết không có trùng — nhưng nếu một phiên crawl chèn ảnh
        // mới vào giữa hai lần cuộn thì ranh giới lát có thể xê dịch. React sẽ
        // cảnh báo trùng `key` và hiện ảnh hai lần; rẻ hơn nhiều là chặn ở đây.
        const seen = new Set(prev.map((image) => image.imageUrl))
        return [...prev, ...response.results.filter((image) => !seen.has(image.imageUrl))]
      })
      setHasMore(response.hasMore)
      setPage(next)
      setError(null)
      onMeta?.({ query, total: response.totalResults, timeTakenMs: response.timeTakenMs })
    } catch {
      setError(
        'Không thể kết nối tới máy chủ tìm kiếm (http://localhost:8080). Hãy chắc chắn backend đang chạy.'
      )
      setHasMore(false)
      onMeta?.({ query, total: 0, timeTakenMs: 0 })
    } finally {
      inFlight.current = false
      setLoading(false)
    }
    // onMeta không nằm trong deps: nó là hàm mới ở mỗi lần render cha, đưa vào
    // sẽ dựng lại observer liên tục.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, page, hasMore])

  // MỘT cơ chế cho MỌI lô, kể cả lô đầu.
  //
  // Không có effect riêng để nạp lô đầu, và đó là chủ ý kép:
  //
  //   1. Gọi loadNext() thẳng trong effect nghĩa là gọi setState đồng bộ
  //      trong effect — React render thêm một lượt ngay lập tức, và ESLint
  //      chặn đúng ở đó.
  //   2. Quan trọng hơn: ô canh nằm ở đáy một lưới RỖNG thì vốn đã nằm trong
  //      tầm nhìn, nên observer tự bắn ngay khi gắn. Lô đầu và lô thứ mười đi
  //      qua đúng một đường mã — không có nhánh "lần đầu" nào để hỏng riêng.
  //
  // Điều kiện bắt buộc: ô canh phải LUÔN được render, kể cả lúc đang hiện
  // khung xương hay trạng thái rỗng. Trả về sớm mà bỏ nó đi thì observer
  // không có gì để theo dõi và lưới đứng im mãi mãi.
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || !hasMore) {
      return undefined
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          void loadNext()
        }
      },
      { rootMargin: PREFETCH_MARGIN }
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [loadNext, hasMore])

  const open = useCallback(
    (image: ImageResultDto): void => {
      navigate(image.pageUrl)
      clearSearch()
    },
    [navigate, clearSearch]
  )

  /**
   * Tỉ lệ khung dùng để xếp chỗ, theo thứ tự ưu tiên: đo được → khai báo →
   * mặc định. Luôn bị chặn trong [{@link MIN_RATIO}, {@link MAX_RATIO}].
   */
  const ratioOf = useCallback(
    (image: ImageResultDto): number => {
      const fromMeasure = measured.get(image.imageUrl)
      const raw =
        fromMeasure ??
        (image.width > 0 && image.height > 0 ? image.width / image.height : FALLBACK_RATIO)
      return Math.min(MAX_RATIO, Math.max(MIN_RATIO, raw))
    },
    [measured]
  )

  const rows = useMemo(
    () => buildRows(images, ratioOf, width, TARGET_ROW_HEIGHT, GAP),
    [images, ratioOf, width]
  )

  /**
   * Ghi lại tỉ lệ thật khi ảnh tải xong.
   *
   * Chỉ đặt state khi tỉ lệ LỆCH ĐÁNG KỂ so với giá trị đang dùng. Không có
   * phép so sánh đó thì mỗi ảnh tải xong là một lần đặt state, tức là với lô 24
   * ảnh sẽ có 24 lần tính lại toàn bộ lưới — trong khi phần lớn ảnh có tỉ lệ
   * gần đúng bằng giá trị tạm và chẳng làm bố cục đổi gì.
   */
  const onImageLoad = useCallback((image: ImageResultDto, element: HTMLImageElement): void => {
    const { naturalWidth, naturalHeight } = element
    if (naturalWidth <= 0 || naturalHeight <= 0) {
      return
    }
    const actual = naturalWidth / naturalHeight
    setMeasured((prev) => {
      const current = prev.get(image.imageUrl)
      if (current !== undefined && Math.abs(current - actual) < 0.01) {
        return prev
      }
      const next = new Map(prev)
      next.set(image.imageUrl, actual)
      return next
    })
  }, [])

  // Điều hướng bằng bàn phím khi khung chi tiết đang mở — đúng thứ Google
  // Images và Cốc Cốc đều có, và là cách duy nhất xem nhanh một dãy ảnh mà
  // không phải đưa chuột về đúng từng ô nhỏ.
  useEffect(() => {
    if (selected === null) {
      return undefined
    }
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        setSelected(null)
      } else if (event.key === 'ArrowRight') {
        event.preventDefault()
        setSelected((prev) => (prev === null ? null : Math.min(images.length - 1, prev + 1)))
      } else if (event.key === 'ArrowLeft') {
        event.preventDefault()
        setSelected((prev) => (prev === null ? null : Math.max(0, prev - 1)))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selected, images.length])

  // Không có effect nào reset `selected` khi truy vấn đổi, và đó là chủ ý:
  // SearchResultList dựng component này với `key={query}`, nên đổi truy vấn là
  // React THÁO BỎ rồi dựng lại từ đầu — mọi state, kể cả ảnh đang chọn, trở về
  // giá trị khởi tạo. Thêm một effect gọi `setSelected(null)` ở đây vừa thừa,
  // vừa là setState đồng bộ trong effect mà ESLint chặn đúng chỗ đó.

  const empty = images.length === 0
  const firstLoad = empty && loading
  const nothingFound = empty && !loading && !hasMore && !error

  return (
    <>
      {empty && error && (
        <div className="flex items-start gap-3 rounded-2xl border border-danger/25 bg-danger/5 px-4 py-3.5">
          <AlertIcon className="mt-0.5 h-5 w-5 shrink-0 text-danger" />
          <p className="text-sm text-danger">{error}</p>
        </div>
      )}

      {nothingFound && <EmptyState />}

      <div ref={gridRef}>
        {/* Khung xương chỉ hiện khi CHƯA có ảnh nào. Nó cũng giữ chỗ cho lần đo
            bề rộng đầu tiên: `width` là 0 ở lượt render đầu, nên `rows` rỗng và
            không có gì để vẽ cho tới khi ResizeObserver báo về. */}
        {(firstLoad || (empty && width === 0 && !error)) && <GridSkeleton />}

        {rows.map((row, rowIndex) => {
          const holdsSelected =
            selected !== null &&
            row.items.length > 0 &&
            selected >= row.items[0].index &&
            selected <= row.items[row.items.length - 1].index

          return (
            <div key={rowIndex}>
              <div className="flex" style={{ gap: GAP, marginBottom: GAP }}>
                {row.items.map((placed) => (
                  <ImageCell
                    key={placed.image.imageUrl}
                    placed={placed}
                    rowHeight={row.height}
                    active={selected === placed.index}
                    broken={broken.has(placed.image.imageUrl)}
                    onSelect={() =>
                      setSelected((prev) => (prev === placed.index ? null : placed.index))
                    }
                    onLoad={onImageLoad}
                    onBroken={() =>
                      setBroken((prev) => {
                        const next = new Set(prev)
                        next.add(placed.image.imageUrl)
                        return next
                      })
                    }
                  />
                ))}
              </div>

              {/* Khung chi tiết bung ra NGAY DƯỚI hàng chứa ảnh được chọn —
                  không phải một panel dính bên phải.

                  Vì sao đặt ở đây thay vì cạnh lưới: panel bên phải chiếm vĩnh
                  viễn một phần bề rộng, nên mở nó ra là lưới bị bóp lại và TOÀN
                  BỘ hàng phải căn lại — mọi ảnh đổi kích thước cùng lúc, đúng
                  thứ bố cục hàng sinh ra để tránh. Bung theo chiều dọc thì chỉ
                  đẩy phần bên dưới xuống, còn hàng đang xem đứng nguyên. */}
              {holdsSelected && selected !== null && (
                <ImagePreview
                  image={images[selected]}
                  position={selected - row.items[0].index}
                  rowLength={row.items.length}
                  broken={broken.has(images[selected].imageUrl)}
                  hasPrev={selected > 0}
                  hasNext={selected < images.length - 1}
                  onPrev={() => setSelected((prev) => (prev === null ? null : prev - 1))}
                  onNext={() => setSelected((prev) => (prev === null ? null : prev + 1))}
                  onClose={() => setSelected(null)}
                  onOpen={() => open(images[selected])}
                />
              )}
            </div>
          )
        })}
      </div>

      {/* Ô canh vô hình: lọt vào tầm nhìn = đã cuộn gần tới đáy = nạp lô sau. */}
      <div ref={sentinelRef} aria-hidden className="h-1" />

      {loading && images.length > 0 && (
        <div className="flex items-center justify-center gap-2 py-6 text-[13px] text-muted">
          <SpinnerIcon className="h-4 w-4" />
          Đang tải thêm ảnh…
        </div>
      )}

      {!hasMore && !loading && images.length > 0 && (
        <p className="py-6 text-center text-[12px] text-faint">
          Đã hiện hết {images.length.toLocaleString('vi-VN')} ảnh tìm được.
        </p>
      )}

      {/* Lỗi ở một lô SAU: giữ nguyên ảnh đã có, chỉ báo ở cuối. Xoá cả lưới
          vì một lô hỏng là mất luôn thứ người dùng đang xem. */}
      {error && images.length > 0 && (
        <div className="flex items-center justify-center gap-2 py-4 text-[12px] text-danger">
          <AlertIcon className="h-4 w-4" />
          Không tải được thêm ảnh.
        </div>
      )}
    </>
  )
}

interface CellProps {
  placed: PlacedImage
  rowHeight: number
  active: boolean
  broken: boolean
  onSelect: () => void
  onLoad: (image: ImageResultDto, element: HTMLImageElement) => void
  onBroken: () => void
}

/**
 * Một ô ảnh trong hàng.
 *
 * Bề rộng và chiều cao do {@link buildRows} tính ra và truyền vào dạng style
 * nội tuyến — KHÔNG phải lớp Tailwind. Đây là điều bắt buộc: mỗi ô có một bề
 * rộng riêng, tính bằng số thực, đổi theo bề rộng cửa sổ. Không có tập lớp hữu
 * hạn nào biểu diễn được điều đó.
 *
 * Chú thích (tiêu đề trang + host) nằm ĐÈ lên ảnh và chỉ hiện khi rê chuột,
 * đúng cách Google Images và Cốc Cốc làm. Bản trước đặt chú thích BÊN DƯỚI mỗi
 * ảnh, và điều đó không tương thích với bố cục căn đều: chiều cao chữ không co
 * giãn theo chiều cao ảnh, nên hai ô cùng hàng sẽ lệch đáy nhau tuỳ tiêu đề dài
 * ngắn.
 */
function ImageCell({
  placed,
  rowHeight,
  active,
  broken,
  onSelect,
  onLoad,
  onBroken
}: CellProps): JSX.Element {
  const { image } = placed

  return (
    <button
      onClick={onSelect}
      title={`${image.pageTitle}\n${image.pageUrl}`}
      aria-pressed={active}
      style={{ width: placed.width, height: rowHeight }}
      className={`group relative shrink-0 overflow-hidden rounded-lg bg-raised text-left
                  transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50
                  ${active ? 'ring-2 ring-brand' : 'hover:brightness-95'}`}
    >
      {broken ? (
        <div className="flex h-full w-full flex-col items-center justify-center gap-1.5 px-2 text-center">
          <GlobeIcon className="h-5 w-5 text-faint" />
          <span className="line-clamp-2 text-[11px] leading-snug text-faint">
            {image.altText || 'Không tải được ảnh'}
          </span>
        </div>
      ) : (
        <img
          src={image.imageUrl}
          alt={image.altText}
          // Trình duyệt tự hoãn tải ảnh ngoài tầm nhìn. Cộng với cuộn vô hạn:
          // lô về từ máy chủ nhưng ảnh chỉ thật sự được tải khi sắp hiện ra.
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          onLoad={(event) => onLoad(image, event.currentTarget)}
          onError={onBroken}
          // `object-cover` chứ không `contain`: bố cục đã cấp cho ô đúng tỉ lệ
          // của ảnh, nên phần bị cắt chỉ xuất hiện ở hai ca — ảnh chưa đo được
          // tỉ lệ (đang dùng 4:3 tạm) và ảnh có tỉ lệ bị chặn ở MIN/MAX_RATIO.
          // `contain` ở hai ca đó để lại viền trống, xấu hơn hẳn.
          className="h-full w-full object-cover"
        />
      )}

      {image.missingAlt && (
        <span
          title="Ảnh này không có văn bản thay thế (alt) — trình đọc màn hình sẽ bỏ qua"
          className="absolute right-1.5 top-1.5 rounded-full bg-danger/90 px-1.5 py-0.5
                     text-[9px] font-semibold text-white shadow-sm"
        >
          thiếu alt
        </span>
      )}

      {/* Chú thích hiện khi rê chuột. `pointer-events-none` để nó không nuốt
          cú bấm vốn thuộc về nút bao ngoài. */}
      <span
        className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center gap-1.5
                   bg-gradient-to-t from-black/80 to-transparent px-2 pb-1.5 pt-6
                   opacity-0 transition group-hover:opacity-100"
      >
        <span
          className="flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full text-[7px] font-bold text-white"
          style={{ background: siteGradient(image.pageUrl) }}
        >
          {siteInitial(image.pageUrl)}
        </span>
        <span className="truncate text-[10px] text-white/90">
          {image.host || hostOf(image.pageUrl)}
        </span>
      </span>
    </button>
  )
}

interface PreviewProps {
  image: ImageResultDto
  /** Vị trí của ảnh được chọn TRONG hàng — dùng để đặt mũi tên chỉ lên. */
  position: number
  rowLength: number
  broken: boolean
  hasPrev: boolean
  hasNext: boolean
  onPrev: () => void
  onNext: () => void
  onClose: () => void
  onOpen: () => void
}

/**
 * Khung chi tiết, bung ra dưới hàng chứa ảnh được chọn.
 *
 * <h3>Mũi tên chỉ lên</h3>
 *
 * Không có nó thì khung này là một khối trôi nổi, và với hàng 6 ảnh thì không
 * cách nào biết nó đang nói về ảnh nào. Mũi tên đặt ở giữa ô được chọn — vị
 * trí tính theo `position/rowLength` chứ không theo toạ độ pixel, nên nó tự
 * đúng khi cửa sổ đổi bề rộng mà không cần đo lại gì.
 *
 * Đây là điểm khác biệt so với panel bên phải của Google: ở đó ảnh được chọn
 * và panel nằm cạnh nhau nên không cần chỉ dẫn gì thêm.
 */
function ImagePreview({
  image,
  position,
  rowLength,
  broken,
  hasPrev,
  hasNext,
  onPrev,
  onNext,
  onClose,
  onOpen
}: PreviewProps): JSX.Element {
  const ref = useRef<HTMLDivElement>(null)

  // Cuộn khung vào tầm nhìn khi nó mở ra hoặc khi đổi sang ảnh khác. Bấm một
  // ảnh ở hàng cuối màn hình thì khung bung ra hoàn toàn NGOÀI tầm nhìn —
  // người dùng thấy lưới nhích xuống rồi không có gì xảy ra nữa.
  //
  // `block: 'nearest'`: chỉ cuộn nếu THẬT SỰ cần. Dùng 'center' thì mỗi lần bấm
  // một ảnh đang hiện rõ ràng vẫn bị giật một nhịp cuộn vô cớ.
  useEffect(() => {
    ref.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [image.imageUrl])

  const host = image.host || hostOf(image.pageUrl)
  const hasSize = image.width > 0 && image.height > 0

  return (
    <div
      ref={ref}
      className="animate-fade-up relative mb-2 rounded-xl border border-line bg-raised"
      style={{ marginTop: -GAP + 2 }}
    >
      {/* Mũi tên chỉ lên ô đang chọn: một hình vuông xoay 45°, che nửa dưới
          bằng chính nền của khung. */}
      <span
        aria-hidden
        className="absolute -top-[7px] h-3 w-3 rotate-45 border-l border-t border-line bg-raised"
        style={{ left: `calc(${((position + 0.5) / rowLength) * 100}% - 6px)` }}
      />

      <div className="flex flex-col gap-4 p-4 sm:flex-row">
        <div className="flex shrink-0 items-center justify-center sm:w-2/5">
          {broken ? (
            <div className="flex h-40 w-full flex-col items-center justify-center gap-2 rounded-lg bg-base text-center">
              <GlobeIcon className="h-7 w-7 text-faint" />
              <span className="px-4 text-[12px] text-faint">
                Máy chủ gốc không cho tải ảnh này (403/404)
              </span>
            </div>
          ) : (
            <img
              src={image.imageUrl}
              alt={image.altText}
              referrerPolicy="no-referrer"
              // `max-h` chứ không chiều cao cố định: ảnh dọc và ảnh ngang đều
              // phải vừa khung mà không bị méo, nên để tỉ lệ tự nhiên quyết
              // định, chỉ chặn cạnh dài nhất.
              className="max-h-72 w-auto max-w-full rounded-lg object-contain"
            />
          )}
        </div>

        <div className="flex min-w-0 flex-1 flex-col">
          <button
            onClick={onOpen}
            className="text-left text-[15px] font-medium leading-snug text-link hover:underline
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
          >
            <span className="line-clamp-3">{image.pageTitle}</span>
          </button>

          <div className="mt-2 flex items-center gap-1.5">
            <span
              className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[8px] font-bold text-white"
              style={{ background: siteGradient(image.pageUrl) }}
            >
              {siteInitial(image.pageUrl)}
            </span>
            <span className="truncate text-[12px] text-muted">{host}</span>
          </div>

          {image.altText && (
            <p className="mt-3 line-clamp-3 text-[13px] leading-relaxed text-muted">
              {image.altText}
            </p>
          )}

          <dl className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-[11px] text-faint">
            {hasSize && (
              <div className="flex gap-1.5">
                <dt>Kích thước</dt>
                <dd className="tabular-nums text-ink">
                  {image.width}×{image.height}
                </dd>
              </div>
            )}
            <div className="flex min-w-0 gap-1.5">
              <dt className="shrink-0">Văn bản thay thế</dt>
              <dd className={image.missingAlt ? 'text-danger' : 'text-ink'}>
                {image.missingAlt ? 'thiếu' : 'có'}
              </dd>
            </div>
          </dl>

          <div className="mt-auto flex items-center gap-2 pt-4">
            <button
              onClick={onOpen}
              className="rounded-lg bg-brand px-3 py-1.5 text-[13px] font-medium text-white
                         transition hover:brightness-110
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
            >
              Mở trang chứa ảnh
            </button>

            {/* Điều hướng chạy XUYÊN hàng, không chỉ trong hàng hiện tại: bấm
                "sau" ở ảnh cuối hàng thì nhảy sang ảnh đầu hàng dưới và khung
                tự chuyển xuống theo. */}
            <button
              onClick={onPrev}
              disabled={!hasPrev}
              aria-label="Ảnh trước"
              className="rounded-lg border border-line p-1.5 text-muted transition
                         hover:bg-base disabled:opacity-30 disabled:hover:bg-transparent
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
            >
              <ChevronLeftIcon className="h-4 w-4" />
            </button>
            <button
              onClick={onNext}
              disabled={!hasNext}
              aria-label="Ảnh sau"
              className="rounded-lg border border-line p-1.5 text-muted transition
                         hover:bg-base disabled:opacity-30 disabled:hover:bg-transparent
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
            >
              <ChevronRightIcon className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      <button
        onClick={onClose}
        aria-label="Đóng"
        className="absolute right-2 top-2 rounded-lg p-1.5 text-faint transition hover:bg-base hover:text-ink
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/50"
      >
        <CloseIcon className="h-4 w-4" />
      </button>
    </div>
  )
}

function EmptyState(): JSX.Element {
  return (
    <div className="flex flex-col items-center gap-2 py-20 text-center">
      <SearchIcon className="h-9 w-9 text-faint" />
      <p className="text-[15px] text-ink">Không tìm thấy ảnh nào.</p>
      <p className="max-w-md text-[13px] text-muted">
        Ảnh chỉ được ghi nhận trong lúc crawl. Nếu tab &ldquo;Tất cả&rdquo; có kết quả mà đây thì
        không, nghĩa là những trang đó chưa được thu thập ảnh — hãy chạy một phiên crawl mới.
      </p>
    </div>
  )
}

/**
 * Khung xương giữ đúng nhịp của bố cục hàng, để lưới không nhảy khi ảnh về.
 *
 * Các bề rộng dưới đây là phần trăm, cộng lại vừa đúng 100% mỗi hàng — bắt
 * chước đúng thứ {@link buildRows} sẽ tạo ra, nên lúc ảnh thật thay chỗ thì
 * chiều cao trang không đổi.
 */
function GridSkeleton(): JSX.Element {
  const rows = [
    [28, 19, 24, 29],
    [22, 31, 25, 22],
    [26, 23, 33, 18]
  ]
  return (
    <div>
      {rows.map((row, rowIndex) => (
        <div key={rowIndex} className="flex" style={{ gap: GAP, marginBottom: GAP }}>
          {row.map((percent, index) => (
            <div
              key={index}
              className="animate-pulse rounded-lg bg-raised"
              style={{
                height: TARGET_ROW_HEIGHT,
                // Trừ phần khoảng cách khỏi tổng bề rộng, nếu không bốn ô cộng
                // lại đúng 100% rồi cộng thêm gap sẽ tràn và đẩy ô cuối xuống.
                width: `calc(${percent}% - ${(GAP * (row.length - 1)) / row.length}px)`
              }}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

export default ImageResultGrid
