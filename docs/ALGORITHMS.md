# Danh sách thuật toán đã sử dụng (ALGORITHMS)

> Tài liệu này liệt kê riêng các **thuật toán** (giải thuật/quy trình xử lý)
> được dùng trong search engine, tách biệt với bảng cấu trúc dữ liệu đã có
> trong `docs/DSA-REPORT.md`. Sắp xếp theo đúng thứ tự pipeline: crawl ->
> tokenize -> index -> query -> rank -> serve. Mỗi mục đều trỏ tới file
> mã nguồn thật sự hiện thực.

## 1. Thu thập dữ liệu (Crawl)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **BFS (Breadth-First Search) có ưu tiên** | `crawler/CrawlerService.java` | Duyệt web từ seed URL theo từng lớp độ sâu (`depth`), giới hạn `maxDepth`/`maxPages`, chia việc cho nhiều worker thread (`ExecutorService`) chạy song song trên cùng một `UrlFrontier`. |
| **Priority scheduling (biến thể của hàng đợi ưu tiên kiểu Dijkstra/greedy)** | `datastructure/UrlFrontier.java` | Điểm ưu tiên = hàm của (độ sâu, số backlink đã biết, domain `.vn`) — dùng MinHeap đảo dấu (`-priority`) để biến min-heap thành "lấy phần tử ưu tiên cao nhất trước". |
| **Hàng đợi tách theo host (mô hình crawler Mercator)** | `datastructure/UrlFrontier.java` | `Map<domain, MinHeap>` thay vì một heap toàn cục. Giảm chi phí mỗi lần lấy URL từ O(n log n) xuống **O(D + log n_d)** — xem phân tích ở `DSA-REPORT.md` mục 2.5. |
| **Politeness scheduling** | `UrlFrontier.nextUrl()` | Chỉ xét các domain đã qua `POLITENESS_DELAY_MS`, tránh dồn dập một domain; buộc crawler luân phiên giữa các host. |
| **Set-membership xấp xỉ (Bloom Filter, double hashing)** | `datastructure/BloomFilter.java` | Khử trùng lặp URL đã crawl: `h_i(x) = h1(x) + i*h2(x) mod m` (kỹ thuật Kirsch & Mitzenmacher) sinh k hàm băm từ 2 hàm băm gốc (FNV-1a + polynomial rolling hash). |
| **Longest-prefix-match cho robots.txt** | `crawler/RobotsTxtParser.java` | Trong tập luật `Allow`/`Disallow` khớp tiền tố đường dẫn, luật có `path` DÀI NHẤT sẽ thắng (đúng chuẩn Robots Exclusion Protocol). |
| **Retry với giới hạn số lần** | `CrawlerService.fetchWithRetry()` | Fetch HTML qua Jsoup, thử lại tối đa 2 lần nếu lỗi mạng/timeout. |

## 2. Tách từ tiếng Việt (Tokenize)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **Longest Matching (tham lam, dựa từ điển song ngữ)** | `index/VietnameseTokenizer.java` | Với mỗi vị trí, thử ghép tối đa 4 tiếng liên tiếp, giảm dần độ dài, tra từ điển bigram (`vietnamese-bigrams.txt`); ghép dài nhất khớp được sẽ thắng, không khớp thì lùi về 1 tiếng. Đây là thuật toán tách từ tiếng Việt kinh điển (không dùng NLP model có sẵn). |
| **Chuẩn hoá Unicode NFC/NFD** | `VietnameseTokenizer.splitIntoSyllables/stripDiacritics` | NFC trước khi xử lý (tránh 2 dạng Unicode khác nhau cho cùng một từ có dấu), NFD + xoá combining mark `\p{M}` để sinh bản không dấu (riêng `đ`/`Đ` xử lý thủ công vì không phải tổ hợp base + dấu). |
| **Stopword filtering** | `VietnameseTokenizer.tokenize()` | Loại từ dừng (chỉ áp dụng cho token 1 tiếng) đọc từ `vietnamese-stopwords.txt`. |

## 3. Lập chỉ mục (Index)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **Xây dựng inverted index** | `index/InvertedIndex.java` | HashMap `term -> posting list`, đảm bảo bất biến posting list LUÔN sắp xếp tăng dần theo docId (thêm tài liệu theo đúng thứ tự docId tăng dần, chỉ append). |
| **Binary search trên posting list** | `InvertedIndex.getPositions()`, `TfIdfScorer.findTermFrequencyInDoc()` | Tra cứu vị trí/tần suất của một term trong một doc cụ thể: O(log n) thay vì quét tuyến tính, nhờ posting list đã sắp xếp. |
| **Dual-index có dấu/không dấu** | `InvertedIndex.addDocument()` | Mỗi token được index cả 2 lần (có dấu + không dấu) trong CÙNG một HashMap, cho phép tìm không dấu mà không cần cấu trúc riêng. |

