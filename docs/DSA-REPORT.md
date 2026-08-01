# Báo cáo Cấu trúc dữ liệu & Giải thuật (DSA-REPORT)

> **Tài liệu này là gì?** Báo cáo kỹ thuật về **toàn bộ cấu trúc dữ liệu tự
> cài đặt** trong đồ án: độ phức tạp lý thuyết, **lý do chọn** thay vì phương
> án có sẵn, và **số liệu đo thực nghiệm** trên corpus 5.011 trang thu thập
> từ 6 báo điện tử Việt Nam.
>
> **Nguyên tắc xuyên suốt báo cáo:** mỗi khẳng định về hiệu năng đều phải kèm
> **số đo**, không được là suy đoán. Mục 3 ghi lại hai lỗi hiệu năng mà **chỉ
> có đo mới phát hiện được** — đó là phần đáng đọc nhất.
>
> **Tài liệu liên quan:** `SEARCH-ENGINE-101.md` (lý thuyết), `ALGORITHMS.md`
> (từng thuật toán kèm mã), `ARCHITECTURE.md` (kiến trúc),
> `EVALUATION.md` + `GIN-BASELINE.md` (kết quả đánh giá, sinh tự động).

## Mục lục

1. [Bảng tổng hợp Big-O](#1-bảng-tổng-hợp-big-o)
2. [Vì sao chọn cấu trúc này thay vì phương án có sẵn](#2-vì-sao-chọn-cấu-trúc-này-thay-vì-phương-án-có-sẵn)
3. [Ba lỗi hiệu năng chỉ phát hiện được nhờ đo đạc](#3-ba-lỗi-hiệu-năng-chỉ-phát-hiện-được-nhờ-đo-đạc)
4. [Số liệu hiệu năng đầy đủ](#4-số-liệu-hiệu-năng-đầy-đủ)
5. [Kiểm thử](#5-kiểm-thử)
6. [Hạn chế đã biết](#6-hạn-chế-đã-biết)
7. [Cách chạy lại mọi số đo](#7-cách-chạy-lại-mọi-số-đo)

---

## 1. Bảng tổng hợp Big-O

### 1.1. Cấu trúc dữ liệu thuần (`datastructure/`)

Nhóm này **không phụ thuộc** vào bất kỳ gói nào khác trong dự án, nên kiểm
thử được độc lập trong vài chục milli-giây.

| Cấu trúc | File | Dùng để làm gì | Big-O thời gian | Big-O bộ nhớ |
|---|---|---|---|---|
| **Trie** | `Trie.java` | Gợi ý từ khoá (autocomplete) | `insert` O(L) · `search` O(L) · `startsWith` O(L) · `getSuggestions` O(L + m log k) · `clear` **O(1)** | O(tổng số ký tự các từ đã insert) |
| **Bloom Filter** | `BloomFilter.java` | Khử trùng lặp URL khi crawl | `add` / `mightContain` O(k), k = số hàm băm (hằng số, thực tế 7) | O(m) bit — **không** phụ thuộc độ dài chuỗi |
| **LRU Cache** | `LRUCache.java` | Cache `SearchResponse` | `get` / `put` **O(1)** · `size` / `containsKey` O(1) | O(capacity) = 200 mục |
| **Min-Heap** | `MinHeap.java` | Lấy top-K điểm cao nhất | `insert` / `extractMin` O(log n) · `peek` O(1) · `topK` **O(n log k)** | O(n) |
| **Url Frontier** | `crawler/frontier/` (9 lớp) | Hàng đợi hai tầng: ưu tiên + politeness | **`addUrl` O(1)** · **`nextUrl` O(log n)** · `size` / `domainCount` O(1) | O(n), chặn trên 500.000 URL |
| **Sparse Matrix** | `SparseMatrix.java` | Ma trận liên kết web cho PageRank | `set` O(1) amortised · `multiply` **O(nnz)** · `nnz()` O(n + nnz) | O(nnz) |

### 1.2. Chỉ mục và truy vấn (`index/`, `query/`)

| Cấu trúc / Thuật toán | File | Dùng để làm gì | Big-O thời gian | Big-O bộ nhớ |
|---|---|---|---|---|
| **Vietnamese Tokenizer** | `VietnameseTokenizer.java` | Tách từ, chuẩn hoá, bỏ dấu, lọc stopword | `tokenize` O(n × 4) = **O(n)** · `stripDiacritics` O(L) | O(n) cho token + O(\|từ điển\|) cố định |
| **Inverted Index** | `InvertedIndex.java` | Tra tài liệu chứa một term | `addDocument` O(L) · `getPostings` / `getDocumentFrequency` **O(1)** · `getPositions` **O(log n)** · `getAverageDocLength` **O(1)** | O(tổng số cặp (term, doc)) |
| **Term Dictionary** | `TermDictionary.java` | Flyweight cho khoá term | `intern` **O(1)** khấu hao | O(số term phân biệt) — thay vì O(số lần xuất hiện) |
| **Posting Cursor** | `ArrayPostingCursor.java` | Duyệt posting list có nhảy cóc | `next` O(1) · **`skipTo` O(log d)** (galloping) | O(1) — không cấp phát |
| **VByte Codec** | `VByteCodec.java` | Nén danh sách số nguyên tăng dần | `encodeSorted` / `decodeSorted` **O(n)** · `encodeSegments` / `decodeSegments` O(Σn) | O(số byte kết quả) |
| **Compressed Postings** | `CompressedPostings.java` | Dạng nén của posting list trên đĩa | `of` / `toPostings` **O(n + Σ vị trí)** | O(số byte nén) — xem mục 4.2 |
| **Posting List Merger** | `PostingListMerger.java` | Giao/hợp posting list, khớp cụm từ | `intersect` / `union` **O(m+n)** · `intersectCursors` **O(m log(d/m))** · `intersectAll` shortest-first · `matchesPhrase` O(p₁ · k · log p) | O(\|kết quả\|) |
| **Query Parser** | `QueryParser.java` | Tách mustTerms / phrases / excludedTerms | `parse` **O(L)** | O(số term) |
| **Candidate Resolver** | `CandidateResolver.java` | Từ truy vấn đã phân tích → danh sách ứng viên | Chi phối bởi `intersectAll` | O(số ứng viên) |

### 1.3. Xếp hạng (`ranking/`)

| Thuật toán | File | Dùng để làm gì | Big-O thời gian | Big-O bộ nhớ |
|---|---|---|---|---|
| **TF-IDF Scorer** | `TfIdfScorer.java` | Điểm liên quan (cosine) | **O(q log d)** | O(1) ngoài dữ liệu chỉ mục |
| **BM25 Scorer** | `BM25Scorer.java` | Điểm liên quan (baseline công nghiệp) | **O(q log d)** | O(1) ngoài dữ liệu chỉ mục |
| **PageRank** | `PageRankService.java` | Điểm uy tín trang dựa trên liên kết | **O(iterations · (nnz + N))** | O(N + nnz) |
| **Result Ranker** | `ResultRanker.java` | Kết hợp điểm, top-K, sinh snippet | Chấm điểm O(c · q · log d) · top-K O(c log topN) · **snippet chỉ O(topN · docLength)** | O(c) |

### 1.4. Đánh giá chất lượng (`eval/`)

| Thuật toán | File | Big-O thời gian |
|---|---|---|
| **P@k, R@k, F1@k** | `EvaluationMetrics.java` | O(k) |
| **Average Precision** | `EvaluationMetrics.java` | O(\|ranked\|) |
| **nDCG@k** | `EvaluationMetrics.java` | O(k + \|qrels\| log \|qrels\|) — có sort để dựng thứ tự lý tưởng |
| **MRR / Success@k** | `EvaluationMetrics.java` | O(\|ranked\|) — dùng `indexOf` |
| **Known-item query generation** | `KnownItemQueryGenerator.java` | O(N · L) để tokenize + O(V log V) để sắp term mỗi tài liệu |
| **TREC pooling** | `PoolBuilder.java` | O(số cấu hình × số truy vấn × k) |

### 1.5. Phía trình duyệt (TypeScript)

| Cấu trúc | File | Dùng để làm gì | Big-O thời gian | Big-O bộ nhớ |
|---|---|---|---|---|
| **Stack** | `lib/Stack.ts` | Back/forward của trình duyệt | `push` / `pop` / `peek` **O(1)** | O(độ sâu lịch sử) |
| **Trie** | `lib/BookmarkTrie.ts` | Tìm bookmark theo tiền tố tiêu đề | `insert` O(L) · `searchByPrefix` O(L + m) | O(tổng số ký tự tiêu đề) |

### Chú thích ký hiệu

| Ký hiệu | Nghĩa |
|---|---|
| `L` | Độ dài một chuỗi / văn bản (số ký tự hoặc số token) |
| `n`, `m` | Kích thước cấu trúc hoặc danh sách liên quan |
| `N` | Tổng số tài liệu trong corpus (= 5.011) |
| `D` | Số host phân biệt trong frontier (= 52 khi crawl 6 báo) |
| `n_d` | Số URL đang chờ của **một** host |
| `nnz` | Số phần tử khác 0 của ma trận thưa (= 239.691 cạnh) |
| `c` | Số ứng viên sau khi giao posting list |
| `q` | Số term phân biệt trong truy vấn (nhỏ, thường 1–4) |
| `d` | Độ dài posting list dài nhất trong các term của truy vấn |
| `k` | Tham số nhỏ: số hàm băm, số gợi ý, top-k |
| `V` | Số term phân biệt của một tài liệu |

---

## 2. Vì sao chọn cấu trúc này thay vì phương án có sẵn

> **Cách đọc mục này.** Mỗi mục con trả lời cùng một câu hỏi: *"Java đã có
> sẵn thứ tương đương, vậy vì sao vẫn tự cài?"* Câu trả lời phải là **số đo**
> hoặc một **lý do kỹ thuật cụ thể** — không được là "để cho biết".

### 2.1. Bloom Filter thay cho `HashSet<String>` — khử trùng lặp URL

**Bài toán.** Crawl 5.011 trang thu về **394.940 outlink**. Mỗi outlink phải
hỏi "URL này crawl chưa?" trước khi fetch.

**Đo thực tế** với **1.000.000 URL**, `expectedItems = 1.000.000`,
`falsePositiveRate = 0,01`:

| Cấu trúc | Bộ nhớ | Ghi chú |
|---|---|---|
| **BloomFilter** (lý thuyết, `m/8` byte) | **~1.170 KB (~1,1 MB)** | m = 9.585.059 bit, k = 7 |
| `HashSet<String>` (đo heap delta thực tế) | **~110.932 KB (~108 MB)** | Lưu nguyên vẹn từng chuỗi |

→ `HashSet` tốn **~95 lần** bộ nhớ ở cùng quy mô, vì nó phải lưu nguyên vẹn
từng chuỗi URL (cộng overhead của `String`, entry của `HashMap` bên trong, con
trỏ…), trong khi Bloom Filter chỉ lưu vài bit trên mỗi phần tử, **độc lập với
độ dài chuỗi gốc**.

**Kiểm chứng con số bằng công thức:**

$$
m = \left\lceil \frac{-10^6 \ln 0{,}01}{(\ln 2)^2} \right\rceil
  = \left\lceil \frac{4{.}605{.}170}{0{,}480453} \right\rceil
  = 9{.}585{.}059 \text{ bit}
$$

$$
\frac{9{.}585{.}059}{8 \times 1024} = 1{.}170 \text{ KB} \quad ✓
$$

**Đánh đổi, nói cho rõ.** Có tỷ lệ false positive nhỏ (đã cấu hình 1%) nhưng
**không bao giờ** có false negative. Đây là **đúng chiều** đánh đổi cần
thiết cho bài toán "có thể đã crawl hay chưa":

| Loại lỗi | Hậu quả | Có xảy ra không |
|---|---|---|
| False positive | Bỏ lỡ một vài trang chưa crawl | Có, ~1% |
| False negative | Crawl lại trang đã crawl → **vòng lặp vô hạn** | **Không bao giờ** |

**Lưu ý quan trọng: đây không phải lớp khử trùng lặp duy nhất.** `UrlFrontier`
còn giữ một `HashSet<String> enqueued` **chính xác tuyệt đối** để trả lời
"URL này đã **xếp hàng** chưa?". Hai lớp có hai vai trò khác nhau: Bloom
Filter đứng ở chỗ được gọi 394.940 lần (ưu tiên tiết kiệm bộ nhớ), `enqueued`
đứng ở chỗ cần chính xác để frontier không phình. Xem mục 3.1 của
`ARCHITECTURE.md`.

---

### 2.2. Two-pointer `intersect` thay cho `HashSet.retainAll`

**Bài toán.** Truy vấn nhiều term cần lấy **giao** các posting list.

**Đo thực tế** với 2 danh sách **đã sắp xếp**, 500.000 phần tử mỗi bên, kết
quả giao ~250.000 phần tử, trung bình 5 lần chạy:

| Cách làm | Thời gian trung bình/lần |
|---|---|
| **Two-pointer `PostingListMerger.intersect`** | **~10,0 ms** |
| `HashSet.retainAll` (không tính chi phí xây HashSet) | ~15,5 ms (**chậm hơn ~55%**) |
| `HashSet.retainAll` (tính cả chi phí xây 2 HashSet) | ~27,0 ms (**chậm hơn ~2,7 lần**) |

**Vì sao two-pointer thắng ở cả hai kịch bản:**

1. **Không có overhead tính hash và xử lý va chạm** của `HashMap`/`HashSet`.
   $O(m+n)$ của two-pointer là $O(m+n)$ **tuyệt đối**, không có hằng số ẩn.
2. **Tận dụng trực tiếp tính chất "đã sắp xếp"** vốn có của posting list —
   một bất biến được đảm bảo *miễn phí* lúc dựng chỉ mục (xem mục 3.2 của
   `ALGORITHMS.md`).
3. **Không cần cấp phát cấu trúc trung gian** nào.

**Cột nào là so sánh công bằng?** Cột thứ **3**. Trong hệ thống thật, posting
list là `List<Posting>` lấy thẳng từ chỉ mục, nên nếu dùng `HashSet` thì
**phải trả** chi phí xây HashSet ở **mỗi** truy vấn. Cột thứ 2 chỉ có ý nghĩa
nếu HashSet được cache sẵn — mà cache HashSet cho 136.768 term là chuyện
không khả thi về bộ nhớ.

---

### 2.3. Ma trận thưa thay cho `double[n][n]` — đồ thị liên kết cho PageRank

Đây là chỗ **quy mô corpus làm thay đổi kết luận**, nên phải đo ở **hai** mức
— và đó chính là bài học.

| Corpus | n | nnz (cạnh) | Ma trận đặc | Adjacency list | Tỷ lệ thưa nnz/n² |
|---|---|---|---|---|---|
| 150 trang, **1 domain** | 150 | 3.901 | 176 KB | 61 KB | **17,3%** |
| **5.011 trang, 6 domain** | 5.011 | 239.691 | **191,5 MB** | ~3,7 MB | **0,95%** |

**Đọc bảng này thế nào.** Ở corpus nhỏ một-domain, tỷ lệ thưa 17,3% **chưa
ấn tượng** — một website tin tức liên kết chéo nội bộ rất dày (menu, chuyên
mục, bài liên quan), nên ma trận không thưa lắm. Nếu chỉ đo ở mức này, ta có
thể kết luận sai rằng "ma trận thưa không lợi bao nhiêu".

Khi mở rộng lên 6 báo độc lập, tỷ lệ thưa **giảm 18 lần xuống 0,95%** và ma
trận đặc tương đương đã cần **191,5 MB**. Đây là **chứng minh bằng thực
nghiệm** rằng lợi ích của ma trận thưa **tăng theo quy mô corpus**, đúng như
dự đoán lý thuyết — đồ thị web thật, trải trên nhiều triệu domain, thường có
tỷ lệ thưa dưới 0,01%.

**Kiểm chứng 191,5 MB:**

$$
5011 \times 5011 \times 8 \text{ byte} = 200{.}881{.}368 \text{ byte} = 191{,}6 \text{ MB} \quad ✓
$$

**Con số quan trọng nhất trong bảng lại không phải nnz.** Trong 239.691 cạnh
có **42.002 cạnh liên kết chéo giữa các domain** (17,5%). Đây mới là thứ
khiến PageRank **có ý nghĩa**: liên kết nội bộ một tờ báo phản ánh **cấu trúc
điều hướng** chứ không phản ánh **uy tín**. Corpus 150 trang cùng một tờ báo
có **0** liên kết chéo domain, nên PageRank trên đó gần như vô nghĩa — và đó
chính là lý do `MultiDomainCrawlRunner` được viết ra.

**Vì sao adjacency list *rồi mới* CSR — dùng cả hai chứ không chọn một.** CSR
(3 mảng liên tục) có locality tốt hơn hẳn khi `multiply`, nhưng cần **biết
trước** số phần tử để cấp phát mảng cố định. Ma trận này lại được **xây dần**
(`incoming.set(...)` mỗi khi phát hiện một cạnh), nên lúc xây phải là adjacency
list để thêm phần tử trong $O(1)$ khấu hao.

Lời giải là **hai chế độ trong một lớp**: xây bằng adjacency list, rồi
`freeze()` sang CSR $O(nnz)$ **một lần** trước khi bắt đầu lặp. Từ đó mọi vòng
`multiply` chạy trên 3 mảng nguyên thuỷ liên tục. `PageRankService` gọi
`incoming.freeze()` ngay trước vòng lặp power iteration, và `set()` sau khi
freeze sẽ ném `IllegalStateException` — bất biến "đã đóng băng thì bất biến"
được **ép bởi code**, không phải bằng quy ước.

Đây cũng là kỹ thuật `rowPtr` được **dùng lại lần thứ hai** ở
`CompressedPostings` để nén danh sách vị trí (xem mục 4.2) — cùng một ý tưởng,
hai chỗ khác nhau trong đồ án.

---

### 2.4. Tự cài Doubly Linked List cho `LRUCache` thay vì `LinkedHashMap`

**Phương án có sẵn.** `LinkedHashMap` với `accessOrder = true` và override
`removeEldestEntry` làm được LRU cache "miễn phí", chỉ vài dòng.

**Vì sao vẫn tự viết.** Đây là mục duy nhất trong báo cáo mà lý do **không**
phải hiệu năng — hai cách đều $O(1)$. Lý do là **yêu cầu cốt lõi của đồ án
DSA: chứng minh hiểu bản chất, không chỉ biết gọi API có sẵn.** Tự viết Doubly
Linked List + 2 sentinel node buộc phải trả lời được ba câu hỏi:

**(a) Vì sao di chuyển một node lên đầu là $O(1)$?** Vì chỉ đổi **4 con trỏ**,
không cần duyệt danh sách:

```java
private void addToFront(Node<K, V> node) {
    node.prev = head;
    node.next = head.next;
    head.next.prev = node;
    head.next = node;
}
```

**(b) Vì sao cần danh sách liên kết *đôi*?** Để xoá một node ở **giữa** trong
$O(1)$ cần biết **cả** node trước và node sau. Danh sách liên kết đơn phải
duyệt từ đầu để tìm node trước → $O(n)$, và khi đó cache LRU mất hoàn toàn ưu
điểm.

**(c) Vì sao 2 sentinel node?** Để `removeNode` chỉ cần 2 dòng và **không bao
giờ** phải kiểm tra `null` cho trường hợp thêm/xoá ở đầu hoặc cuối:

```java
private void removeNode(Node<K, V> node) {
    node.prev.next = node.next;
    node.next.prev = node.prev;
}
```

Không có sentinel thì hàm này phải thành 6–8 dòng với các nhánh `if (node.prev == null)`,
`if (node.next == null)` — mỗi nhánh là một chỗ có thể sai.

**Phần thưởng ngoài dự kiến: một bẫy đồng thời chỉ hiện ra khi tự viết.**
`get()` **trông như** thao tác đọc, nhưng nó phải `moveToFront` — tức là một
thao tác **ghi**. Dùng read lock ở đây thì nhiều thread cùng "đọc" sẽ cùng
sửa danh sách liên kết và **làm hỏng cấu trúc dữ liệu**:

```java
public V get(K key) {
    lock.writeLock().lock();   // ← KHÔNG phải readLock, dù tên hàm là get
    ...
}
```

Người chỉ gọi `LinkedHashMap` sẽ không bao giờ gặp — và cũng không bao giờ
hiểu — vấn đề này.

---

### 2.5. Hàng đợi tách theo domain cho `UrlFrontier`

**Đây là bài học hiệu năng lớn nhất của phần crawler**, và nó **chỉ lộ ra khi
tăng quy mô**.

**Bản đầu tiên: một heap toàn cục.** Khi phần tử ưu tiên cao nhất thuộc domain
đang trong politeness delay, thuật toán phải rút nó ra, gác sang danh sách
tạm, rồi rút tiếp phần tử sau. Trường hợp xấu nhất — **mọi** URL đang chờ đều
thuộc các domain vừa truy cập — phải rút **cạn** cả heap rồi nhét lại toàn bộ:

$$
O(n \log n) \text{ cho MỖI lần lấy MỘT URL}
$$

**Vì sao lỗi này không lộ ra ở corpus nhỏ.** Ở quy mô 150 trang, chi phí này
**không quan sát được**. Nhưng mỗi trang tin tức sinh trung bình **78,8
outlink** (394.940 / 5.011), nên crawl 5.000 trang đẩy frontier lên hàng chục
nghìn URL — và crawler thực tế **đứng hình**.

**Giải pháp.** Giữ `Map<domain, MinHeap>` — chính là mô hình "back queue theo
host" của crawler **Mercator** (Heydon & Najork, 1999). Chỉ cần quét qua các
domain (`D` nhỏ), chọn domain vừa hết hoãn và có phần tử đầu hàng ưu tiên cao
nhất, rồi `extractMin` **đúng một lần**:

| Thiết kế | Chi phí mỗi `nextUrl()` |
|---|---|
| Một heap toàn cục | $O(n\log n)$ — phụ thuộc **tổng** kích thước frontier |
| **Tách theo domain** | **$O(D + \log n_d)$** — **không** phụ thuộc tổng kích thước |

**Kết quả đo:** crawl 5.011 trang trong **3,2 phút**, thông lượng **26,2
trang/giây**, với **52 host** phân biệt hoạt động song song.

**Trần thông lượng là ràng buộc kiến trúc, không phải vấn đề tối ưu.**
Politeness delay 1 giây/host nghĩa là:

$$
\text{thông lượng tối đa (trang/giây)} = \text{số host được crawl đồng thời}
$$

52 host → trần lý thuyết 52 trang/giây, thực đo 26,2 (khoảng 50% trần, phần
còn lại là độ trễ fetch và parse). Muốn 400 trang/giây thì **phải có ≥ 400
host**, không phải mua máy nhanh hơn.

**Hai chi tiết cài đặt đáng ghi nhận:**

1. **Dọn heap rỗng bằng `it.remove()`** trong vòng quét. Không dọn thì các
   domain đã cạn URL vẫn bị quét lại mãi, khiến `D` chỉ tăng chứ không giảm
   trong suốt phiên crawl — làm mất đúng cái ưu điểm "D nhỏ" mà thiết kế này
   dựa vào.
2. **`Thread.sleep(50)` nằm NGOÀI khối `synchronized`.** Nếu ngủ trong khối
   đồng bộ, thread đang ngủ vẫn giữ khoá và **chặn mọi thread khác muốn
   `addUrl`** — biến một tối ưu thành một điểm nghẽn tệ hơn cả vấn đề ban đầu.

**Chặn trên bộ nhớ.** `DEFAULT_MAX_SIZE = 500_000` URL đang chờ. Khi đầy, URL
mới bị bỏ qua (đếm vào `droppedDueToCapacity`) thay vì để bộ nhớ phình không
kiểm soát. Đây là đánh đổi **có chủ ý**: vì crawler ưu tiên theo bề rộng, các
URL bị bỏ hầu hết là URL độ sâu lớn — vốn có điểm ưu tiên thấp nhất.

---

### 2.6. Sắp xếp shortest-first trong `intersectAll`

**Lập luận.** Gọi `A` là kết quả giao sau `k` bước, luôn có

$$
|A| \le \min\bigl(\text{các list đã xét}\bigr)
$$

Bắt đầu từ list **ngắn nhất** giúp `|A|` nhỏ **ngay từ đầu**, nên các bước
giao kế tiếp — mỗi bước tốn $O(\lvert A\rvert + \lvert\text{list ke tiep}\rvert)$ — rẻ hơn đáng kể
so với bắt đầu từ list dài nhất.

**Khi nào lợi nhất:** khi một term **hiếm** (df nhỏ) trộn với nhiều term **phổ
biến** (df lớn). Ví dụ `iPhone` (df = 5) với `của` (df = 4000): bắt đầu từ
`iPhone` thì kết quả trung gian ≤ 5 phần tử, nên mọi bước sau gần như miễn
phí.

Kèm hai tối ưu thoát sớm:

```java
// Trong intersectAll: giao rỗng thì dừng ngay
for (int i = 1; i < sorted.size() && !result.isEmpty(); i++) { ... }
```

```java
// Trong CandidateResolver: df = 0 ở BẤT KỲ term → trả rỗng, không cần giao gì
if (postings.isEmpty()) {
    return new ResolvedQuery(new ArrayList<>(), queryTermFrequency);
}
```

---

### 2.7. Tự cài `MinHeap` thay vì `java.util.PriorityQueue`

`PriorityQueue` của Java cũng là binary heap trên mảng và cũng nhận
`Comparator`. Ba lý do tự cài:

1. **Yêu cầu đề bài** — heap là cấu trúc dữ liệu lõi phải tự cài.
2. **Cần một `topK` tĩnh có hành vi cụ thể.** `MinHeap.topK` duy trì heap kích
   thước tối đa `k`, trả về danh sách **giảm dần** — `PriorityQueue` không có
   sẵn thao tác này, phải tự viết vòng lặp bên ngoài, và khi đó phần đáng học
   nhất lại nằm ngoài cấu trúc.
3. **Biểu diễn heap hiện rõ trong code** để đưa vào báo cáo: phần tử tại `i`
   có con trái ở `2i+1`, con phải ở `2i+2`, cha ở `(i−1)/2` — biểu diễn "cây
   nhị phân đầy đủ" chuẩn, không cần con trỏ.

---

## 3. Ba lỗi hiệu năng chỉ phát hiện được nhờ đo đạc

> **Mục đích của mục này.** Ghi lại ba vấn đề mà **suy luận thuần không tìm
> ra** — chỉ có số đo mới lộ. Đây là phần trả lời trực tiếp cho câu hỏi "vì
> sao phải đo, chẳng phải Big-O đã đủ sao?".

### 3.1. Sinh snippet cho mọi ứng viên thay vì chỉ top-N

**Triệu chứng.** Thời gian truy vấn tăng nhanh hơn dự kiến khi corpus lớn
lên, dù Big-O của phần chấm điểm không đổi.

**Nguyên nhân.** `ResultRanker.rank()` ban đầu gọi `buildSnippet()` **bên
trong vòng lặp chấm điểm**, tức cho **mọi** ứng viên, rồi mới dùng MinHeap cắt
lấy top-N. Mỗi lần sinh snippet phải tách toàn bộ `bodyText` (trung bình
**1.043 token**) và trượt cửa sổ qua từng từ. Với 500 ứng viên thì **490
snippet bị tạo ra rồi vứt đi ngay**.

**Vì sao Big-O không phát hiện được.** Vì cả hai bản đều là "một vòng lặp qua
`c` ứng viên". Sai lệch nằm ở **hằng số** bên trong vòng lặp — mà Big-O cố ý
bỏ qua hằng số.

**Cách sửa.** Tách thành **ba** bước rõ ràng: chấm điểm → lấy top-K bằng
MinHeap → **chỉ** sinh snippet cho K tài liệu sống sót.

| | Trước | Sau |
|---|---|---|
| Độ phức tạp phần snippet | $O(c\cdot\lvert d\rvert)$ | **$O(\text{topN}\cdot\lvert d\rvert)$** |
| Với c = 500, topN = 10 | 500 snippet | **10 snippet** |

Comment trong code ghi lại nguyên nhân để không ai "tối ưu" ngược trở lại:

```java
// BUOC 1 - chi CHAM DIEM moi ung vien, chua sinh snippet.
// ... Truoc day buoc nay chay cho MOI ung vien roi moi cat top-N, nghia la
// voi 500 ung vien thi 490 snippet bi vut di ngay sau khi tao ra ...
```

---

### 3.2. Lỗi phương pháp đo: bỏ qua JIT warmup của JVM

**Triệu chứng.** Phép so sánh với PostgreSQL ban đầu cho kết quả **10,83 ms**
(chỉ mục tự cài) so với **1,42 ms** (GIN) — chênh gần 8 lần, một con số khó
tin.

**Nguyên nhân — lỗi ở *phương pháp đo*, không ở code.** Phép đo chạy chỉ mục
tự cài **trước**, GIN **sau**. Nhưng JVM thực thi những lần gọi đầu bằng
**trình thông dịch**, chỉ sau vài nghìn lượt thì JIT mới biên dịch sang mã
máy. Nghĩa là **phía chạy trước gánh toàn bộ chi phí khởi động, phía chạy sau
hưởng JVM đã nóng** — chênh lệch đo được phản ánh **thứ tự chạy** chứ không
phản ánh cài đặt.

**Cách sửa.** Thêm 2 vòng làm nóng cho **cả hai** phía trước khi bấm giờ:

```java
System.out.println("Lam nong JVM ...");
for (int round = 0; round < 2; round++) {
    for (KnownItemQueryGenerator.KnownItemQuery q : queries) {
        harness.search(q.queryText(), config, TOP_N);
        repo.searchWithGin(q.queryText(), TOP_N);
    }
}
```

| Phép đo | Trước khi sửa | Sau khi sửa | Chênh |
|---|---|---|---|
| Chỉ mục tự cài | 10,83 ms | **6,43 ms** | −40,6% |
| PostgreSQL GIN | 1,42 ms | 1,18 ms | −16,9% |

*(Cặp số trên là phép đo **lịch sử** tại thời điểm phát hiện lỗi, chụp trên
cùng một máy và cùng một lần chạy — giữ lại để thấy độ lớn của sai lệch. Con
số hiện hành, sau đợt tối ưu tính-trước-theo-truy-vấn ở mục 4.4, là
**1,62 ms** so với **1,24 ms**; xem `docs/GIN-BASELINE.md`.)*

Chi phí warmup chiếm **~40%** con số ban đầu ở phía chạy trước. **Kết luận
cuối cùng không đổi** (GIN vẫn nhanh hơn), nhưng mức chênh lệch báo cáo sai
lệch đáng kể nếu không sửa: từ "chậm hơn 7,6 lần" thành "chậm hơn 5,4 lần".

**Bài học tổng quát:** luôn chạy vài vòng làm nóng cho **mọi** phía trước khi
bấm giờ, và **hoài nghi** mọi phép đo mà thứ tự chạy có thể ảnh hưởng tới.

---

### 3.3. Từ khoá gợi ý là tiếng lẻ — lỗi chất lượng, không phải hiệu năng

**Triệu chứng.** Gõ `cong` vào ô tìm kiếm thì gợi ý ra `cong`, `the`,
`congreso`, và cả những tiêu đề tiếng Anh dài loằng ngoằng.

**Nguyên nhân — ba lỗi cùng lúc trong `SuggestionService.rebuild()`:**

| Lỗi | Hậu quả |
|---|---|
| Chèn **nguyên tiêu đề** làm một gợi ý | Gợi ý dài loằng ngoằng, không ai gõ hết |
| Chèn **từng tiếng lẻ** | `cong`, `the`, `kinh` — trong tiếng Việt tiếng lẻ phần lớn **không phải từ** |
| Chỉ `insert` mà **không `clear()`** | Tiêu đề của corpus **cũ** vẫn còn trong Trie sau mỗi lần crawl lại |

**Cách sửa.** Tokenize tiêu đề bằng **chính** `VietnameseTokenizer` rồi chỉ
lấy hai loại đơn vị mà người dùng **thực sự gõ**:

1. Các **từ ghép** mà tokenizer nhận ra (term chứa dấu `_`).
2. Các **cặp token liền nhau** — bắt các cụm phổ biến mà từ điển chưa kịp có,
   ví dụ `bóng đá Việt Nam`.

Kèm ba bước lọc:

```java
public static final int MIN_SUGGESTION_FREQUENCY = 3;
...
suggestTrie.clear();                                    // (1) xoá sạch trước
...
if (title == null || title.isBlank() || !LanguageDetector.looksVietnamese(title)) {
    continue;                                           // (2) bỏ tiêu đề không phải tiếng Việt
}
...
if (entry.getValue() < MIN_SUGGESTION_FREQUENCY) {
    continue;                                           // (3) chỉ giữ cụm xuất hiện ≥ 3 lần
}
```

Cách phát hiện tiếng Việt cũng đáng nhắc — dùng **dấu thanh** làm dấu hiệu,
với ngưỡng độ dài để không loại nhầm tiêu đề rất ngắn. Hàm này nay nằm trong
lớp riêng `service/LanguageDetector` (trước đây là phương thức private trong
`SearchEngineFacade` — một ví dụ **Feature Envy** rõ rệt):

```java
public static final int MIN_LENGTH_TO_JUDGE = 15;

public static boolean looksVietnamese(String text) {
    if (text == null) return false;
    String trimmed = text.trim();
    if (trimmed.isEmpty()) return false;
    if (trimmed.length() < MIN_LENGTH_TO_JUDGE) {
        return true;    // "Video" có thể không có dấu nào
    }
    // Điểm bất động: stripDiacritics(s) == s ⟺ s không có dấu nào.
    return !VietnameseTokenizer.stripDiacritics(trimmed).equals(trimmed);
}
```

Tách ra còn cho phép dùng nó ở **chỗ thứ hai** mà trước đây bỏ sót:
`KnownItemQueryGenerator` sinh truy vấn đánh giá từ corpus có lẫn bài tiếng
Trung và tiếng Anh, tạo ra những truy vấn vô nghĩa.

**Vì sao lỗi này thuộc mục "chỉ đo mới thấy".** Không có test đơn vị nào bắt
được nó — mọi hàm đều làm đúng thứ nó được viết ra để làm. Lỗi chỉ hiện ra khi
**thực sự gõ vào ô tìm kiếm và nhìn kết quả** trên corpus thật.

---

## 4. Số liệu hiệu năng đầy đủ

Mọi số dưới đây đo trên corpus **5.011 trang** từ 6 báo điện tử Việt Nam.

### 4.1. Crawl

| Phép đo | Kết quả |
|---|---|
| Thời gian crawl 5.011 trang | **3,2 phút** |
| Thông lượng | **26,2 trang/giây** (trần lý thuyết 52 do politeness) |
| Số host phân biệt | **52** |
| Tổng outlink thu được | **394.940** (trung bình **78,8**/trang) |
| Số cạnh trong đồ thị PageRank (outlink trỏ **vào** corpus) | **239.691** |
| — trong đó liên kết nội bộ domain | 197.689 (82,5%) |
| — trong đó **liên kết chéo domain** | **42.002 (17,5%)** |
| Tỷ lệ thưa nnz/n² | **0,9546%** |

### 4.2. Lập chỉ mục

| Phép đo | Kết quả |
|---|---|
| Thời gian dựng chỉ mục đảo | **6,8 – 9,5 giây** (biến động giữa các lần chạy) |
| Số term phân biệt | **136.768** (gồm cả bản không dấu) |
| Độ dài tài liệu trung bình | **1.043,3 token** |
| Kích thước `data/crawled-multi.json` | **62 MB** |

#### Nén chỉ mục — ba mốc, tách bạch hai thay đổi

`data/index.json` chứa **cả ba phần**: posting list, toàn văn `WebDocument`, và
độ dài tài liệu. Định dạng cũ vừa *không nén* vừa *thụt dòng*; đo riêng từng
thay đổi:

| Định dạng | Kích thước | So với mốc trước |
|---|---|---|
| A. Thụt dòng + không nén (**cũ**) | **341,5 MB** | — |
| B. Gói + không nén | **226,6 MB** | −33,7% |
| C. Gói + nén VByte (**đang dùng**) | **94,7 MB** | **−58,2%** |
| | | **Tổng A→C: −72,3% (nhỏ 3,60 lần)** |

> **Vì sao phải ba mốc chứ không phải hai.** Gộp cả hai thay đổi rồi báo một
> con số sẽ quy nhầm công của việc bỏ thụt dòng cho phần nén: nén sẽ được báo
> là −72,3% trong khi công thật của nó là **−58,2%**. Đây là cùng một bài học
> phương pháp với lỗi JIT warmup ở mục 3.2 — *không bao giờ đổi hai biến cùng
> lúc rồi báo một tỷ lệ*.

Chạy lại phép đo này:

```bash
MAVEN_OPTS=-Xmx4g ./mvnw.cmd -q compile exec:java \
  -Dexec.mainClass=com.vnsearch.index.IndexPersistence \
  -Dexec.args="data/crawled-multi.json"
```

Ba kỹ thuật nén và lý do không dùng GZIP: xem `CompressedPostings` Javadoc và
[`SO-SANH-PHUONG-AN.md`](SO-SANH-PHUONG-AN.md) §4.

*(Con số lịch sử **9,1 MB** trong các bản báo cáo trước là của corpus rút gọn
`crawled-documents.json` (~150 trang), **không** phải corpus 5.011 trang — giữ
lại ghi chú này để tránh so sánh nhầm hai quy mô.)*

### 4.3. PageRank

| Phép đo | Kết quả |
|---|---|
| Số vòng lặp tới hội tụ (ngưỡng L1 < 1e-6) | **53 vòng** |
| Thời gian tính | **0,2 giây** |

Số vòng lặp theo quy mô corpus — minh hoạ đồ thị càng lớn và càng nhiều liên
kết chéo thì càng cần nhiều vòng để hội tụ:

| Corpus | Số vòng lặp |
|---|---|
| Đồ thị 6 node tự tạo (test đơn vị) | 1 – 28 |
| 40 trang (seed rút gọn) | 20 |
| 150 trang, 1 domain | 44 |
| **5.011 trang, 6 domain** | **53** |

### 4.4. Truy vấn

| Phép đo | Kết quả |
|---|---|
| Thời gian truy vấn trung bình (**đã làm nóng JVM**) | **1,59 ms** |
| Cùng phép đo, **trước** tối ưu tính-trước-theo-truy-vấn | 3,84 ms |
| Thời gian truy vấn, phép đo lịch sử đã làm nóng (máy khác) | 6,43 ms |
| Thời gian truy vấn, phép đo lịch sử **chưa** làm nóng (số **sai**) | 10,83 ms |
| Cache miss → hit (đo qua HTTP) | 34,5 ms → **12,8 ms** (nhanh 2,7 lần) |

#### Tối ưu tính-trước-theo-truy-vấn (nhanh 2,4 lần)

Chu kỳ một truy vấn là: lấy `c` ứng viên rồi chấm điểm từng cái. Nhưng giao
diện `RelevanceScorer.score` nhận `queryTermFrequency` ở **mỗi** lần gọi, nên
mọi đại lượng suy ra từ truy vấn bị tính lại `c` lần dù chúng không hề đổi:

| Nơi | Việc bị lặp lại cho từng ứng viên | Chi phí mỗi lần |
|---|---|---|
| `TfIdfScorer` | `idf` + trọng số truy vấn của từng term | 2 × `Math.log10` |
| `BM25Scorer` | `idf` của từng term | 1 × `Math.log` |
| `TitleBoostScorer` | dựng lại **cả đối tượng** `QuerySyllables` | 2 `HashSet` + bỏ dấu từng tiếng |

Với 5.000 ứng viên và 3 term, đó là **30.000 phép logarit** và **5.000 đối
tượng tập băm** bị vứt đi ngay sau khi tạo. `RelevanceScorer.prepare` tách
phần chỉ phụ thuộc truy vấn ra một lần, đưa chi phí từ `O(c·q)` xuống `O(q)`.

Đo A/B trên cùng máy, cùng corpus, cùng 200 truy vấn:

| Cấu hình | Trước | Sau | Nhanh hơn |
|---|---|---|---|
| TF-IDF thuần | 3,58 ms | 3,10 ms | 1,2× |
| BM25 thuần | 3,43 ms | 2,20 ms | 1,6× |
| TF-IDF + title | 3,34 ms | 1,97 ms | 1,7× |
| **TF-IDF + PR + title (đang dùng)** | **3,84 ms** | **1,59 ms** | **2,4×** |

**MRR của cả 11 cấu hình không đổi một chữ số thập phân nào** — đây là điều
kiện cần để gọi một thay đổi là "tối ưu" chứ không phải "đánh đổi". Cấu hình
có title boost nhanh lên nhiều nhất, đúng chỗ lãng phí lớn nhất: đó là bài học
"bất biến vòng lặp bị kẹt bên trong vòng lặp".

Một khoản thứ hai cùng loại, ở tầng thấp hơn: `VietnameseTokenizer.stripDiacritics`
trước đây gọi `String.replaceAll("\p{M}", "")`, mà `replaceAll` **biên dịch lại
mẫu regex ở mỗi lần gọi**. Hàm này chạy cho mọi token của mọi tài liệu lúc lập
chỉ mục (hàng triệu lần) rồi lại chạy cho từng từ lúc bôi sáng snippet. Nay là
một lượt quét ký tự, và trường hợp phổ biến nhất (chuỗi vốn không dấu) không
cấp phát gì. Tính tương đương được kiểm chứng vét cạn trên toàn dải U+0000–U+1FFF
cộng 500.000 chuỗi ngẫu nhiên.

> **Cách đọc con số cache.** Phần lớn 12,8 ms còn lại là chi phí round-trip
> HTTP, **không** phải xử lý tìm kiếm — nên đừng đọc nó như "cache hit mất
> 12,8 ms để tra".

### 4.5. PostgreSQL (chỉ là kho lưu trữ)

| Phép đo | Kết quả |
|---|---|
| Nạp 5.011 tài liệu + 394.940 liên kết | **26,5 giây** (189 tài liệu/giây) |
| Đọc lại toàn bộ corpus từ PostgreSQL | **1,0 giây** |
| Kích thước bảng `documents` (kèm chỉ mục) | 79,6 MB |
| Kích thước chỉ mục GIN | 15,9 MB |

Đối chứng chất lượng và tốc độ với GIN: xem `docs/GIN-BASELINE.md`.

### 4.6. Chất lượng xếp hạng

| Cấu hình | MRR | Success@1 |
|---|---|---|
| TF-IDF thuần | 0,8541 | 78,0% |
| TF-IDF + title | 0,8715 | 81,0% |
| TF-IDF + PageRank + title (đang dùng) | 0,8758 | 81,5% |
| BM25 thuần | 0,8989 | 85,0% |
| **BM25 + PageRank + title** | **0,9093** | **85,5%** |

Đầy đủ 11 cấu hình, kiểm định ý nghĩa thống kê và phân tích thang đo:
`docs/EVALUATION.md`.

> **Cấu hình mặc định không phải cấu hình tốt nhất, và điều đó được nói ra.**
> BM25 hơn TF-IDF 0,0335 MRR trên cùng tập truy vấn. Mặc định vẫn là TF-IDF vì
> bộ trọng số PageRank/title được tinh chỉnh cho thang điểm TF-IDF; đổi
> `app.ranking.scorer=bm25` là chuyển sang cấu hình tốt hơn. Chênh lệch này có
> vượt ngưỡng ý nghĩa thống kê hay không thì `EVALUATION.md` §5 trả lời bằng
> paired t-test và randomization test.

---

## 5. Kiểm thử

**280 test, tất cả xanh** (0 failure, 0 error, 0 skipped). Chạy lại:

```bash
cd search-engine
./mvnw.cmd test
```

### 5.1. Phân bố test

| Lớp test | Số test | Trọng tâm |
|---|---|---|
| **`SignificanceTestTest`** | **23** | Paired t-test + randomization test; kỳ vọng lấy từ **dạng đóng giải tích** (Cauchy khi df=1, dạng đóng khi df=2), không lấy từ thư viện khác |
| `EvaluationMetricsTest` | 20 | Mọi giá trị kỳ vọng **tính tay** |
| `QueryAstTest` | 14 | Cây truy vấn AND/OR/NOT, shortest-first, ngữ nghĩa `NOT` |
| `VByteCodecTest` | 13 | Delta + variable-byte, mã hoá theo **đoạn**, vòng lặp mã hoá → giải mã |
| **`CandidateResolverTest`** | **12** | Lui dần về AND-của-tập-con: thứ tự bỏ term theo IDF, cụm từ và mệnh đề `NOT` **không bao giờ** bị bỏ |
| `HeapifyAndFreezeTest` | 12 | Floyd heapify $O(n)$, đóng băng `SparseMatrix` sang CSR |
| `TrieTest` | 12 | Prefix search, tách khoá/hiển thị, top-k theo frequency, **thread-safe** |
| `BM25ScorerTest` | 11 | Kiểm chứng **tính chất** phân biệt BM25 với TF-IDF |
| `UrlFrontierTest` | 14 | Ưu tiên, politeness, **đồng thời với 8 thread** |
| `BackQueuesTest` | 8 | Một host một hàng đợi, nạp lại khi cạn, Mapping Table bị chặn |
| `FrontQueuesTest` | 7 | FIFO trong mức, **chống bỏ đói**, lặp lại được theo hạt giống |
| `DefaultPrioritizerTest` | 8 | Mức ưu tiên, kẹp biên, tín hiệu phụ chỉ nâng một bậc |
| `CrawlConfigTest` | 10 | Giá trị mặc định, kiểm tra hợp lệ, **2 test bản sao phòng thủ** |
| `UrlCanonicalizerTest` | 10 | Từng phép chuẩn hoá, và những phép **không** được làm |
| `PostingCursorTest` | 9 | Galloping đối chiếu quét tuyến tính ở **mọi** vị trí |
| `PostingListMergerTest` | 9 | intersect / union / shortest-first / phrase |
| `ScorerDecoratorTest` | 9 | Decorator **bất biến với thang đo** của scorer cơ sở |
| **`CompressedPostingsTest`** | **8** | Nén/giải nén posting list, **ép bất biến `tf == |positions|`**, ví dụ tính tay 13 byte, 200 vòng ngẫu nhiên |
| `MinHeapTest` | 8 | siftUp/siftDown, topK |
| `QueryParserTest` | 8 | Cụm từ, loại trừ, `OR`, `site:`, tokenize khớp index |
| `SearchEngineFacadeApiTest` | 8 | Hợp đồng API qua facade (không qua HTTP) |
| `TfIdfScorerTest` | 8 | tf, idf, cosine, chuẩn hoá độ dài |
| `BloomFilterTest` | 7 | Không false negative, tỷ lệ false positive |
| `CrawlStatusTest` | 7 | Máy trạng thái; **không trạng thái nào chuyển về chính nó** |
| `LRUCacheTest` | 7 | Thứ tự MRU/LRU, eviction |
| `ResultRankerTest` | 7 | Kết hợp điểm, snippet, **bôi sáng có dấu** |
| `VietnameseTokenizerTest` | 7 | Longest Matching, NFC/NFD, `đ`, stopword |
| `InvertedIndexTest` | 6 | Bất biến sắp xếp **tự ép**, chỉ mục kép, binary search |
| `PageRankServiceTest` | 6 | Kiểm chứng bằng **tính chất toán học** |
| `RobotsTxtParserTest` | 6 | Longest-prefix-match, section riêng thắng `*` |
| `SparseMatrixTest` | 6 | set/multiply/nnz, biên |
| `UrlFilterTest` | 11 | Lọc độ sâu / scheme / domain / đuôi tệp, đếm theo nguyên nhân |
| `UrlSeenFilterTest` | 10 | Test-and-set nguyên tử, cỡ bộ lọc, lưu bền + nạp lại |
| `ContentSeenFilterTest` | 8 | Vân tay SHA-256, chuẩn hoá, đồng thời |
| `LinkExtractorTest` | 5 | URL tuyệt đối, khử trùng, bỏ scheme không phải http |
| `ContentParserTest` | 4 | Trích title/meta/body, **không** bóc liên kết |
| `IndexPersistenceTest` | 1 | Lưu rồi nạp lại phải bằng nhau |
| `VnSearchApplicationTests` | 1 | Spring context khởi động được |
| **40 lớp** | **340** | |

Ba lớp in đậm là test của đợt sửa lỗi và tối ưu gần nhất: chúng phủ dạng nén
posting list, kiểm định thống kê, và cơ chế nới lỏng truy vấn.

### 5.2. Bốn lớp test đáng chú ý nhất

**`UrlFrontierTest` (11 test) — kiểm thử tính đồng thời thật.** Bao gồm một
test với **8 thread** xác nhận **không URL nào bị phát cho hai thread khác
nhau**, và một test xác nhận politeness delay **buộc** crawler luân phiên
giữa các domain. Đây là loại tính chất mà đọc code không đủ để tin.

**`EvaluationMetricsTest` (20 test) — mọi giá trị kỳ vọng đều tính tay** và
ghi rõ phép tính trong comment. Chính bộ test này đã bắt được **một lỗi làm
tròn trong giá trị nDCG tính tay ban đầu**: `0,9639403` so với giá trị đúng
`0,96394043`. Nghĩa là test không chỉ kiểm tra code — nó kiểm tra cả **phép
tính tay của người viết test**.

**`BM25ScorerTest` (11 test) — kiểm chứng tính chất, không kiểm chứng số.**
Ba tính chất phân biệt BM25 với TF-IDF đều được kiểm riêng: bão hoà tần suất,
IDF không bao giờ âm, ảnh hưởng của tham số `b` tới chuẩn hoá độ dài. Cách
này bền hơn hardcode số: đổi `k1`/`b` thì test vẫn đúng.

**`PageRankServiceTest` (6 test) — kiểm chứng bằng tính chất toán học** thay
vì hardcode số: tổng PageRank ≈ 1, chu trình đối xứng cho điểm bằng nhau, và
dangling node không làm rò rỉ xác suất. Hardcode số ở đây sẽ vô nghĩa, vì kết
quả phụ thuộc đồ thị đầu vào.

---

## 6. Hạn chế đã biết

> Nêu ra để người đọc không phải tự phát hiện — và để biết chỗ nào đáng làm
> tiếp nếu mở rộng đồ án.

### 6.1. Từ điển tách từ chỉ có 154 mục — trần chất lượng của cả hệ thống

`vietnamese-bigrams.txt` có **154 mục** phân bố như sau:

| Số tiếng | Số mục | Ví dụ |
|---|---|---|
| 2 tiếng | 131 | `máy tính`, `khoa học`, `internet` |
| 3 tiếng | 11 | `trình duyệt web`, `mạng xã hội`, `bất động sản` |
| 4 tiếng | 12 | `khoa học máy tính`, `trí tuệ nhân tạo`, `thương mại điện tử` |

Thuật toán Longest Matching cài **đúng**, nhưng chạy trên từ điển nhỏ này thì
nhiều cụm từ phổ biến không được ghép: `máy tính` **có** nên ghép đúng, còn
`bóng đá` **không có** nên bị tách thành `bóng` + `đá`. Một từ điển tiếng Việt
đầy đủ cần **30.000–70.000 mục**.

**Độ chính xác tách từ chưa được đo** — đây là khoảng trống lớn nhất của phần
đánh giá. Muốn đo cần một tập văn bản đã tách từ thủ công làm chuẩn.

Ghi chú thêm về cách đặt tên: biến trong code là `bigramDictionary` và file là
`vietnamese-bigrams.txt`, nhưng từ điển thực chất chứa cụm **tới 4 tiếng** —
tên gọi gây nhầm.

### 6.2. Toán tử `-` chỉ loại trừ một tiếng

`-quảng cáo` chỉ loại trừ `quảng`, còn `cáo` vẫn là `mustTerm`. Muốn loại trừ
cả cụm phải viết `-"quảng cáo"` — **chưa hỗ trợ** dấu `-` trước cụm trong
ngoặc kép.

### 6.3. Trọng số PageRank không cùng thang đo với TF-IDF

`β = 0,3` **không** có nghĩa PageRank đóng góp 30%. Đo thực tế: sau khi nhân
trọng số, TF-IDF đóng góp **gấp ~1.004 lần** PageRank, vì PageRank là một phân
phối xác suất tổng bằng 1 trên 5.011 tài liệu nên giá trị điển hình chỉ quanh
`1/N ≈ 0,0002`.

Hệ quả nghiêm trọng cho việc diễn giải: **chênh lệch quan sát được trong phép
quét β thực chất phản ánh việc α bị thay đổi theo** (do ràng buộc
`alpha = 0.9 − beta` trong `EvaluationRunner`), chứ không phải ảnh hưởng của
PageRank.

Cách khắc phục: chuẩn hoá PageRank trước khi kết hợp (chia cho giá trị lớn
nhất, hoặc min-max normalisation trên tập ứng viên của từng truy vấn). Phân
tích đầy đủ ở mục 6 của `docs/EVALUATION.md`.

### 6.4. Nén chỉ mục — đã cài, nhưng chỉ ở tầng lưu trữ

Posting list **trên đĩa** đã được nén bằng delta + variable-byte (mục 4.2:
giảm 58,2%). Nhưng chỉ mục **trong bộ nhớ** vẫn là `List<Posting>` với
`Integer` boxed — nghĩa là mỗi docId tốn 16 byte thay vì 4.

Nén ngay trong bộ nhớ (giữ posting list ở dạng `byte[]` và giải mã khi duyệt)
sẽ tiết kiệm nhiều hơn hẳn, nhưng đổi lại phải giải mã ở **đường nóng** của
mỗi truy vấn. Đây là đánh đổi chưa được đo — và là việc đáng làm tiếp.

### 6.5. Chưa có WAND / MaxScore — khoảng trống thuật toán lớn nhất

`ResultRanker` chấm điểm **mọi** ứng viên rồi mới cắt top-K.
`MaxCandidatesFilter` là chặn trên an toàn, **không** phải tối ưu top-K chính
xác: nó cắt danh sách ứng viên **trước khi biết điểm**, nên về nguyên tắc có
thể loại nhầm một tài liệu đáng lẽ đứng đầu.

**WAND** (Broder 2003) giải đúng bài toán này: dùng cận trên điểm của từng term
để bỏ qua tài liệu không thể vào top-K, mà kết quả vẫn **đúng chính xác**. Hạ
tầng cần thiết đã có sẵn — `PostingCursor.skipTo` với galloping. Phân tích đầy
đủ: [`SO-SANH-PHUONG-AN.md`](SO-SANH-PHUONG-AN.md) §6.

### 6.6. Chỉ mục tự cài vẫn chậm hơn PostgreSQL GIN

**1,62 ms so với 1,24 ms** (trước đợt tối ưu ở mục 4.4 là 3,84 ms, tức chậm
hơn 2,9 lần; nay còn 1,31 lần) — báo cáo trung thực kèm phân tích
nguyên nhân trong `docs/GIN-BASELINE.md`. Đáng chú ý vì GIN còn phải đi qua
tầng mạng và SQL, nghĩa là chỉ mục tự cài **vẫn còn dư địa**: nén trong bộ
nhớ (6.4), WAND (6.5), và tránh boxing `Integer` ở `docIdsOf`.

### 6.7. Một số điểm còn tối ưu được, đã xác định nhưng chưa sửa

| Chỗ | Vấn đề | Ảnh hưởng hiện tại |
|---|---|---|
| `PostingListMerger` | `docIdsOf` tạo `List<Integer>` mới → boxing 250.000 `Integer` mỗi phép giao lớn | Trung bình; dùng `int[]` sẽ nhanh hơn đáng kể |
| `CandidateResolver` | Chuỗi `FILTERS` là `static final`, không inject được → không cấu hình được theo request, khó mock trong test | Nhỏ ở quy mô hiện tại |
| `UrlFrontier.nextUrl` | Hàng đợi tái sử dụng thừa hưởng đồng hồ lịch sự của host trước | Chờ thừa, không bao giờ chờ thiếu |
| `RobotsTxtParser` | Bỏ qua wildcard `*` / `$`; khi hai luật cùng độ dài thì luật đầu thắng (chuẩn: `Allow` thắng) | Nhỏ |
| `HtmlDownloader.download` | Retry **không có** exponential backoff | Có thể dồn tải lên server đang gặp sự cố |

Các hạn chế **kiến trúc** (chỉ mục một tiến trình, reindex toàn phần, không
có `Content Seen?`…): xem mục 6 của `ARCHITECTURE.md`. Các điểm **vỡ ở quy mô
1 tỷ trang**: xem mục 13 của `SEARCH-ENGINE-101.md`.

---

## 7. Cách chạy lại mọi số đo

Mọi con số trong báo cáo này **tái lập được** — seed ngẫu nhiên cố định (42),
corpus cố định.

```bash
cd search-engine

# 1. Bộ test đầy đủ (280 test)
./mvnw.cmd test

# 2. Demo từng cấu trúc dữ liệu, chạy độc lập không cần Spring
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.datastructure.MinHeap
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.datastructure.BloomFilter
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.datastructure.LRUCache
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.datastructure.Trie
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.datastructure.SparseMatrix
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.crawler.frontier.UrlFrontier
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.index.VietnameseTokenizer
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.index.InvertedIndex
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.query.PostingListMerger
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.ranking.TfIdfScorer
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.ranking.PageRankService
./mvnw.cmd -q compile exec:java -Dexec.mainClass=com.vnsearch.ranking.ResultRanker

# 2b. Đo kích thước chỉ mục theo 3 định dạng (mục 4.2) + kiểm chứng nạp lại
MAVEN_OPTS=-Xmx4g ./mvnw.cmd -q compile exec:java \
  -Dexec.mainClass=com.vnsearch.index.IndexPersistence \
  -Dexec.args="data/crawled-multi.json"

# 3. Dựng lại corpus lớn (~3-5 phút, cần mạng)
./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.crawler.MultiDomainCrawlRunner \
  -Dexec.args="5000 3 data/crawled-multi.json"

# 4. Đánh giá chất lượng + ablation trọng số → sinh docs/EVALUATION.md
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \
  -Dexec.args="data/crawled-multi.json 200"

# 5. Đối chứng với PostgreSQL GIN → sinh docs/GIN-BASELINE.md (cần Docker)
docker compose up -d                              # từ thư mục gốc
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.storage.PostgresImportRunner \
  -Dexec.args="data/crawled-multi.json"
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.storage.GinBaselineRunner -Dexec.args="200"

# 6. Sinh pool để gán nhãn liên quan thủ công (cho nDCG/MAP)
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.eval.QrelsEvaluationRunner \
  -Dexec.args="pool data/crawled-multi.json"
```

> **Lưu ý về tính tái lập.** Các con số **chất lượng** (MRR, Success@k) tái
> lập chính xác vì seed cố định. Các con số **thời gian** (ms/truy vấn, giây
> dựng chỉ mục) sẽ dao động vài phần trăm giữa các lần chạy và giữa các máy —
> đó là bản chất của phép đo thời gian, không phải lỗi.
