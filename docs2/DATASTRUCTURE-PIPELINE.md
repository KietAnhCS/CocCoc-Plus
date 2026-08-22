Bản rút gọn dạng cây

Sáu cấu trúc dữ liệu tự cài, và chỗ dùng thật của từng cái:

```
com.vnsearch.datastructure
├─ MinHeap        → ResultRanker top-K, UsageAnalyticsService.topQueries/topLinks/topHosts,
│                    CorpusStats.topHosts, Trie.getSuggestions, BackQueues (chọn hàng đợi)
├─ LRUCache       → SearchEngineFacade.searchCache, DnsResolver
├─ Trie           → SuggestionService (gợi ý theo tiền tố)
├─ SyllableTrie   → VietnameseWordDictionary + MaxWeightSegmenter (tách từ)
├─ BloomFilter    → UrlSeenFilter (crawler), CorpusStats.countDistinctTargets
└─ SparseMatrix   → PageRankService (ma trận liên kết vào)
```

MinHeap — lấy K phần tử lớn nhất mà không sắp xếp cả tập:

```
MinHeap.topK(items, k, comparator)                          O(n·log k), bộ nhớ O(k)
├─ k ≤ 0 hoặc items rỗng → danh sách rỗng
├─ gom k phần tử ĐẦU vào seed rồi heapify MỘT lần            O(k), không phải O(k·log k)
├─ ∀ phần tử còn lại:
│  └─ cmp(item, heap.peek()) > 0 → extractMin + insert       2·log k
│     ↳ dấu ">" CHẶT: phần tử BẰNG ngưỡng bị bỏ qua, tiết kiệm một cặp thao tác
│       mà kết quả vẫn hợp lệ (đề bài chỉ đòi "k lớn nhất", không đòi phần tử nào)
├─ items.size() < k → heap vẫn null → heapify phần seed đã gom
└─ lấy ra rồi ĐẢO NGƯỢC → giảm dần
   ↳ vì sao không sort: 30 000 ứng viên · log 30 000 ≈ 450 000 phép so, trong khi
     top-10 chỉ tốn ≈ 30 000 · log 10 ≈ 100 000 — và không cần cấp phát mảng sắp xếp
```

LRUCache — bảng băm + danh sách liên kết đôi:

```
LRUCache(capacity)   capacity ≤ 0 → IllegalArgumentException
├─ map: HashMap<K, Node>            → tra cứu O(1)
├─ head ↔ tail: hai nút CANH BIÊN giả  → thêm/xoá không cần if cho đầu/cuối danh sách
├─ get(key)
│  ├─ map.get → null → trả null
│  └─ moveToFront(node) rồi trả giá trị
│     ↳ dùng WRITE lock dù mang tên "get": mỗi lần đọc đều SỬA thứ tự danh sách,
│       nên đây không phải thao tác thuần đọc
├─ put(key, value)
│  ├─ đã có → cập nhật giá trị + moveToFront
│  └─ chưa có → addToFront ; map.size() > capacity → xoá tail.prev khỏi CẢ hai cấu trúc
└─ size / containsKey → read lock (thuần đọc thật)
   dùng ở: searchCache (app.search.cache-size = 200, tạo LẠI mỗi lần chỉ mục đổi)
           DnsResolver (nhớ kết quả phân giải tên miền trong phiên crawl)
```

Trie — gợi ý theo tiền tố:

```
Trie.insert(key, display, frequency)
├─ normalize(key) → chữ thường, gộp khoảng trắng
└─ mỗi ký tự một nút; nút cuối giữ isEndOfWord + display + frequency
   ↳ mỗi gợi ý được chèn HAI lần: khoá CÓ dấu và khoá KHÔNG dấu, cùng một `display`
     → gõ "ha noi" vẫn ra "hà nội"

Trie.getSuggestions(prefix, limit)
├─ read lock THẬT (không sửa nút nào) → nhiều request /api/suggest chạy song song
├─ findNode(prefix) → null → rỗng
├─ collectWords: DFS toàn bộ cây con, gom (display, frequency)
├─ gộp trùng theo `display`, giữ frequency LỚN NHẤT
│  ↳ vì chèn hai lần, một tiền tố ngắn có thể chạm CẢ hai nút và làm gợi ý bị lặp
└─ MinHeap.topK(deduplicated, limit, theo frequency)
   nguồn dữ liệu (SuggestionService.rebuild, chạy lại mỗi lần refreshDerivedState):
   ├─ trie.clear() TRƯỚC — chỉ insert thêm thì tiêu đề của corpus cũ ở lại vĩnh viễn
   ├─ chỉ tiêu đề TIẾNG VIỆT (LanguageDetector.looksVietnamese)
   ├─ từ ghép (có "_") và bigram của tiêu đề, đếm tần suất
   ├─ bỏ mục dưới MIN_SUGGESTION_FREQUENCY (lọc nhiễu)
   └─ + truy vấn THẬT đã học, chỉ học từ truy vấn CÓ kết quả
```

