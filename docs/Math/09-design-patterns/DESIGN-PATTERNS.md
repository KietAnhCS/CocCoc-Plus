# 10 Design Pattern trong VnSearch

**Phạm vi:** 74 lớp Java (9.286 dòng) + 8 module TypeScript. **233 test xanh.**

Mười mẫu dưới đây đều thoả ba điều kiện: **giải một vấn đề thật đã đo được**, **được dùng trong đường chạy chính** (không phải code chết), và **động cơ được viết trong Javadoc** chứ không chỉ trong tài liệu.

> Chấm điểm chi tiết: [CHAM-DIEM.md](CHAM-DIEM.md) · Mục lục: [README.md](../README.md)

---

## Bảng tổng hợp

| # | Pattern | File chính | Vấn đề đo được mà nó giải |
|---|---|---|---|
| 1 | **Strategy** | `RelevanceScorer`, `Tokenizer`, `SearchIndex`, `DocumentStore` | Ablation khoa học: BM25 hơn TF-IDF **5,3 % MRR** |
| 2 | **Factory** | `ScorerFactory` | BM25 tốt hơn nhưng **không ai dùng được** |
| 3 | **Decorator** | `PageRankBoostScorer`, `TitleBoostScorer` | PageRank chỉ đóng góp **0,1 %** dù trọng số 30 % |
| 4 | **Composite** | `QueryNode` + 5 nút | Không có OR; `union` là **code chết** |
| 5 | **Chain of Responsibility** | `CandidateFilter` + 2 lọc | 3 tầng lọc chôn cứng trong hàm 104 dòng |
| 6 | **State** | `CrawlStatus` | `status` là `String` — gõ sai không bị bắt |
| 7 | **Observer** | `CrawlListener` | `printf` chôn trong worker, test bị spam |
| 8 | **Builder** | `CrawlConfig` | Sửa được giữa phiên crawl, không kiểm tra |
| 9 | **Iterator/Cursor** | `PostingCursor` | Autoboxing **64 KB/lần**; 4005 bước → **48 bước** |
| 10 | **Flyweight** | `TermDictionary` | **7 triệu** `String` cho 136.768 giá trị |

**Mẫu bổ trợ:** Facade (`SearchEngineFacade`), Adapter (`HtmlExtractor`, `Stack<T>`), Repository (`DocumentRepository`), Value Object (9 `record`), Cache-Aside (`LRUCache`), Producer–Consumer (crawler), DI (constructor injection).

---

## 1. Strategy — bốn trục hoán đổi được

```java
public interface RelevanceScorer {
    double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index);
    String name();
}
```

**Động cơ khoa học, không phải "dùng pattern cho có".** Javadoc nói thẳng:

> *"Đây là điều kiện CẦN để làm thí nghiệm ablation: chạy CÙNG một bộ truy vấn, CÙNG một chỉ mục, chỉ thay đúng một mô hình tính điểm. **Nếu không tách được ra sau một giao diện thì mọi so sánh đều lẫn thêm biến số khác và mất giá trị khoa học.**"*

Kết quả nó mở khoá (200 truy vấn known-item):

| Cấu hình | MRR | Success@1 |
|---|---|---|
| TF-IDF thuần | 0,8537 | 78,0 % |
| **BM25 thuần** | **0,8989** | **85,0 %** |

**Bốn trục Strategy, không chỉ một:**

| Interface | Cho phép thay gì | Vì sao cần |
|---|---|---|
| `RelevanceScorer` | TF-IDF ↔ BM25 ↔ chuỗi Decorator | Ablation mô hình |
| `Tokenizer` | Bộ tách từ | **Xoá bất đối xứng**: trước đây đo được scorer nào tốt hơn nhưng không đo được tokenizer nào tốt hơn, dù tokenizer là *trần chất lượng* của cả hệ thống |
| `SearchIndex` | Chỉ mục trong RAM ↔ trên đĩa ↔ giả lập | 4 lớp dùng nó không còn phụ thuộc lớp cụ thể |
| `DocumentStore` | PostgreSQL ↔ JSON ↔ seed mẫu | Chuỗi dự phòng thành **dữ liệu**, không phải `else if` |

Trục cuối đáng nói riêng:

