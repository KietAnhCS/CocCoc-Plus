# CrawlerService — BFS đa luồng và bài toán "khi nào thì hết việc"

**File nguồn:** `search-engine/src/main/java/com/vnsearch/crawler/CrawlerService.java`
**Việc nó làm:** Điều phối $T$ worker thread cùng duyệt đồ thị web theo BFS, thu về 5.011 trang trong 3,2 phút.

> 📖 Chưa quen ký hiệu toán? Đọc [00 — Từ điển ký hiệu toán](../00-KY-HIEU-TOAN.md) trước.


> ### 🔄 Đã cập nhật sau đợt tái cấu trúc
>
> Phần **toán học và thuật toán** dưới đây vẫn đúng nguyên vẹn. Nhưng một số
> đoạn mã trích dẫn và mục *"Hạn chế đã biết"* mô tả **phiên bản trước**.
> Những gì đã thay đổi ở file này:
>
> - `CrawlConfig` nay là lớp riêng, **bất biến, dựng bằng Builder**, kiểm tra tính hợp lệ tập trung trong `build()`.
> - Tiến độ crawl nay phát qua **`CrawlListener`** (Observer) thay vì `System.out.printf` chôn trong worker.
> - Log dùng **SLF4J** thay `System.out`.
>
> Chi tiết: [09-design-patterns/CHAM-DIEM.md](../09-design-patterns/CHAM-DIEM.md)

---

## 📌 Hiểu trong 30 giây

Web là một **đồ thị có hướng** gần như vô hạn về chiều sâu: đỉnh là trang, cạnh là liên kết. Crawl là bài toán **duyệt đồ thị với ngân sách hữu hạn**.

Ba câu hỏi phải trả lời, và câu thứ ba khó nhất:

1. **Duyệt theo thứ tự nào?** → BFS có ưu tiên (xem [UrlFrontier](UrlFrontier.md)).
2. **Làm sao không duyệt lại đỉnh cũ?** → Bloom Filter (xem [BloomFilter](BloomFilter.md)).
3. **Khi nào thì dừng?** → **Đây là phần riêng của lớp này, và nó tinh tế hơn vẻ ngoài rất nhiều.**

Với một thread, câu 3 dễ: hàng đợi rỗng là hết việc. Với nhiều thread, **hàng đợi rỗng KHÔNG đồng nghĩa với hết việc** — một worker khác có thể đang fetch một trang và sắp thêm 78 outlink mới vào frontier ngay giây tới.

---

## 1. Vì sao BFS mà không phải DFS

**Vấn đề.** Web gần như vô hạn về chiều sâu. Nếu duyệt bằng DFS, crawler sẽ lao xuống một nhánh (chuyên mục → bài → bài liên quan → bài liên quan → …) và **không bao giờ quay lên**. Với ngân sách 5.000 trang, ta thu về một tập lệch hẳn và bỏ sót những trang quan trọng nằm ngay cạnh seed.

**Ý tưởng.** BFS duyệt theo **từng lớp độ sâu**, nên các trang thu được là những trang **gần seed nhất** — vốn thường là trang chủ và trang chuyên mục, tức là những trang quan trọng nhất.

**Số liệu minh hoạ, ước lượng theo hệ số phân nhánh thật $b = 78{,}8$:**

| Độ sâu | Số trang lý thuyết ở lớp đó | Cộng dồn |
|---|---|---|
| 0 | 6 (seed) | 6 |
| 1 | ~473 | ~479 |
| 2 | ~37 000 | vượt xa ngân sách 5.000 |

Nghĩa là với `maxPages = 5000`, crawler thực tế **chưa duyệt xong lớp 2**. Đó là lý do `maxDepth = 3` là quá đủ và tại sao BFS ở đây gần như tương đương "lấy các trang gần seed nhất".

> **Ghi chú về tính chính xác của mô hình:** con số 37 000 giả định các outlink không trùng nhau, điều hoàn toàn sai trên thực tế (mọi trang của một báo đều trỏ về trang chủ, menu, chuyên mục). Số đỉnh phân biệt thật nhỏ hơn nhiều — nhưng kết luận "chưa duyệt xong lớp 2" vẫn đúng.

