# Báo cáo Cấu trúc dữ liệu & Giải thuật (DSA-REPORT)

> Tài liệu này tổng hợp toàn bộ cấu trúc dữ liệu / giải thuật **tự cài đặt**
> trong đồ án, kèm độ phức tạp lý thuyết và **số liệu đo thực tế trên corpus
> 5.011 trang** thu thập từ 6 báo điện tử Việt Nam.
>
> Tài liệu liên quan:
> - `docs/ALGORITHMS.md` — danh sách thuật toán theo thứ tự pipeline.
> - `docs/EVALUATION.md` — đánh giá **chất lượng** tìm kiếm (sinh tự động).
> - `docs/GIN-BASELINE.md` — đối chứng với PostgreSQL GIN (sinh tự động).

## 1. Bảng tổng hợp

| Cấu trúc / Thuật toán | File | Dùng để làm gì | Big-O thời gian | Big-O bộ nhớ |
|---|---|---|---|---|
| Trie | `datastructure/Trie.java` | Gợi ý từ khoá (autocomplete) | insert O(L), search O(L), suggest O(L + m·log k), clear O(1) | O(tổng số ký tự các từ đã insert) |
| Bloom Filter | `datastructure/BloomFilter.java` | Khử trùng lặp URL khi crawl | add/mightContain O(k), k = số hàm băm (hằng số nhỏ) | O(m) bit |
| LRU Cache | `datastructure/LRUCache.java` | Cache kết quả tìm kiếm | get/put O(1) | O(capacity) |
| Min-Heap | `datastructure/MinHeap.java` | Lấy top-K kết quả điểm cao nhất | insert/extractMin O(log n), topK O(n·log k) | O(n) |
| **Url Frontier (hàng đợi tách theo domain)** | `datastructure/UrlFrontier.java` | Hàng đợi URL cho crawler, có ưu tiên + politeness | addUrl O(log n_d), **nextUrl O(D + log n_d)** | O(n) |
| Sparse Matrix (adjacency list) | `datastructure/SparseMatrix.java` | Ma trận liên kết web cho PageRank | set O(1) amortized, multiply O(nnz) | O(nnz) |
| Inverted Index | `index/InvertedIndex.java` | Tra cứu tài liệu chứa một term | addDocument O(L), getPostings O(1), getPositions O(log n) | O(tổng số cặp (term, doc)) |
| Posting List Merger | `query/PostingListMerger.java` | Giao/hợp posting list, khớp cụm từ | intersect/union O(m+n), intersectAll O(tổng) với shortest-first | O(kết quả) |
| TF-IDF Scorer | `ranking/TfIdfScorer.java` | Điểm liên quan (cosine) | O(q·log d) | O(1) ngoài dữ liệu chỉ mục |
| **BM25 Scorer** | `ranking/BM25Scorer.java` | Điểm liên quan (baseline chuẩn công nghiệp) | O(q·log d) | O(1) ngoài dữ liệu chỉ mục |
| PageRank | `ranking/PageRankService.java` | Điểm uy tín trang dựa trên liên kết | O(iterations · (nnz + N)) | O(N + nnz) |
| ResultRanker (+ snippet) | `ranking/ResultRanker.java` | Kết hợp điểm, sinh snippet | rank O(c·log topN), **snippet chỉ O(topN · docLength)** | O(c) |
| **Các độ đo IR** | `eval/EvaluationMetrics.java` | P@k, R@k, MAP, nDCG, MRR | O(k) hoặc O(\|ranked\|) tuỳ độ đo | O(1) |
| Stack (TypeScript) | `browser-app/.../lib/Stack.ts` | Back/forward của trình duyệt | push/pop/peek O(1) | O(độ sâu lịch sử) |
| Trie (TypeScript) | `browser-app/.../lib/BookmarkTrie.ts` | Tìm bookmark theo tiền tố | insert O(L), searchByPrefix O(L + m) | O(tổng số ký tự tiêu đề) |

*(L = độ dài chuỗi/văn bản; n, m = kích thước cấu trúc/danh sách liên quan;
N = số tài liệu; D = số domain phân biệt; n_d = số URL của một domain;
nnz = số phần tử khác 0 của ma trận thưa; c = số ứng viên; k, q = tham số
nhỏ.)*

## 2. Vì sao chọn cấu trúc này thay vì phương án khác

### 2.1. Bloom Filter thay cho `HashSet<String>` — khử trùng lặp URL