```java
// Trước: cấu trúc điều khiển
if (postgresEnabled && loadFromPostgres()) { ... }
else if (Files.exists(indexDataPath))   { ... }
else if (Files.exists(crawledDataPath)) { ... }
else if (Files.exists(seedDataPath))    { ... }

// Sau: dữ liệu
for (DocumentStore store : buildStoreChain()) {
    if (!store.isAvailable()) continue;
    lastCrawledDocuments = store.loadAll();
    index = indexBuilder.build(lastCrawledDocuments);
    log.info("Da nap corpus tu {}", store.describe());
    return;
}
```

Thêm nguồn thứ năm = thêm **một lớp**, không sửa `init()`.

---

## 2. Factory — biến kết quả đo thành tính năng

**Vấn đề.** `RelevanceScorer` tồn tại và hoạt động tốt, nhưng Facade **chôn cứng** cài đặt:

```java
private final TfIdfScorer tfIdfScorer = new TfIdfScorer();   // lớp CỤ THỂ
```

Nghĩa là: đo được BM25 tốt hơn **5,3 % MRR**, nhưng **người dùng thật không bao giờ nhận được nó** mà không sửa mã và biên dịch lại. Strategy chỉ được bộ đánh giá khai thác; sản phẩm thì không.

```java
@Component
public class ScorerFactory {
    @Value("${app.ranking.scorer:tfidf}") private String scorerType;
    @Value("${app.ranking.beta:0.30}")    private double pageRankWeight;
    @Value("${app.ranking.gamma:0.10}")   private double titleWeight;

    public RelevanceScorer createBase() {
        return switch (scorerType.trim().toLowerCase(Locale.ROOT)) {
            case "bm25"            -> new BM25Scorer(k1, b);
            case "tfidf", "tf-idf" -> new TfIdfScorer();
            default -> throw new IllegalArgumentException(
                    "app.ranking.scorer phai la 'tfidf' hoac 'bm25', nhan duoc: " + scorerType);
        };
    }

    public RelevanceScorer create(Map<Integer, Double> pageRankScores) {
        RelevanceScorer scorer = createBase();
        if (pageRankWeight > 0 && !pageRankScores.isEmpty()) {
            scorer = new PageRankBoostScorer(scorer, pageRankScores, pageRankWeight);
        }
        if (titleWeight > 0) {
            scorer = new TitleBoostScorer(scorer, titleWeight);
        }
        return scorer;
    }
}
```

Nay đổi mô hình xếp hạng là **một dòng properties**:

```properties
app.ranking.scorer=bm25
```

Trọng số bằng 0 thì lớp bọc tương ứng **bị bỏ hẳn** — không trả chi phí cho tín hiệu đã tắt.

---

## 3. Decorator — sửa lỗi thang đo 1000×

**Đây là pattern sửa được một lỗi thật, nghiêm trọng nhất của hệ thống cũ.**

Công thức cũ chôn cứng trong `ResultRanker`:

```java
double finalScore = alpha * relevance + beta * pageRank + gamma * titleBonus;
```

Số đo trên corpus 5.011 trang:

| Thành phần | Trung bình | Sau khi nhân trọng số |
|---|---|---|
| TF-IDF cosine | 0,177687 | 0,106612 ($\alpha = 0{,}6$) |
| **PageRank** | **0,00035388** | **0,00010616** ($\beta = 0{,}3$) |

$$\frac{\beta\,\overline{\text{PR}}}{\alpha\,\overline{\text{TF-IDF}}} = \frac{0{,}00010616}{0{,}106612} \approx \mathbf{0{,}1\,\%}$$

Bằng chứng thực nghiệm: quét $\beta$ từ 0,05 tới 0,80 (**gấp 16 lần**) chỉ làm MRR đổi **0,0040**.

**Đây không phải "chọn $\beta$ chưa tối ưu".** PageRank là một **phân phối xác suất**: $\sum\text{PR} = 1$, nên với $N = 5011$, trung bình *buộc phải* là $1/5011$. Và nó **co lại** khi corpus lớn hơn — với 1 triệu trang, đóng góp giảm thêm 200 lần. Cộng một độ tương tự với một phân phối xác suất là phép toán không có ý nghĩa; bất kỳ $\beta$ nào cũng không sửa được.

**Lời giải — nhân, không cộng:**

```java
public double score(Map<String, Integer> qtf, int docId, SearchIndex index) {
    double base = inner.score(qtf, docId, index);
    if (base == 0.0 || weight == 0.0) return base;
    double pageRank = pageRankScores.getOrDefault(docId, minPageRank);
    double normalized = Math.log1p(pageRank / minPageRank) / logRange;  // ∈ [0,1]
    return base * (1 + weight * normalized);
}
```