---

## 2. Vòng lặp worker — cấu trúc

```java
private void workerLoop(CrawlConfig config) {
    final int IDLE_CONFIRMATIONS = 3;
    int idleChecks = 0;

    while (pagesCrawled.get() < config.maxPages) {
        UrlFrontier.Task task = frontier.nextUrl();
        if (task == null) {
            if (activeWorkers.get() == 0 && ++idleChecks >= IDLE_CONFIRMATIONS) {
                break; // that su het viec
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { ...; return; }
            continue;
        }
        idleChecks = 0;

        if (task.depth() > config.maxDepth
                || !isAllowedDomain(task.url(), config.allowedDomains)
                || visited.mightContain(task.url())) {
            continue;
        }
        visited.add(task.url());

        if (!robotsTxtParser.isAllowed(USER_AGENT, task.url())) {
            continue;
        }

        activeWorkers.incrementAndGet();
        try {
            WebDocument doc = fetchWithRetry(task.url());
            if (doc == null) continue;

            doc.setDocId(docIdCounter.getAndIncrement());
            crawled.put(task.url(), doc);
            int count = pagesCrawled.incrementAndGet();
            ...
            if (task.depth() < config.maxDepth) {
                for (String outlink : doc.getOutlinks()) {
                    if (isAllowedDomain(outlink, config.allowedDomains)
                            && !visited.mightContain(outlink)) {
                        frontier.addUrl(outlink, task.depth() + 1, 1);
                    }
                }
            }
        } finally {
            activeWorkers.decrementAndGet();
        }
    }
}
```

**Thứ tự các phép lọc rất quan trọng** — xếp từ rẻ tới đắt:

| Thứ tự | Phép lọc | Chi phí |
|---|---|---|
| 1 | `depth > maxDepth` | so sánh số nguyên — gần như 0 |
| 2 | `isAllowedDomain` | phân tích URI + so chuỗi — $O(L)$ |
| 3 | `visited.mightContain` | 2 lần băm + 7 lần đọc bit — $O(k)$ |
| 4 | `robotsTxtParser.isAllowed` | tra cache, có thể **fetch mạng** lần đầu |
| 5 | `fetchWithRetry` | **mạng**, tới 30 giây |

Đây là nguyên tắc **short-circuit theo chi phí tăng dần**: đặt phép kiểm tra rẻ nhất và loại nhiều nhất lên trước. Java đánh giá `||` theo kiểu ngắn mạch nên chỉ cần điều kiện đầu đúng là ba điều kiện sau không chạy.

---

## 3. Bài toán trung tâm: phát hiện kết thúc phân tán

**Vấn đề, phát biểu chính xác.** Gọi $F$ = số URL trong frontier, $A$ = số worker đang xử lý một trang. Điều kiện "thật sự hết việc" là:

$$F = 0 \;\wedge\; A = 0$$

Chỉ kiểm tra $F = 0$ là **sai**, vì tồn tại khoảng thời gian mà $F = 0$ nhưng $A > 0$ — một worker đang fetch và sắp thêm hàng chục outlink.

**Hậu quả nếu làm sai:** các worker sẽ **chết dần** trong những khoảng trống tạm thời đó. Worker thứ nhất thấy frontier rỗng → thoát. Worker thứ hai cũng vậy. Đến khi worker đang fetch trả về outlink thì đã không còn ai nhặt. Phiên crawl dừng ở vài trăm trang thay vì 5.000.

**Lời giải: một bộ đếm nguyên tử + xác nhận nhiều lần.**

```java
private final AtomicInteger activeWorkers = new AtomicInteger(0);
```

```java
if (activeWorkers.get() == 0 && ++idleChecks >= IDLE_CONFIRMATIONS) {
    break;
}
```

### 3.1 Vì sao cần `IDLE_CONFIRMATIONS = 3` chứ không phải 1