## 4. Xử lý truy vấn (Query)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **Two-pointer merge (kiểu merge-sort) — intersect/union** | `query/PostingListMerger.java` | Giao/hợp 2 danh sách docId đã sắp xếp trong O(m+n), không cần HashSet trung gian. |
| **Sắp xếp shortest-first trước khi giao nhiều tập (intersectAll)** | `PostingListMerger.intersectAll()` | Sắp posting list theo độ dài tăng dần, giao tuần tự từ ngắn nhất -> kết quả trung gian nhỏ ngay từ đầu, giảm chi phí các bước sau. |
| **Phrase matching bằng vị trí liên tiếp** | `PostingListMerger.matchesPhrase()` | Với từng vị trí xuất hiện của từ đầu tiên trong cụm từ, kiểm tra các từ tiếp theo có nằm đúng vị trí liên tục (start+1, start+2, ...) hay không. |
| **Regex-based query parsing** | `query/QueryParser.java` | Tách câu truy vấn thành mustTerms (AND ngầm định) / phrases (`"..."`) / excludedTerms (`-từ`) bằng một pattern tìm cụm từ trong ngoặc kép, phần còn lại xử lý tiền tố `-`. |

## 5. Xếp hạng kết quả (Rank)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **TF-IDF + Cosine similarity** | `ranking/TfIdfScorer.java` | `tf = 1 + log10(termFrequency)` (log-normalized), `idf = log10(N/df)`, điểm = `dot(query,doc) / (||query|| * ||doc||)`, xấp xỉ `||doc|| ≈ sqrt(docLength)` (kiểu Lucene classic Similarity). |
| **BM25 (Okapi BM25)** | `ranking/BM25Scorer.java` | Baseline chuẩn công nghiệp: `Σ IDF(q)·f(q,D)(k1+1) / (f(q,D) + k1(1−b+b·\|D\|/avgdl))` với k1=1,2 và b=0,75. Hai ưu điểm so với TF-IDF: **bão hoà tần suất** (lặp từ khoá 50 lần gần như không hơn 20 lần) và **chuẩn hoá độ dài có tham số điều chỉnh**. IDF theo Robertson–Sparck Jones nên không bao giờ âm. |
| **PageRank (power iteration)** | `ranking/PageRankService.java` | `PR(j) = (1-d)/N + d*(sum_i PR(i)/outDegree(i) + danglingMass/N)`, d=0.85, lặp tới khi hội tụ (`L1 diff < 1e-6`) hoặc tối đa 100 vòng; nhân ma trận-vector qua `SparseMatrix.multiply` (O(nnz)/vòng); xử lý riêng dangling node (phân phối đều "khối lượng" PR cho toàn bộ N trang). |
| **Linear weighted scoring (kết hợp điểm)** | `ranking/ResultRanker.java` | `finalScore = alpha*tfidf + beta*pageRank + gamma*titleMatchBonus` (mặc định 0.6/0.3/0.1). |
| **Top-K selection bằng Min-Heap (không sort toàn bộ)** | `datastructure/MinHeap.topK()`, dùng trong `ResultRanker.rank()` và `Trie.getSuggestions()` | Duy trì heap kích thước k: O(n log k) thay vì sort O(n log n). |
| **Sliding window (cửa sổ trượt) sinh snippet** | `ResultRanker.buildSnippet()` | Trượt cửa sổ kích thước cố định (25 tiếng) qua bodyText, cập nhật O(1) số từ khớp khi trượt (trừ phần tử ra/thêm phần tử vào), chọn cửa sổ có nhiều từ khớp truy vấn nhất -> tổng O(n) thay vì O(n·windowSize). Chỉ chạy cho **top-N** tài liệu sống sót sau khi xếp hạng, không chạy cho mọi ứng viên. |