$$\text{final} = \text{base} \times \bigl(1 + w \cdot \hat{p}\bigr), \qquad \hat{p} = \frac{\log(1 + p/p_{\min})}{\log(1 + p_{\max}/p_{\min})} \in [0,1]$$

Hai lý do:

1. **Logarit nén dải động** — PageRank trải trên nhiều bậc độ lớn; $\log$ biến nó thành đại lượng cộng được, và chuẩn hoá về $[0,1]$ làm `weight` trở thành tỷ lệ đóng góp **thật**.
2. **Phép nhân bất biến với thang đo của scorer cơ sở** — đổi TF-IDF sang BM25 (thang 0,18 so với 12,1) **không cần chỉnh lại trọng số**.

Lý do (2) giải thích luôn nghịch lý trong bảng đánh giá cũ: *"BM25 + PR + title" (0,9089) thua "TF-IDF + PR + title" (0,9229)* — vì bộ trọng số được tinh chỉnh cho thang TF-IDF.

**Có test chứng minh đúng tính chất đó:**

```java
@Test
void pageRankBoostIsInvariantToBaseScorerScale() {
    RelevanceScorer tfidf = new PageRankBoostScorer(new TfIdfScorer(), pageRank, 0.5);
    RelevanceScorer bm25  = new PageRankBoostScorer(new BM25Scorer(),  pageRank, 0.5);

    double tfidfRatio = tfidf.score(query, 1, index) / tfidf.score(query, 0, index);
    double bm25Ratio  = bm25.score(query, 1, index)  / bm25.score(query, 0, index);

    assertEquals(tfidfRatio, bm25Ratio, 1e-9,
            "Ty le tang do PageRank phai GIONG NHAU du thang diem co so khac han");
}
```

**Lắp ghép:**

```java
RelevanceScorer scorer = new TitleBoostScorer(
        new PageRankBoostScorer(new BM25Scorer(), pageRankScores, 0.30), 0.10);

scorer.name();  // "BM25(k1=1.2,b=0.75) + PR x0.30 + title x0.10"
```

`name()` tự ghép thành nhãn mô tả đầy đủ — dùng trực tiếp làm nhãn trong bảng đánh giá.

---

## 4. Composite — cây biểu thức truy vấn

**Vấn đề.** `ParsedQuery` là ba danh sách phẳng, mã hoá sẵn giả định *"mọi mustTerm nối bằng AND"*. Không biểu diễn được `(máy tính OR laptop) AND giá rẻ`.

Và: **`PostingListMerger.union` đã tồn tại, đã có test, nhưng không có đường nào gọi tới nó** — một cấu trúc bị bỏ phí hoàn toàn.

```java
public sealed interface QueryNode
        permits TermNode, PhraseNode, AndNode, OrNode, NotNode {
    List<Integer> evaluate(SearchIndex index);
    int estimatedSize(SearchIndex index);
    String describe();
}
```

```
(máy tính OR laptop) AND giá rẻ

              AndNode
             ╱       ╲
        OrNode      TermNode(giá_rẻ)
        ╱     ╲
  TermNode   TermNode
 (máy_tính)  (laptop)
```

**`AndNode` tự áp shortest-first**, dựa trên $\lvert A \cap B\rvert \le \min(\lvert A\rvert, \lvert B\rvert)$:

```java
positives.sort(Comparator.comparingInt(node -> node.estimatedSize(index)));
List<Integer> accumulator = positives.get(0).evaluate(index);
for (int i = 1; i < positives.size(); i++) {
    if (accumulator.isEmpty()) return List.of();   // ∅ là phần tử HẤP THỤ
    accumulator = PostingListMerger.intersect(accumulator, positives.get(i).evaluate(index));
}
```

`estimatedSize` **không cần đánh giá thật** — với `TermNode` đó chỉ là một phép tra document frequency $O(1)$.

**`NotNode` được xử lý đúng về mặt ngữ nghĩa.** Phủ định thuần tuý cho ra *tập bù* — với `NOT quảng_cáo` trên 5.011 tài liệu là gần 5.000 kết quả vô nghĩa. Nên `evaluate()` ném ngoại lệ có thông điệp rõ, còn `evaluateAgainst()` mới là đường đúng:

```java
public List<Integer> evaluateAgainst(List<Integer> candidates, SearchIndex index) {
    List<Integer> excluded = inner.evaluate(index);
    // Vì cả hai sắp xếp tăng dần, con trỏ j chỉ tiến MỘT chiều: O(m+n).
    int j = 0;
    for (int candidate : candidates) {
        while (j < excluded.size() && excluded.get(j) < candidate) j++;
        if (j >= excluded.size() || excluded.get(j) != candidate) result.add(candidate);
    }
    return result;
}
```