Đo thực tế với **1.000.000 URL**, `expectedItems = 1.000.000`,
`falsePositiveRate = 0,01`:

| Cấu trúc | Bộ nhớ |
|---|---|
| BloomFilter (lý thuyết, `m/8` byte) | **~1.170 KB (~1,1 MB)** |
| `HashSet<String>` (đo heap delta thực tế) | **~110.932 KB (~108 MB)** |

→ HashSet tốn **~95 lần** bộ nhớ so với Bloom Filter ở cùng quy mô, vì
HashSet phải lưu nguyên vẹn từng chuỗi URL (cộng thêm overhead của
`String`, entry của HashMap bên trong, con trỏ...), trong khi Bloom Filter
chỉ lưu vài bit trên mỗi phần tử, độc lập với độ dài chuỗi gốc.

Đánh đổi: có tỷ lệ false positive nhỏ (đã cấu hình 1%) nhưng **không bao
giờ** false negative — chấp nhận được cho bài toán "có thể đã crawl hay
chưa", vì false positive tối đa chỉ khiến bỏ lỡ một vài trang, không gây
lỗi logic.

### 2.2. Two-pointer `intersect` thay cho `HashSet.retainAll`

Đo thực tế với 2 danh sách đã sắp xếp, **500.000 phần tử mỗi bên**, kết quả
giao ~250.000 phần tử, trung bình 5 lần chạy:

| Cách làm | Thời gian trung bình/lần |
|---|---|
| Two-pointer `PostingListMerger.intersect` | **~10,0 ms** |
| `HashSet.retainAll` (không tính chi phí xây HashSet) | ~15,5 ms (**chậm hơn ~55%**) |
| `HashSet.retainAll` (tính cả chi phí xây 2 HashSet) | ~27,0 ms (**chậm hơn ~2,7 lần**) |

→ Two-pointer thắng ở cả 2 kịch bản vì: (1) không có overhead tính hash và
xử lý va chạm của HashMap/HashSet, (2) tận dụng trực tiếp tính chất "đã sắp
xếp" vốn có của posting list mà không cần cấu trúc trung gian nào. Trong hệ
thống thực tế, posting list là `List<Posting>` lấy thẳng từ chỉ mục nên
phải tính **cả** chi phí xây HashSet mỗi lần truy vấn — cột thứ 3 là so
sánh công bằng nhất.

### 2.3. Ma trận thưa thay cho `double[n][n]` — đồ thị liên kết cho PageRank

Đây là chỗ **quy mô corpus làm thay đổi kết luận**, nên được đo ở hai mức:

| Corpus | n | nnz (cạnh) | Ma trận đặc | Adjacency list | Tỷ lệ thưa nnz/n² |
|---|---|---|---|---|---|
| 150 trang, **1 domain** | 150 | 3.901 | 176 KB | 61 KB | **17,3%** |
| **5.011 trang, 6 domain** | 5.011 | 239.691 | **191,5 MB** | ~3,7 MB | **0,95%** |

Ở corpus nhỏ một-domain, tỷ lệ thưa 17,3% chưa ấn tượng vì một website tin
tức liên kết chéo nội bộ rất dày (menu, chuyên mục, bài liên quan). Khi mở
rộng lên 6 báo độc lập, tỷ lệ thưa **giảm 18 lần xuống 0,95%** và ma trận
đặc tương đương đã cần 191,5 MB — **chứng minh bằng thực nghiệm** rằng lợi
ích của ma trận thưa **tăng theo quy mô corpus**, đúng như dự đoán lý
thuyết (đồ thị web thật, trải trên nhiều domain, thường có tỷ lệ thưa dưới
0,01%).

Trong 239.691 cạnh có **42.002 cạnh liên kết chéo giữa các domain** — đây
mới là thứ khiến PageRank có ý nghĩa, vì liên kết nội bộ một tờ báo phản
ánh cấu trúc điều hướng chứ không phản ánh uy tín.

### 2.4. Tự cài Doubly Linked List cho `LRUCache` thay vì `LinkedHashMap`