Vì `frontier.nextUrl()` và `activeWorkers.get()` là **hai phép đọc riêng biệt, không nguyên tử với nhau**. Có một cửa sổ đua thật sự:

```
Thời điểm   Worker A                     Worker B
────────────────────────────────────────────────────────────────
t0          nextUrl() → null             đang chuẩn bị lấy task
t1                                       ĐÃ lấy task xong,
                                         CHƯA kịp incrementAndGet
t2          activeWorkers.get() == 0     ← đọc đúng vào khe hở!
t3          → tưởng hết việc             activeWorkers = 1, fetch...
```

Tại $t_2$, worker A quan sát một trạng thái **không phản ánh sự thật**. Đây không phải lỗi cài đặt mà là hệ quả tất yếu của việc **không có ảnh chụp nhất quán toàn cục** trong hệ thống đồng thời.

Yêu cầu điều kiện đúng **3 lần liên tiếp, cách nhau 200ms** biến xác suất nhầm từ "thỉnh thoảng" thành "gần như không bao giờ": khe hở giữa `nextUrl()` trả về và `incrementAndGet()` rộng cỡ **micro giây**, nên xác suất trúng nó ba lần liên tiếp cách nhau 200ms là tích của ba xác suất cực nhỏ.

$$P(\text{nhầm 3 lần liên tiếp}) \approx \left(\frac{\text{vài } \mu s}{200\,000\,\mu s}\right)^3 \approx 10^{-15}$$

**Và `idleChecks = 0` sau mỗi lần lấy được task** đảm bảo bộ đếm chỉ tích luỹ khi **liên tục** rỗng, không phải cộng dồn rải rác qua cả phiên crawl.

> **Đây là một heuristic, không phải một thuật toán đúng đắn có chứng minh.** Bài toán "phát hiện kết thúc phân tán" có lời giải chính xác — thuật toán **Dijkstra–Scholten** (đếm tham chiếu trên cây lan toả) hoặc **Safra** (thẻ bài vòng) — nhưng cả hai phức tạp hơn nhiều. Với một crawler đồ án, xác suất sai $10^{-15}$ là đánh đổi hoàn toàn hợp lý, miễn là **nói rõ đó là heuristic**.

### 3.2 `try / finally` là bắt buộc

```java
activeWorkers.incrementAndGet();
try {
    ...
} finally {
    activeWorkers.decrementAndGet();
}
```

Nếu `fetchWithRetry` ném ngoại lệ mà không có `finally`, `activeWorkers` sẽ **không bao giờ về 0**, và điều kiện dừng **không bao giờ đúng** — mọi worker kẹt trong vòng lặp ngủ-thử-lại vô hạn cho tới khi hết `maxDurationMinutes`.

Chú ý cả `continue` bên trong khối `try` (dòng `if (doc == null) continue;`) vẫn chạy qua `finally` — đó chính là lý do phải dùng `finally` chứ không đặt `decrementAndGet()` ở cuối khối.

---

## 4. Ba lớp bảo vệ chống chạy vô hạn

Crawler có **ba** cơ chế dừng độc lập, mỗi cơ chế chặn một kiểu hỏng khác nhau:

| Cơ chế | Chặn kiểu hỏng nào | Code |
|---|---|---|
| `maxPages` | Đủ dữ liệu thì dừng | `while (pagesCrawled.get() < config.maxPages)` |
| `maxDepth` | Lao quá sâu vào một nhánh | `if (task.depth() > config.maxDepth) continue;` |
| `maxDurationMinutes` | Mọi thứ khác hỏng | `latch.await(config.maxDurationMinutes, TimeUnit.MINUTES)` |

```java
CountDownLatch latch = new CountDownLatch(config.threadCount);
...
if (!latch.await(config.maxDurationMinutes, TimeUnit.MINUTES)) {
    System.out.printf("Het tran thoi gian %d phut, dung crawl voi %d trang.%n",
            config.maxDurationMinutes, pagesCrawled.get());
}
pool.shutdownNow();
```