**`sealed` + `record`** cho `switch` có kiểm tra đầy đủ nhánh — thêm loại nút mới thì trình biên dịch nhắc mọi chỗ cần sửa.

**Tính năng mới cho người dùng:** `laptop OR máy tính`.

---

## 5. Chain of Responsibility — và ranh giới với Composite

**Vấn đề.** `CandidateResolver.resolve` có ba tầng lọc chôn cứng trong hàm 104 dòng. Thêm bộ lọc = sửa thân hàm.

```java
public interface CandidateFilter {
    List<Integer> apply(List<Integer> candidates, FilterContext context);
    String name();
    default boolean isApplicable(FilterContext context) { return true; }

    record FilterContext(SearchIndex index, QueryParser.ParsedQuery parsed) { }
}
```

```java
private static final List<CandidateFilter> FILTERS = List.of(
        new DomainFilter(),
        new MaxCandidatesFilter());
// Thêm bộ lọc = thêm MỘT dòng ở đây, không sửa resolve().

for (CandidateFilter filter : FILTERS) {
    if (candidates.isEmpty()) break;              // ∅ là phần tử hấp thụ
    if (!filter.isApplicable(context)) continue;
    candidates = filter.apply(candidates, context);
}
```

### Ranh giới với Composite — không tuỳ tiện

Đây là điểm thiết kế đáng chú ý nhất: **hai pattern làm hai việc khác hẳn nhau, phân công theo một nguyên tắc rõ ràng.**

| | **Composite** (`QueryNode`) | **Chain of Responsibility** (`CandidateFilter`) |
|---|---|---|
| Lo việc gì | Truy hồi **boolean**: AND, OR, NOT, term, cụm từ | Ràng buộc **sau truy hồi** |
| Làm việc trên | Posting list | Siêu dữ liệu tài liệu |
| Ví dụ | `máy tính OR laptop` | `site:vnexpress.net` |

> **Nguyên tắc phân công:** một ràng buộc **có posting list** thì thuộc về cây; một ràng buộc **trên siêu dữ liệu** thì thuộc về đường ống lọc.

`site:` không phải một term — nó không có posting list nào. Đưa nó vào cây sẽ buộc phải dựng một chỉ mục phụ `host → docIds`; ở tầng lọc, với vài chục ứng viên, kiểm tra trực tiếp là đủ.

**`name()` không thừa:** nó cho phép bọc timer quanh `apply` để in bảng *"tầng nào loại bao nhiêu ứng viên, tốn bao nhiêu ms"* — đúng tinh thần đo đạc của dự án.

**Tính năng mới:** `công nghệ site:vnexpress.net`.

---

## 6. State — enum thay `String`

**Vấn đề cũ**, bốn lỗi im lặng:

```java
volatile String status = "STARTED";
job.status = "RUNNING";
job.status = "DONE";
```

1. `job.status = "DONEE"` biên dịch bình thường; UI đọc `"DONE"` không bao giờ khớp.
2. Không gì ngăn `"DONE"` → `"RUNNING"`.
3. Muốn biết có những trạng thái nào phải grep cả codebase.
4. `switch` trên `String` không có kiểm tra đầy đủ nhánh.

```java
public enum CrawlStatus {
    STARTED { public boolean canTransitionTo(CrawlStatus next) {
        return next == RUNNING || next == FAILED; } },
    RUNNING { public boolean canTransitionTo(CrawlStatus next) {
        return next == DONE || next == FAILED; } },
    DONE    { public boolean canTransitionTo(CrawlStatus next) { return false; } },
    FAILED  { public boolean canTransitionTo(CrawlStatus next) { return false; } };

    public abstract boolean canTransitionTo(CrawlStatus next);
    public boolean isTerminal() { return this == DONE || this == FAILED; }
}
```

```
  STARTED ──→ RUNNING ──→ DONE
     │           │
     └───────────┴──────→ FAILED
```

```java
synchronized void transitionTo(CrawlStatus next) {
    if (!status.canTransitionTo(next)) {
        throw new IllegalStateException("Khong the chuyen tu " + status + " sang " + next);
    }
    status = next;
}
```

**`isTerminal()`** cho UI biết khi nào ngừng hỏi lại — trước đây phải so chuỗi với `"DONE"` và `"FAILED"` rải rác nhiều nơi.