## 5b. Đánh giá chất lượng tìm kiếm (Information Retrieval)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **Precision@k, Recall@k, F1@k** | `eval/EvaluationMetrics.java` | Độ đo nhị phân. Mẫu số của P@k là `k` chứ không phải số kết quả trả về, theo quy ước TREC — trả về quá ít kết quả tự nó là khiếm khuyết và phải bị phạt. |
| **Average Precision / MAP** | `eval/EvaluationMetrics.java` | Trung bình P@i tại mọi vị trí có tài liệu liên quan, chia cho **tổng** số tài liệu liên quan (nên bỏ sót vẫn bị phạt). Nhạy với thứ tự, khác với P@k. |
| **nDCG@k** | `eval/EvaluationMetrics.java` | `DCG = Σ (2^rel − 1)/log2(i+1)`, chuẩn hoá bằng IDCG của thứ tự lý tưởng. Dùng độ lợi **hàm mũ** để nhấn mạnh tài liệu "rất liên quan" gấp 3 lần "liên quan" (thay vì 2:1 nếu dùng tuyến tính). |
| **MRR / Success@k** | `eval/EvaluationMetrics.java` | Nghịch đảo thứ hạng của kết quả đúng đầu tiên. Độ đo phù hợp nhất cho known-item search. |
| **Known-item query generation** | `eval/KnownItemQueryGenerator.java` | Sinh ground truth **tự động, khách quan**: chọn tài liệu, lấy các term TF-IDF cao nhất **có df trong khoảng [3, 10% corpus]** làm truy vấn, đáp án đúng chính là tài liệu đó. Lọc df dưới để tránh truy vấn tầm thường (df=1 thì hệ thống nào cũng đạt MRR 1,0), lọc trên để loại term không mang thông tin. |
| **TREC pooling** | `eval/PoolBuilder.java` | Gộp top-k của **nhiều cấu hình xếp hạng** thành pool cần gán nhãn, thay vì gán nhãn toàn bộ corpus. Giảm khối lượng từ hàng trăm nghìn xuống vài trăm lượt đánh giá mà thứ tự xếp hạng giữa các hệ thống hầu như không đổi. |

## 6. Gợi ý & phục vụ (Suggest / Serve)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **Trie prefix search + DFS thu thập + top-K** | `datastructure/Trie.java` | `getSuggestions(prefix, limit)`: tìm node của prefix O(L), DFS duyệt toàn bộ từ dưới cây con O(m), rồi `MinHeap.topK` lấy top theo frequency O(m log k). |
| **LRU eviction (Doubly Linked List + HashMap)** | `datastructure/LRUCache.java` | Cache toàn bộ `SearchResponse` theo key `query+page+size`; `get`/`put` O(1) nhờ HashMap tra cứu + danh sách liên kết 2 chiều di chuyển node O(1). |

## 7. Phía trình duyệt (Electron/TypeScript)

| Thuật toán | Ở đâu | Vai trò |
|---|---|---|
| **Stack (LIFO) cho back/forward** | `browser-app/.../lib/Stack.ts`, dùng trong `historyStore.ts` | Quản lý lịch sử duyệt web độc lập với `webContents.canGoBack()/goBack()` của Electron. |
| **Trie prefix search (TypeScript)** | `browser-app/.../lib/BookmarkTrie.ts` | Tìm bookmark theo tiền tố tiêu đề, song song với `Trie.java` ở backend. |

## Tóm tắt luồng thuật toán cho MỘT truy vấn tìm kiếm

```
"công nghệ" từ người dùng
  -> QueryParser (regex parse: mustTerms/phrases/excludedTerms, tokenize
     bằng thuật toán Longest Matching giống lúc index)
  -> InvertedIndex.getPostings() cho từng term (O(1) HashMap)
  -> PostingListMerger.intersectAll() (two-pointer, shortest-first) -> candidate docIds
  -> (nếu có "cụm từ") PostingListMerger.matchesPhrase() lọc tiếp theo vị trí liên tiếp
  -> loại bỏ excludedTerms
  -> TfIdfScorer.score() cho từng candidate (binary search + cosine similarity)
  -> kết hợp với PageRankService (đã tính sẵn bằng power iteration) theo
     trọng số alpha/beta/gamma trong ResultRanker
  -> MinHeap.topK() lấy top-N, không sort toàn bộ candidate
  -> ResultRanker.buildSnippet() (sliding window) sinh đoạn trích + highlight
  -> LRUCache lưu lại SearchResponse cho lần gọi sau với cùng key
```

Xem thêm:
- `docs/DSA-REPORT.md` — bảng cấu trúc dữ liệu + Big-O đầy đủ, số liệu đo
  hiệu năng thực tế (thời gian truy vấn, cache hit rate, số vòng lặp
  PageRank...).
- `docs/ARCHITECTURE.md` — sơ đồ kiến trúc và sequence diagram cho một
  request tìm kiếm đầy đủ.