**`CountDownLatch` hoạt động thế nào:** khởi tạo bằng $T$ (số thread), mỗi worker gọi `countDown()` khi kết thúc, `await()` chặn cho tới khi bộ đếm về 0 **hoặc** hết thời gian chờ. Đây là **rào chắn một chiều** — đếm xuống rồi không đếm lên lại được, đúng ngữ nghĩa "chờ tất cả xong".

`countDown()` nằm trong `finally` của worker để đảm bảo được gọi kể cả khi worker chết vì ngoại lệ — nếu không, `await()` sẽ chờ đủ 60 phút một cách vô ích.

Trần thời gian là **lưới an toàn cuối cùng**: nếu hai cơ chế trên đều hỏng vì một lỗi chưa lường trước, phiên crawl vẫn kết thúc.

---

## 5. Retry có giới hạn

```java
private static final int TIMEOUT_MS = 10_000;
private static final int MAX_RETRIES = 2;

private WebDocument fetchWithRetry(String url) {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent(USER_AGENT).timeout(TIMEOUT_MS).followRedirects(true).get();
            return htmlExtractor.extract(url, document);
        } catch (Exception e) {
            if (attempt == MAX_RETRIES) {
                System.out.printf("  [loi] khong the fetch %s sau %d lan thu: %s%n",
                        url, MAX_RETRIES + 1, e.getMessage());
            }
        }
    }
    return null;
}
```

**Vấn đề.** Lỗi mạng tạm thời (timeout, connection reset) rất thường xuyên khi crawl hàng nghìn trang. Bỏ luôn trang thì mất dữ liệu; thử lại vô hạn thì một URL chết treo cả worker.

**Chặn trên thời gian cho một URL chết:**

$$(\text{MAX\_RETRIES} + 1) \times \text{TIMEOUT} = 3 \times 10\text{s} = \mathbf{30\ giây}$$

Chỉ log ở lần thử **cuối** (`if (attempt == MAX_RETRIES)`) để không spam console — với 5.000 trang và tỉ lệ lỗi vài phần trăm, chênh lệch là hàng trăm dòng log.

> **Ghi chú:** đây là retry đơn giản, **không có exponential backoff**. Với crawler nghiêm túc nên giãn khoảng chờ theo số lần thất bại ($1s, 2s, 4s, \dots$) để không dồn tải lên một server đang gặp sự cố. Ở đây politeness delay 1 giây đã tạo ra một mức giãn tối thiểu, nhưng không tăng theo số lần lỗi.

---

## 6. Cấp phát Bloom Filter theo quy mô thật

```java
visited = new BloomFilter(Math.max(200_000, config.maxPages * 200), 0.01);
```

Hệ số **200** chứ không phải 1 — và đây là một trong những dòng dễ viết sai nhất của cả dự án.

Bloom Filter này không chứa các trang **đã lưu**, mà chứa mọi URL **đã kiểm tra**. Mỗi trang sinh 78,8 outlink, mỗi outlink đi qua `mightContain`. Với `maxPages = 5000`, số phần tử thật là gần **400.000** chứ không phải 5.000.

Hậu quả nếu tính theo `maxPages`: $n$ thật gấp 80 lần $n$ thiết kế ⇒ tỉ lệ bit bật vọt lên gần 100% ⇒ **mọi** URL đều bị báo "đã thấy" ⇒ crawler dừng sau vài trang.

Chi tiết toán học ở [BloomFilter §6](BloomFilter.md).

---

## 7. Mô hình đồng thời — bảng tổng hợp

| Trạng thái chia sẻ | Kiểu | Vì sao kiểu đó |
|---|---|---|
| `frontier` | `UrlFrontier` (`synchronized` nội bộ) | Cần nguyên tử **nhóm** thao tác |
| `crawled` | `ConcurrentHashMap<String, WebDocument>` | Chỉ cần nguyên tử **từng** `put` |
| `docIdCounter` | `AtomicInteger` | Cấp docId duy nhất, không trùng |
| `pagesCrawled` | `AtomicInteger` | Đếm không mất mát |
| `activeWorkers` | `AtomicInteger` | Điều kiện dừng |
| `visited` | `volatile BloomFilter` | Gán lại tham chiếu ở đầu `crawl()` |