**7 test riêng**, gồm một test bắt lỗi tinh vi: *không trạng thái nào được chuyển về chính nó*.

---

## 7. Observer — tách quan sát khỏi thực thi

**Vấn đề.** Logic in tiến độ chôn thẳng trong vòng lặp worker → không tắt được khi test, không đẩy WebSocket được, không ghi file được, không đo được (chỉ có chuỗi, không có số liệu).

```java
public interface CrawlListener {
    default void onPageCrawled(CrawlEvent event) { }
    default void onError(String url, Exception error) { }
    default void onFinished(int totalPages, long elapsedMs) { }

    record CrawlEvent(int pageNumber, int maxPages, String url, int depth,
                       int outlinks, int frontierSize, int domainCount) { }
}
```

```java
private final List<CrawlListener> listeners = new CopyOnWriteArrayList<>();

private void notifyPageCrawled(CrawlListener.CrawlEvent event) {
    for (CrawlListener listener : listeners) {
        try {
            listener.onPageCrawled(event);
        } catch (Exception e) {
            // Một listener hỏng KHÔNG được làm chết cả phiên crawl.
            log.warn("Listener {} nem ngoai le", listener.getClass().getSimpleName(), e);
        }
    }
}
```

Hai chi tiết đúng: **`CopyOnWriteArrayList`** (đọc từ nhiều worker, hiếm khi ghi — đúng ca sử dụng của cấu trúc này) và **`try/catch` quanh mỗi listener**.

`CrawlEvent` là **số liệu có cấu trúc**, không phải dòng log — tổng hợp được, đo được.

---

## 8. Builder — cấu hình bất biến, kiểm tra tập trung

**Vấn đề cũ:**

```java
CrawlConfig cfg = new CrawlConfig().maxPages(5000);
crawler.crawl(seeds, cfg);
cfg.maxPages = -1;        // sửa GIỮA phiên crawl, không ai chặn
```

Và không có kiểm tra nào: `threadCount = 0` → `newFixedThreadPool(0)` ném ngoại lệ khó hiểu; `progressEveryN = 0` → `count % 0` ném `ArithmeticException` **ở giữa vòng lặp worker**, một chỗ hoàn toàn không liên quan tới nguyên nhân.

```java
public CrawlConfig build() {
    if (maxPages <= 0)   throw new IllegalArgumentException("maxPages phai > 0, nhan duoc: " + maxPages);
    if (maxDepth < 0)    throw new IllegalArgumentException("maxDepth phai >= 0, nhan duoc: " + maxDepth);
    if (threadCount <= 0) throw new IllegalArgumentException("threadCount phai > 0, nhan duoc: " + threadCount);
    if (maxDurationMinutes <= 0) throw new IllegalArgumentException(...);
    if (progressEveryN <= 0) throw new IllegalArgumentException(...);
    return new CrawlConfig(this);
}
```

```java
this.allowedDomains = Set.copyOf(builder.allowedDomains);   // bản sao BẤT BIẾN
```

Cấu hình sai bị bắt **trước khi crawl bắt đầu**, không phải sau 30 phút. Và bất biến cho lợi ích về đồng thời: 12 worker cùng đọc mà không cần `volatile`.

**10 test**, gồm hai test riêng cho bản sao phòng thủ.

---

## 9. Iterator/Cursor — skip pointer bằng galloping search

**Hai vấn đề đo được.**

**(a) Autoboxing.** `docIdsOf` vật chất hoá posting list thành `List<Integer>`; mỗi `docId` thành object 16 byte thay vì 4. Với list 4.000 mục: **64 KB rác GC** mỗi lần gọi, và gọi $k$ lần cho truy vấn $k$ term.

**(b) Không nhảy cóc được.** Giao list 5 phần tử với list 4.000 phần tử, two-pointer thuần vẫn duyệt gần hết 4.000.

```java
public interface PostingCursor {
    int NO_MORE = Integer.MAX_VALUE;
    int docId();
    boolean next();
    boolean skipTo(int targetDocId);   // ← O(log d) galloping
}
```

**Galloping search hai pha:**

```java
// Pha 1: nhảy theo cấp số nhân 1, 2, 4, 8, ... cho tới khi vượt mục tiêu
int step = 1, low = index, high = index + step;
while (high < n && postings.get(high).docId() < targetDocId) {
    low = high;
    step <<= 1;
    high = index + step;
}
if (high >= n) high = n - 1;

// Pha 2: binary search trong đoạn vừa khoanh
int lo = low, hi = high;
while (lo < hi) {
    int mid = (lo + hi) >>> 1;
    if (postings.get(mid).docId() < targetDocId) lo = mid + 1; else hi = mid;
}
```