`LinkedHashMap` (với `accessOrder = true` và override `removeEldestEntry`)
có thể làm LRU cache "miễn phí", nhưng tự viết Doubly Linked List + 2
sentinel node buộc phải hiểu rõ **cơ chế** bên trong: vì sao di chuyển một
node lên đầu là O(1) (chỉ đổi 4 con trỏ `prev`/`next`, không cần duyệt danh
sách), vì sao cần 2 sentinel để không phải kiểm tra `null` riêng cho trường
hợp thêm/xoá ở đầu hoặc cuối. Đây chính là yêu cầu cốt lõi của đồ án DSA:
chứng minh **hiểu bản chất**, không chỉ biết gọi API có sẵn.

### 2.5. Hàng đợi tách theo domain cho `UrlFrontier`

Bản đầu tiên dùng **một heap toàn cục**. Khi phần tử ưu tiên cao nhất thuộc
domain đang trong politeness delay, thuật toán phải rút nó ra, gác sang
danh sách tạm, rồi rút tiếp phần tử sau. Trường hợp xấu nhất — mọi URL đang
chờ đều thuộc các domain vừa truy cập — phải rút **cạn** cả heap rồi nhét
lại toàn bộ: **O(n log n) cho mỗi lần lấy một URL**.

Ở quy mô 150 trang, chi phí này không quan sát được. Nhưng mỗi trang tin
tức sinh trung bình **78,8 outlink**, nên crawl 5.000 trang đẩy frontier
lên hàng chục nghìn URL và crawler thực tế đứng hình.

Giải pháp: giữ `Map<domain, MinHeap>` — chính là mô hình "back queue theo
host" của crawler **Mercator** (Heydon & Najork, 1999). Chỉ cần quét qua
các domain (D nhỏ), chọn domain vừa hết hoãn và có phần tử đầu hàng ưu tiên
cao nhất, rồi `extractMin` đúng một lần:

| Thiết kế | Chi phí mỗi `nextUrl()` |
|---|---|
| Một heap toàn cục | O(n log n) — phụ thuộc **tổng** kích thước frontier |
| **Tách theo domain** | **O(D + log n_d)** — không phụ thuộc tổng kích thước |

Kết quả đo: crawl 5.011 trang trong **3,2 phút**, thông lượng **26,2
trang/giây**, với 50+ host phân biệt hoạt động song song.

### 2.6. Sắp xếp shortest-first trong `intersectAll`

Khi truy vấn nhiều term, sắp xếp các posting list theo độ dài **tăng dần**
trước khi giao tuần tự: gọi A là kết quả giao sau k bước, luôn có
`|A| <= min(các list đã xét)`. Bắt đầu từ list **ngắn nhất** giúp `|A|` nhỏ
ngay từ đầu, nên các bước giao kế tiếp (O(|A| + |list kế tiếp|)) rẻ hơn
đáng kể so với bắt đầu từ list dài nhất — đặc biệt lợi khi một term hiếm
(document frequency nhỏ) trộn với nhiều term phổ biến.

## 3. Hai lỗi hiệu năng phát hiện được nhờ đo đạc

Phần này ghi lại hai vấn đề **chỉ lộ ra khi có số liệu**, minh hoạ vì sao
đo đạc quan trọng hơn suy đoán.

### 3.1. Sinh snippet cho mọi ứng viên thay vì chỉ top-N

`ResultRanker.rank()` ban đầu gọi `buildSnippet()` **bên trong vòng lặp
chấm điểm**, tức cho **mọi** ứng viên, rồi mới dùng MinHeap cắt lấy top-N.
Mỗi lần sinh snippet phải tách toàn bộ `bodyText` (trung bình **1.043
token**) và trượt cửa sổ qua từng từ. Với 500 ứng viên thì 490 snippet bị
tạo ra rồi vứt đi ngay.

Sửa thành 3 bước: chấm điểm → lấy top-K bằng MinHeap → **chỉ** sinh snippet
cho K tài liệu sống sót. Độ phức tạp phần snippet giảm từ
`O(c · docLength)` xuống `O(topN · docLength)`.

### 3.2. Lỗi phương pháp đo: bỏ qua JIT warmup của JVM

Phép so sánh với PostgreSQL ban đầu chạy chỉ mục tự cài **trước**, GIN
**sau**. Kết quả: 10,83 ms so với 1,42 ms. Nhưng JVM thực thi những lần gọi
đầu bằng trình thông dịch, chỉ sau vài nghìn lượt JIT mới biên dịch sang mã
máy — nghĩa là phía chạy trước gánh toàn bộ chi phí khởi động còn phía chạy
sau hưởng JVM đã nóng.