**Vì sao `docIdCounter` phải là `AtomicInteger`:** `getAndIncrement()` là phép **đọc-sửa-ghi nguyên tử**. Nếu dùng `int` thường với `id++`, hai thread có thể cùng đọc giá trị 100, cùng ghi 101, và **hai tài liệu khác nhau nhận cùng docId 100**. Hậu quả xuống tận tầng chỉ mục: posting list sẽ có hai posting cùng docId, phá vỡ bất biến mà binary search dựa vào (xem [InvertedIndex §3](../03-index/InvertedIndex.md)).

**Vì sao `visited` là `volatile`:** nó được **gán lại** ở đầu `crawl()`. Không có `volatile`, các worker thread có thể vẫn thấy tham chiếu cũ do bộ nhớ đệm CPU. Bản thân các thao tác `add`/`mightContain` thì không được `volatile` bảo vệ — xem hạn chế ở [BloomFilter §11](BloomFilter.md).

---

## 8. `CrawlConfig` — Builder kiểu fluent

```java
public static class CrawlConfig {
    public int maxDepth = 3;
    public int maxPages = 100;
    public int threadCount = 4;
    public Set<String> allowedDomains = Set.of();
    public int maxDurationMinutes = 60;
    public int progressEveryN = 1;

    public CrawlConfig maxDepth(int v) { this.maxDepth = v; return this; }
    public CrawlConfig maxPages(int v) { this.maxPages = v; return this; }
    ...
}
```

Mỗi setter `return this` nên gọi được nối chuỗi:

```java
CrawlerService.CrawlConfig config = new CrawlerService.CrawlConfig()
        .maxDepth(maxDepth)
        .maxPages(maxPages)
        .threadCount(allowedDomains.size() * 2)
        .allowedDomains(allowedDomains)
        .maxDurationMinutes(90)
        .progressEveryN(25);
```

**Vì sao tốt hơn constructor 6 tham số:** `new CrawlConfig(3, 5000, 12, domains, 90, 25)` không đọc được — người đọc phải tra thứ tự tham số. Fluent setter làm mỗi giá trị **tự giải thích tên**.

**Vì sao đây chưa phải Builder pattern đầy đủ:** trường là `public` và có thể sửa **sau khi** đã dùng, nên đối tượng không bất biến. Builder đúng nghĩa sẽ có `CrawlConfig.Builder` riêng với `build()` trả về một `CrawlConfig` bất biến. Phân tích và đề xuất cải tiến ở [09-design-patterns/PATTERNS-DE-XUAT.md](../09-design-patterns/DESIGN-PATTERNS.md).

---

## 9. Số đo thực tế

| Phép đo | Kết quả |
|---|---|
| Thời gian crawl 5.011 trang | **3,2 phút** |
| Thông lượng | **26,2 trang/giây** (trần lý thuyết 52 do politeness) |
| Số host phân biệt | **52** |
| Tổng outlink thu được | **394.940** (trung bình **78,8**/trang) |
| Số cạnh trong đồ thị PageRank (outlink trỏ **vào** corpus) | **239.691** |
| — liên kết nội bộ domain | 197.689 (82,5 %) |
| — **liên kết chéo domain** | **42.002 (17,5 %)** |
| Kích thước `data/crawled-multi.json` | 62 MB |

**Đọc con số 17,5 % thế nào.** Đây là tỉ lệ quyết định xem PageRank có ý nghĩa hay không. Liên kết **nội bộ** một tờ báo phản ánh cấu trúc điều hướng (menu, chuyên mục, "bài liên quan") chứ không phản ánh uy tín. Chỉ liên kết **chéo** giữa các site độc lập mới là "phiếu bầu" thật.

Corpus cũ 150 trang **một domain** có 0 % liên kết chéo — PageRank khi đó đo cấu trúc menu của vnexpress.net. Đó chính là lý do `MultiDomainCrawlRunner` tồn tại.

---

## 10. Tổng hợp độ phức tạp