$$O(m + n) = 4005 \text{ bước} \quad\longrightarrow\quad O\!\left(m\log\frac{n}{m}\right) \approx 48 \text{ bước}$$

**Điểm mạnh so với binary search thuần:** chi phí phụ thuộc **khoảng cách thật** $d$, **không phụ thuộc kích thước mảng** $n$. Khi hai posting list có nhiều phần tử chung, $d$ nhỏ nên galloping gần như miễn phí.

**Test đối chiếu với quét tuyến tính ở MỌI vị trí** — nguồn sự thật đơn giản:

```java
@Test
void gallopingMatchesLinearScanOnEveryPosition() {
    int[] docIds = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
    for (int target = 0; target <= 1100; target++) {
        PostingCursor cursor = PostingCursor.of(postings(docIds));
        boolean found = cursor.skipTo(target);
        int expected = /* quét tuyến tính */;
        assertEquals(expected, cursor.docId(), "target=" + target);
    }
}
```

---

## 10. Flyweight — kho chuỗi dùng chung

**Vấn đề.** Chỉ mục có 136.768 term phân biệt, nhưng tokenizer tạo chuỗi **mới** mỗi lần gặp:

```java
term = String.join("_", Arrays.copyOfRange(syllables, i, i + matchedLen));
```

Với 5.011 tài liệu × ~1.400 tiếng ≈ **7 triệu** object `String` được cấp phát cho **136.768** giá trị phân biệt. Mỗi `String` tốn $\approx 44 + L$ byte.

```java
public String intern(String term) {
    String existing = pool.putIfAbsent(term, term);
    return existing != null ? existing : term;
}
```

`putIfAbsent` làm cả hai việc trong **một** lần băm — `containsKey` rồi `put` sẽ băm hai lần.

```java
// InvertedIndex.addDocument
String term = termDictionary.intern(token.term());
positionsByTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(token.position());
```

**Vì sao không dùng `String.intern()` của JDK:** nó dùng bảng chuỗi nội bộ của JVM, kích thước cấu hình cứng và **không giải phóng được**. Pool tự quản lý thì kiểm soát được vòng đời và **đo được** (`size()`, `estimatedBytes()`).

---

## Bảy mẫu bổ trợ

| Pattern | Nơi dùng | Ghi chú |
|---|---|---|
| **Facade** | `SearchEngineFacade` | Nay **chỉ** điều phối — 6 trách nhiệm đã chuyển đi |
| **Adapter** | `HtmlExtractor`, `Stack<T>` (TS) | Jsoup chỉ xuất hiện ở **2 file** trong toàn dự án |
| **Repository** | `DocumentRepository` | Nay có interface `DocumentStore` bọc ngoài |
| **Value Object** | 9 `record` | Và biết khi nào **không** dùng record (`PoolEntry` sinh ra để sửa tay) |
| **Cache-Aside** | `LRUCache` trong `search()` | 34,5 ms → **12,8 ms** |
| **Producer–Consumer** | Crawler + `UrlFrontier` | Mỗi worker vừa là producer vừa là consumer |
| **DI** | Constructor injection | Facade nhận 6 phụ thuộc qua constructor |

---

## Anti-pattern đã loại bỏ

| Anti-pattern | Trước | Nay |
|---|---|---|
| **God Object** | `Facade` 420 dòng / 7 trách nhiệm | Chỉ điều phối |
| **Primitive Obsession** | `String status` | `enum CrawlStatus` |
| **Feature Envy** | `looksVietnamese` trong Facade | `LanguageDetector` |
| **Copy-Paste** | `findTermFrequencyInDoc` × 3 | Một cài đặt trong `SearchIndex` |
| **Dead code** | `union` không ai gọi | `OrNode` dùng thật |
| **Leaky encapsulation** | `getAllDocuments()` trả map nội bộ | `unmodifiableMap` |

Vẫn **không** có: Singleton thủ công, kế thừa sâu, Service Locator, `instanceof` phân nhánh kiểu, over-engineering.

---

## Liên kết

- Chấm điểm chi tiết theo tiêu chí: [CHAM-DIEM.md](CHAM-DIEM.md)
- Phân tích từng thuật toán: [README.md](../README.md)