SyllableTrie — trie theo ÂM TIẾT, lưu trên mảng phẳng:

```
đơn vị cạnh không phải ký tự mà là một ÂM TIẾT
├─ intern(syllable) → id bắt đầu từ 1     ← id 0 dành cho "không có", nên 0 là ô trống hợp lệ
├─ bảng cạnh mở địa chỉ tuyến tính:
│  ├─ edgeKey[slot] = (parent << 32) | syllableId       ← một long gói cả hai
│  ├─ đụng độ → slot = (slot + 1) & edgeMask            ← dò tuyến tính
│  └─ edgeCount > MAX_LOAD_FACTOR 0.55 × bảng → growEdgeTable (nhân đôi, băm lại)
├─ weight[node]: NOT_A_WORD 0.0 nghĩa là nút không kết thúc từ nào
├─ insert: cùng một từ đến từ CẢ HAI tệp từ điển → giữ trọng số LỚN HƠN
├─ child(node, syllableId) → NONE (−1) nếu không có cạnh
└─ ↳ vì sao không dùng HashMap<String, Node>: MaxWeightSegmenter đi trie MỘT lượt phủ
     cả bốn độ dài 1..4 và CẮT NHÁNH ngay khi gặp NONE. HashMap không nói được
     "không từ nào bắt đầu bằng tiền tố này" — nó buộc phải thử từng độ dài một.
     Mảng phẳng còn tránh hàng trăm nghìn object Node và con trỏ.
```

BloomFilter — kiểm tra thành viên xác suất:

```
BloomFilter(expectedItems n, falsePositiveRate p)
├─ m = ceil(−n·ln p / (ln2)²)   , tối thiểu 64 bit
├─ k = round(m/n · ln2)         , tối thiểu 1
├─ bits = long[(m + 63) / 64]
├─ add / mightContain: k chỉ số sinh bằng double hashing  idx = h1 + i·h2
│  ↳ chỉ tính HAI hàm băm thật rồi phối hợp tuyến tính, thay vì chạy k hàm băm độc lập
└─ ngữ nghĩa: "có thể có" hoặc "CHẮC CHẮN không có"
   dùng ở:
   ├─ UrlSeenFilter — crawl 8 trang → BloomFilter(200 000, 1%) = 1 917 012 bit, 7 hàm băm
   │  ↳ dương tính giả = bỏ sót một URL chưa từng thăm. Chấp nhận được: đổi lấy việc
   │    KHÔNG giữ hàng triệu chuỗi URL trong RAM
   └─ CorpusStats.countDistinctTargets — đếm XẤP XỈ số đích liên kết phân biệt
      ↳ sức chứa = docs × 64, trần 5 000 000, FPR 1% — con số chỉ để hiển thị
```

SparseMatrix — hai chế độ, đổi bằng freeze():

```
trước freeze()  : adjacency list  List<List<Entry>>   → set() được, nhân chậm
freeze()
├─ đếm nnz, cấp csrValues[], csrColIdx[], csrRowPtr[rows + 1]
├─ đổ từng hàng vào mảng phẳng theo đúng thứ tự
├─ csrRowPtr[rows] = nnz                ← CANH BIÊN: vòng lặp khỏi cần if cho hàng cuối
└─ rowEntries = null                    ← nhường toàn bộ Entry cho bộ gom rác
sau freeze()   : CSR                                  → set() ném IllegalStateException
                                                        multiply đi trên mảng liền kề
dùng ở: PageRankService — dựng ma trận "liên kết VÀO", freeze, rồi nhân ~vài chục vòng lặp
        ↳ ma trận n×n với n = 30 017 mà lưu dày sẽ là 900 triệu ô double ≈ 7,2 GB;
          dạng thưa chỉ giữ đúng số cạnh có thật (log ghi ra nnz và độ thưa %)
```

Một mẫu lặp lại ở cả sáu:

```
Đổi ĐỘ CHÍNH XÁC hoặc TÍNH LINH HOẠT lấy BỘ NHỚ và TỐC ĐỘ, một cách có chủ đích:
├─ MinHeap      bỏ thứ tự của phần đuôi        → O(n log k) thay vì O(n log n)
├─ LRUCache     bỏ mục cũ nhất                 → bộ nhớ chặn cứng ở capacity
├─ SyllableTrie bỏ tiện lợi của HashMap        → cắt nhánh được + hết object Node
├─ BloomFilter  chấp nhận dương tính giả 1%    → vài trăm KB thay vì hàng trăm MB
├─ SparseMatrix bỏ khả năng sửa sau freeze     → nhân nhanh trên mảng liền kề
└─ Trie         chấp nhận chèn hai lần         → tìm được cả khi gõ không dấu
```