| Thao tác | Thời gian |
|---|---|
| Một vòng worker (một trang) | $O(\log n_d + D)$ lấy URL + $O(k)$ Bloom + **$O(\text{mạng})$** fetch + $O(\lvert\text{HTML}\rvert)$ trích xuất + $O(b \log n_d)$ thêm outlink |
| Toàn phiên crawl | $O(P \cdot (D + b\log n_d))$ **cộng** chi phí mạng |

với $P$ = số trang, $b$ = số outlink/trang, $D$ = số host.

**Điểm quan trọng nhất về độ phức tạp:** toàn bộ phiên crawl **hoàn toàn bị chi phối bởi độ trễ mạng và politeness delay**, không phải bởi thuật toán. Phần thuật toán tốn cỡ **65 thao tác** mỗi trang; phần mạng tốn cỡ **38 mili giây**. Tỉ lệ là khoảng 1 : 500.000.

Đó cũng là lý do bản dùng một heap toàn cục mới thảm hoạ đến thế: nó đẩy phần thuật toán từ 65 lên 7,3 triệu thao tác, tức từ "không đáng kể" thành "chậm hơn cả mạng".

---

## 11. Chủ đề DSA thể hiện

| Chủ đề | Ở đâu |
|---|---|
| **BFS trên đồ thị** | duyệt web theo lớp độ sâu |
| **Đồ thị ẩn** | đỉnh/cạnh sinh ra dần khi fetch, không lưu sẵn |
| **Hàng đợi ưu tiên** | `UrlFrontier` |
| **Cấu trúc dữ liệu xác suất** | `BloomFilter` chống duyệt lại |
| **Thread pool / producer–consumer** | `ExecutorService` + frontier chia sẻ |
| **Biến nguyên tử** | `AtomicInteger` cho docId, bộ đếm |
| **Phát hiện kết thúc phân tán** | `activeWorkers` + `IDLE_CONFIRMATIONS` |
| **Rào chắn đồng bộ** | `CountDownLatch` + `await` có thời hạn |
| **Short-circuit theo chi phí** | thứ tự các phép lọc từ rẻ tới đắt |
| **Retry có chặn trên** | $3 \times 10$s |
| **Ước lượng tham số theo dữ liệu đo** | `maxPages * 200` từ 78,8 outlink/trang |

---

## 12. Hạn chế đã biết

1. **Điều kiện dừng là heuristic**, không có chứng minh đúng đắn (xem §3.1).
2. **Không có exponential backoff** khi retry.
3. **`crawled` khoá theo URL, `docId` cấp theo thứ tự hoàn thành**, nên docId **không** phản ánh thứ tự BFS. Không sai, nhưng khiến `docId` nhỏ không đồng nghĩa với "gần seed".
4. **Không lọc theo Content-Type.** Một liên kết tới file PDF hay ảnh vẫn được đưa vào frontier và fetch; Jsoup sẽ ném lỗi và tốn tới 30 giây retry vô ích.
5. **Không xử lý trang lỗi mềm.** Trang 404 trả về HTML "không tìm thấy" với mã 200 vẫn được index như một tài liệu bình thường.
6. **Log bằng `System.out.printf`** thay vì một logger. Không tắt được, không phân mức, không định tuyến ra file — xem [CHAM-DIEM.md](../09-design-patterns/CHAM-DIEM.md).

---

## 13. Liên kết

- Hàng đợi và politeness: [UrlFrontier.md](UrlFrontier.md)
- Khử trùng lặp: [BloomFilter.md](BloomFilter.md) · [UrlCanonicalizer.md](UrlCanonicalizer.md)
- Luật crawl: [RobotsTxtParser.md](RobotsTxtParser.md)
- Trích xuất nội dung: [HtmlExtractor.md](HtmlExtractor.md)
- Bước tiếp theo trong pipeline: [VietnameseTokenizer.md](../02-tokenize/VietnameseTokenizer.md)
- Ký hiệu chưa hiểu: [00 — Từ điển ký hiệu toán](../00-KY-HIEU-TOAN.md)