Sau khi thêm 2 vòng làm nóng cho **cả hai** phía trước khi đo:

| Phép đo | Trước khi sửa | Sau khi sửa |
|---|---|---|
| Chỉ mục tự cài | 10,83 ms | **6,43 ms** |
| PostgreSQL GIN | 1,42 ms | 1,18 ms |

Chi phí warmup chiếm **40%** con số ban đầu. Kết luận cuối cùng không đổi
(GIN vẫn nhanh hơn), nhưng mức chênh lệch báo cáo sai lệch đáng kể nếu
không sửa.

## 4. Số liệu hiệu năng (corpus 5.011 trang)

| Phép đo | Kết quả |
|---|---|
| Thời gian crawl 5.011 trang | 3,2 phút (26,2 trang/giây) |
| Thời gian dựng chỉ mục đảo | 6,6 – 8,1 giây |
| Số term phân biệt | 136.768 |
| Độ dài tài liệu trung bình | 1.043,3 token |
| Số vòng lặp PageRank tới hội tụ (ngưỡng 1e-6) | **53 vòng** |
| Thời gian tính PageRank | 0,2 giây |
| Thời gian truy vấn trung bình (đã làm nóng JVM) | **6,43 ms** |
| Nạp 5.011 tài liệu + 394.940 liên kết vào PostgreSQL | 27,5 giây (182 tài liệu/giây) |
| Đọc lại toàn bộ corpus từ PostgreSQL | **1,1 giây** |
| Kích thước bảng `documents` (kèm chỉ mục) | 79,6 MB |
| Kích thước chỉ mục GIN | 15,9 MB |

Số vòng lặp PageRank theo quy mô corpus — minh hoạ đồ thị càng lớn và càng
nhiều liên kết chéo thì càng cần nhiều vòng lặp để hội tụ:

| Corpus | Số vòng lặp |
|---|---|
| Đồ thị 6 node tự tạo (test đơn vị) | 1 – 28 |
| 40 trang (seed rút gọn) | 20 |
| 150 trang, 1 domain | 44 |
| **5.011 trang, 6 domain** | **53** |

## 5. Kiểm thử

**148 test**, tất cả xanh. Đáng chú ý:

- `UrlFrontierTest` (11 test) — bao gồm test **đồng thời** với 8 thread xác
  nhận không URL nào bị phát cho hai thread khác nhau, và test xác nhận
  politeness delay buộc crawler luân phiên giữa các domain.
- `EvaluationMetricsTest` (20 test) — mọi giá trị kỳ vọng đều **tính tay**
  và ghi rõ phép tính trong comment. Chính bộ test này đã bắt được một lỗi
  làm tròn trong giá trị nDCG tính tay ban đầu (0,9639403 so với giá trị
  đúng 0,96394043).
- `BM25ScorerTest` (11 test) — kiểm chứng các **tính chất** phân biệt BM25
  với TF-IDF: bão hoà tần suất, IDF không bao giờ âm, ảnh hưởng của tham số
  `b` tới chuẩn hoá độ dài.
- `PageRankServiceTest` — kiểm chứng bằng **tính chất toán học** (tổng
  PageRank ≈ 1, chu trình đối xứng cho điểm bằng nhau) thay vì hardcode số.

## 6. Hạn chế đã biết

1. **Từ điển tách từ chỉ có 158 bigram.** Thuật toán Longest Matching cài
   đúng, nhưng chạy trên từ điển nhỏ này thì nhiều cụm từ phổ biến không
   được ghép — ví dụ "bóng đá" bị tách thành hai tiếng rời rạc, trong khi
   "máy tính" thì được ghép. Độ chính xác tách từ **chưa được đo**.
2. **Toán tử `-` chỉ loại trừ một tiếng** ngay sau nó, không tự động loại
   trừ cả cụm từ ghép. Muốn loại trừ cụm phải viết `-"cụm từ"` (chưa hỗ trợ).
3. **Trọng số PageRank không cùng thang đo** với TF-IDF — xem mục 6 của
   `docs/EVALUATION.md`. `beta = 0.3` không có nghĩa PageRank đóng góp 30%.
4. **Chưa nén chỉ mục.** Posting list lưu nguyên docId dạng số nguyên, chưa
   dùng delta encoding hay variable-byte.
5. Chỉ mục tự cài **chậm hơn PostgreSQL GIN 5,4 lần** — xem
   `docs/GIN-BASELINE.md`.
