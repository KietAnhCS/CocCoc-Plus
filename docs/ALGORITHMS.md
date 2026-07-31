# Các thuật toán trong VnSearch — giải thích theo đường đi của dữ liệu

> **Tài liệu này là gì?** Một chương sách đi qua **từng thuật toán** được cài
> đặt trong máy tìm kiếm, theo đúng thứ tự dữ liệu chảy qua hệ thống:
> `crawl → tokenize → index → query → rank → serve`.
>
> Mỗi thuật toán được trình bày theo cùng một khuôn năm phần:
>
> | Phần | Trả lời câu hỏi |
> |---|---|
> | **Vấn đề** | Nếu không có thuật toán này thì sai/chậm ở đâu? |
> | **Ý tưởng** | Mẹo cốt lõi, nói bằng lời |
> | **Mã giả** | Thuật toán ở dạng thuần khiết, không lệ thuộc ngôn ngữ |
> | **Mã thật** | Trích đúng đoạn code trong repo |
> | **Độ phức tạp** | Big-O, kèm giải thích *vì sao* ra con số đó |
>
> **Phân biệt với các tài liệu khác:** `SEARCH-ENGINE-101.md` giải thích lý
> thuyết IR nói chung (kèm ví dụ tính tay); `DSA-REPORT.md` là báo cáo Big-O
> và số liệu đo. Tài liệu này ở giữa: nối lý thuyết với dòng code cụ thể.

## Mục lục

| Giai đoạn | Thuật toán |
|---|---|
| [**1. Crawl**](#1-giai-đoạn-crawl--thu-thập-dữ-liệu) | BFS có ưu tiên · Hàng đợi tách theo host (Mercator) · Politeness scheduling · Bloom Filter với double hashing · Chuẩn hoá URL · Longest-prefix-match cho robots.txt · Retry có giới hạn |
| [**2. Tokenize**](#2-giai-đoạn-tokenize--tách-từ-tiếng-việt) | Longest Matching · Chuẩn hoá Unicode NFC/NFD · Sinh bản không dấu · Lọc từ dừng |
| [**3. Index**](#3-giai-đoạn-index--lập-chỉ-mục) | Dựng chỉ mục đảo · Bất biến sắp xếp (tự ép) · Binary search trên posting list · Chỉ mục kép có dấu/không dấu · **Flyweight cho khoá term** · **Nén delta + VByte** |
| [**4. Query**](#4-giai-đoạn-query--xử-lý-truy-vấn) | Phân tích truy vấn bằng regex · Cây biểu thức AND/OR/NOT · Two-pointer intersect/union · Shortest-first · **Galloping skip pointer** · Khớp cụm từ theo vị trí |
| [**5. Rank**](#5-giai-đoạn-rank--xếp-hạng) | TF-IDF + cosine · BM25 · PageRank power iteration · **Kết hợp tín hiệu bằng nhân + log (Decorator)** · Top-K bằng MinHeap · Cửa sổ trượt sinh snippet |
| [**6. Evaluate**](#6-giai-đoạn-evaluate--đo-chất-lượng) | P@k / R@k / F1@k · AP / MAP · nDCG · MRR / Success@k · Sinh truy vấn known-item · TREC pooling |
| [**7. Serve**](#7-giai-đoạn-serve--phục-vụ-và-gợi-ý) | Trie prefix search · LRU eviction |
| [**8. Trình duyệt**](#8-phía-trình-duyệt-electron--typescript) | Stack cho back/forward · Trie TypeScript |

---

## 1. Giai đoạn CRAWL — thu thập dữ liệu

### 1.1. BFS có ưu tiên

**Vấn đề.** Web là một đồ thị có hướng gần như vô hạn về chiều sâu. Nếu duyệt
bằng DFS, crawler sẽ lao xuống một nhánh (chuyên mục → bài → bài liên quan →
…) và không bao giờ quay lên, nên với ngân sách hữu hạn ta thu về một tập
trang lệch hẳn và bỏ sót những trang quan trọng gần seed.

**Ý tưởng.** BFS duyệt theo **từng lớp độ sâu**, nên các trang thu được là
những trang **gần seed nhất** — vốn thường là trang chủ và trang chuyên mục,
tức là những trang quan trọng nhất. Nhưng BFS thuần dùng hàng đợi FIFO, coi
mọi trang cùng một lớp là như nhau. Ta thay FIFO bằng **hàng đợi ưu tiên**,
với điểm ưu tiên là một hàm của ba tín hiệu.

**Mã giả.**

```
BFS-CO-UU-TIEN(seeds, maxDepth, maxPages):
    for mỗi seed: frontier.addUrl(seed, depth = 0)
    chạy song song K worker, mỗi worker:
        while pagesCrawled < maxPages:
            task ← frontier.nextUrl()          # ưu tiên cao nhất + đã hết hoãn
            nếu task = null: kiểm tra thật sự hết việc, nếu chưa thì ngủ ngắn
            nếu task.depth > maxDepth: bỏ
            nếu đã thăm (BloomFilter): bỏ
            nếu robots.txt cấm: bỏ
            doc ← fetch(task.url)
            nếu task.depth < maxDepth:
                for mỗi outlink của doc:
                    frontier.addUrl(outlink, task.depth + 1)
```

**Mã thật.** `crawler/CrawlerService.java` — công thức điểm ưu tiên nằm ở
`datastructure/UrlFrontier.computePriority()`:

```java
private double computePriority(String url, int depth, int knownBacklinks) {
    double score = 0;
    score -= depth * 2.0;                          // càng sâu càng ít ưu tiên
    score += Math.min(knownBacklinks, 50) * 0.5;   // nhiều backlink → ưu tiên hơn
    if (isVnDomain(url)) {
        score += 5.0;                              // ưu tiên domain .vn theo yêu cầu đề bài
    }
    return score;
}
```

Viết thành công thức:

$$
\mathrm{priority}(u) \;=\; -2\,\mathrm{depth}(u)
\;+\; 0{,}5 \cdot \min\bigl(\mathrm{backlinks}(u),\, 50\bigr)
\;+\; 5 \cdot \mathbb{1}\bigl[u \in \texttt{.vn}\bigr]
$$

Ba chi tiết đáng chú ý trong công thức này:

- **`min(backlinks, 50)`** — chặn trên để một trang có 5.000 backlink không
  áp đảo hoàn toàn tín hiệu độ sâu. Không có nó thì hàng đợi ưu tiên thoái
  hoá thành "chỉ xét backlink".
- **Hệ số `-2` cho độ sâu** lớn hơn hệ số `0,5` cho backlink, nghĩa là
  **giảm một lớp độ sâu quan trọng hơn có thêm 4 backlink**. Đây là điều làm
  thuật toán vẫn *giống BFS* thay vì biến thành thuần greedy theo backlink.
- **`+5` cho `.vn`** là yêu cầu của đề bài (máy tìm kiếm tiếng Việt), không
  phải một nguyên lý IR tổng quát.

**Mẹo cài đặt: biến min-heap thành max-heap.** `MinHeap` luôn trả về phần tử
**nhỏ nhất**, nhưng ta cần phần tử **ưu tiên cao nhất**. Giải pháp là sắp
heap theo `−priority`:

```java
byDomain.computeIfAbsent(domain,
                d -> new MinHeap<>((a, b) -> Double.compare(-a.priority(), -b.priority())))
        .insert(new FrontierEntry(url, depth, priority));
```

Phần tử có `priority` cao nhất sẽ có `−priority` nhỏ nhất nên vẫn ra đầu
tiên. Kỹ thuật này biến một min-heap thành "max-heap theo tiêu chí X" mà
không phải viết lại cấu trúc.

**Độ phức tạp.** Toàn bộ phiên crawl: $O(P\,(\log n_d + D))$ với `P` là số
trang crawl được — nhưng trong thực tế **hoàn toàn bị chi phối bởi độ trễ
mạng và politeness delay**, không phải bởi thuật toán. Đo thực tế: 5.011
trang trong 3,2 phút = 26,2 trang/giây.

---

### 1.2. Hàng đợi tách theo host — mô hình Mercator

**Vấn đề.** Đây là bài học hiệu năng lớn nhất của phần crawler, và nó **chỉ
lộ ra khi tăng quy mô**.

Bản đầu tiên dùng **một heap toàn cục**. Khi phần tử ưu tiên cao nhất thuộc
một host đang trong thời gian hoãn (politeness delay), thuật toán buộc phải:
rút nó ra → gác sang danh sách tạm → rút tiếp phần tử sau → … Trường hợp xấu
nhất — **mọi** URL đang chờ đều thuộc các host vừa được truy cập — phải rút
**cạn** cả heap rồi nhét lại toàn bộ:

$$
\text{Chi phí mỗi lần lấy MỘT URL} = O(n \log n)
$$

Ở quy mô 150 trang, chi phí này **không quan sát được**. Nhưng mỗi trang tin
tức sinh trung bình **78,8 outlink**, nên crawl 5.000 trang đẩy frontier lên
hàng chục nghìn URL và crawler thực tế **đứng hình**.

**Ý tưởng.** Thay một heap toàn cục bằng `Map<host, MinHeap>` — mỗi host một
hàng đợi riêng. Khi cần lấy URL, chỉ **quét qua các host** (số host `D` nhỏ),
chọn host vừa hết hoãn và có phần tử đầu hàng ưu tiên cao nhất, rồi
`extractMin` **đúng một lần**. Đây chính là mô hình "back queue theo host"
của crawler **Mercator** (Heydon & Najork, 1999).

**Mã giả.**

```
NEXT-URL():
    lặp mãi:
        nếu tổng số URL = 0: trả về null            # thật sự rỗng
        bestHost ← null; bestPriority ← −∞
        for mỗi (host, heap) trong byDomain:
            nếu heap rỗng: xoá khỏi map; tiếp
            nếu now − lastAccess[host] < DELAY: tiếp   # đang hoãn
            nếu heap.peek().priority > bestPriority:
                bestPriority ← heap.peek().priority; bestHost ← host
        nếu bestHost ≠ null:
            lastAccess[bestHost] ← now
            trả về byDomain[bestHost].extractMin()
        ngủ 50ms NGOÀI khối đồng bộ rồi thử lại        # mọi host đang hoãn
```

**Mã thật.** `datastructure/UrlFrontier.nextUrl()`:

```java
Iterator<Map.Entry<String, MinHeap<FrontierEntry>>> it = byDomain.entrySet().iterator();
while (it.hasNext()) {
    Map.Entry<String, MinHeap<FrontierEntry>> entry = it.next();
    MinHeap<FrontierEntry> heap = entry.getValue();
    if (heap.isEmpty()) {
        it.remove(); // dọn hàng đợi rỗng để vòng quét sau khỏi phải xét lại
        continue;
    }
    Long last = lastAccessTime.get(entry.getKey());
    if (last != null && now - last < POLITENESS_DELAY_MS) {
        continue; // domain này đang trong thời gian hoãn
    }
    double priority = heap.peek().priority();
    if (priority > bestPriority) {
        bestPriority = priority;
        bestDomain = entry.getKey();
    }
}
```

**Hai chi tiết cài đặt đáng học.**

1. **`it.remove()` cho heap rỗng.** Không dọn thì các host đã cạn URL vẫn bị
   quét lại ở mọi lần gọi `nextUrl()`, khiến `D` chỉ tăng chứ không bao giờ
   giảm trong suốt phiên crawl.
2. **Ngủ *ngoài* khối `synchronized`.** Nếu `Thread.sleep(50)` nằm trong khối
   đồng bộ, thread đang ngủ vẫn giữ khoá và **chặn mọi thread khác đang muốn
   `addUrl`** — biến một tối ưu thành một điểm nghẽn.

**Độ phức tạp.**

| Thiết kế | Chi phí mỗi `nextUrl()` |
|---|---|
| Một heap toàn cục | $O(n\log n)$ — phụ thuộc **tổng** kích thước frontier |
| **Tách theo host** | **$O(D + \log n_d)$** — không phụ thuộc tổng kích thước |

`addUrl` là $O(\log n_d)$. Xem phân tích đầy đủ ở mục 2.5 của `DSA-REPORT.md`.

**Hạn chế đã biết:** khi hai host cùng ưu tiên bằng nhau, phép so sánh
`priority > bestPriority` (dấu `>` chặt) khiến host được quét trước thắng —
mà thứ tự quét là thứ tự nội bộ của `HashMap`, tức là không xác định trước.
Không sai, nhưng làm việc tái lập thứ tự crawl khó hơn.

---

### 1.3. Politeness scheduling

**Vấn đề.** Bắn 100 request/giây vào một website là một cuộc tấn công từ chối
dịch vụ, bất kể ý định. Quy tắc bất thành văn của mọi crawler: **mỗi host
tối đa 1 request/giây**.

**Ý tưởng.** Ghi lại thời điểm truy cập cuối cùng của mỗi host, và chỉ xét
những host đã qua `POLITENESS_DELAY_MS`. Điều này **buộc** crawler luân phiên
giữa các host.

```java
public static final long POLITENESS_DELAY_MS = 1000L;
```

**Hệ quả kiến trúc — đây là phần quan trọng nhất.** Politeness không phải một
chi tiết lễ nghi mà là một **ràng buộc đặt trần cứng lên thông lượng**:

$$
\text{thông lượng tối đa (trang/giây)} \;=\; \text{số host được crawl đồng thời}
$$

Dự án này có **52 host** phân biệt → trần lý thuyết 52 trang/giây, thực đo
26,2. Muốn 400 trang/giây thì phải có ít nhất 400 host được crawl song song —
**không phải** mua máy nhanh hơn. `MultiDomainCrawlRunner` áp dụng trực tiếp
suy luận này khi chọn số thread:

```java
// Politeness delay 1s/domain nghia la thong luong toi da = so domain
// (trang/giay). Dung so thread gap doi so domain de thread khong phai
// la nut that co, phan con lai da bi politeness khong che.
.threadCount(allowedDomains.size() * 2)
```

---

### 1.4. Khử trùng lặp URL — Bloom Filter với double hashing

**Vấn đề.** Crawl 5.011 trang thu về **394.940 outlink**. Trước mỗi lần fetch
phải trả lời: "URL này crawl chưa?". `HashSet<String>` trả lời được, nhưng
phải lưu **nguyên vẹn** từng chuỗi URL. Đo thực tế với 1 triệu URL:

| Cấu trúc | Bộ nhớ |
|---|---|
| `HashSet<String>` (đo heap delta thực tế) | **~108 MB** |
| Bloom Filter (lý thuyết `m/8` byte) | **~1,1 MB** |

Chênh **~95 lần**, vì Bloom Filter chỉ lưu vài bit trên mỗi phần tử, **độc
lập với độ dài chuỗi gốc**.

**Ý tưởng.** Một mảng bit kích thước `m`, ban đầu toàn 0.

- `add(x)`: băm `x` bằng `k` hàm băm khác nhau, **bật** `k` bit tương ứng.
- `mightContain(x)`: băm lại; nếu **có bất kỳ bit nào bằng 0** → chắc chắn
  chưa thêm.

**Tính đúng đắn — điểm mấu chốt phải hiểu:**

> **Không bao giờ có false negative.** Vì `add()` chỉ **bật** bit, không bao
> giờ tắt. Bit đã bật bởi `x` sẽ vẫn bật khi kiểm tra lại `x`, dù có bao
> nhiêu phần tử khác được thêm vào.
>
> **Có thể có false positive.** Nhiều chuỗi khác nhau có thể vô tình bật
> trùng đủ bộ `k` bit.

Với bài toán crawl, đây là **đúng chiều đánh đổi cần thiết**: false positive
chỉ khiến bỏ lỡ vài trang; false negative mới nguy hiểm (crawl lại trang đã
crawl → vòng lặp vô hạn), và điều đó **không thể xảy ra**.

**Công thức chọn tham số tối ưu.**

$$
m = \left\lceil \frac{-n \ln p}{(\ln 2)^2} \right\rceil
\qquad
k = \operatorname{round}\!\left(\frac{m}{n}\ln 2\right)
$$

Thay số cho `n = 1.000.000`, `p = 0,01`:

$$
m = \left\lceil \frac{10^6 \times 4{,}60517}{0{,}480453} \right\rceil = 9{.}585{.}059 \text{ bit} \approx 1{,}14 \text{ MB}
\qquad
k = \operatorname{round}(9{,}585 \times 0{,}693) = 7
$$

**Mẹo double hashing (Kirsch & Mitzenmacher, 2008).** Thay vì viết `k` hàm
băm riêng — vừa dài vừa khó đảm bảo độc lập — chỉ cần **2** hàm băm thật,
phần còn lại là tổ hợp tuyến tính:

$$
h_i(x) = \bigl(h_1(x) + i \cdot h_2(x)\bigr) \bmod m,
\qquad i = 0, 1, \dots, k-1
$$

**Mã thật.** `datastructure/BloomFilter.java`:

```java
public void add(String item) {
    long h1 = hash1(item);      // FNV-1a 64-bit
    long h2 = hash2(item);      // polynomial rolling hash + avalanche mix
    for (int i = 0; i < numHashes; i++) {
        int idx = indexFor(h1, h2, i);
        setBit(idx);
    }
}

private int indexFor(long h1, long h2, int i) {
    long combined = h1 + (long) i * h2;
    return (int) Math.floorMod(combined, (long) numBits);
}
```

Ba chi tiết cài đặt đáng chú ý:

- **Tự quản lý bit bằng `long[]`** thay vì dùng `java.util.BitSet`, để cơ chế
  lưu trữ bit hiện rõ: `bits[index / 64] |= (1L << (index % 64))`.
- **`Math.floorMod`** thay vì `%`. Với `long` có thể tràn thành số âm, và `%`
  trong Java trả về kết quả âm khi toán hạng đầu âm → chỉ số mảng âm →
  `ArrayIndexOutOfBoundsException`. `floorMod` luôn trả về giá trị không âm.
- **Avalanche mix trong `hash2`** (`hash ^= hash >>> 33; hash *= 0xff51...`)
  để các bit thấp của `h2` không tương quan với `h1` — nếu tương quan thì `k`
  hàm băm dẫn xuất sẽ đụng nhau và tỷ lệ false positive tăng vọt.

**Độ phức tạp.** `add` và `mightContain` đều $O(k)$ với `k` là hằng số nhỏ
(thường dưới 20). Bộ nhớ $O(m)$ bit, **không phụ thuộc độ dài chuỗi**.

---

### 1.5. Chuẩn hoá URL

**Vấn đề.** `https://a.com` và `https://a.com/` là **cùng một trang** nhưng
là **hai chuỗi khác nhau**, nên Bloom Filter coi chúng khác nhau và crawl cả
hai. Dự án này đã dính đúng lỗi đó: **23 cặp trang trùng nhau** chỉ khác dấu
gạch chéo cuối, trong phiên crawl đầu tiên.

Hậu quả không chỉ là lãng phí băng thông: **các bản sao cùng lọt vào chỉ mục
và cùng xuất hiện trong kết quả tìm kiếm**, làm giảm chất lượng thấy rõ.

**Ý tưởng.** Đưa mọi URL về một dạng biểu diễn duy nhất, và chỉ dùng những
phép biến đổi **an toàn** — tức không làm thay đổi tài nguyên được trỏ tới.

| Phép | Ví dụ | Vì sao an toàn |
|---|---|---|
| Bỏ fragment | `a.com/x#phan-2` → `a.com/x` | Fragment không được gửi lên máy chủ |
| Hạ chữ thường scheme + host | `HTTPS://A.COM/X` → `https://a.com/X` | RFC 3986: hai thành phần này không phân biệt hoa thường |
| Bỏ cổng mặc định | `a.com:443/x` → `a.com/x` | `:443` với https là mặc định |
| Bỏ `/` cuối | `a.com/tin/` → `a.com/tin` | Quy ước; đường dẫn gốc rút hẳn thành chuỗi rỗng |

**Hai phép KHÔNG được làm** — và đây là phần dễ sai:

- **Không hạ chữ thường phần path.** Theo RFC 3986, đường dẫn **có** phân
  biệt hoa thường. `/Tin-Tuc` và `/tin-tuc` có thể là hai tài nguyên khác
  nhau.
- **Không đụng vào query string.** Bỏ tham số theo dõi (`utm_*`) hay đảo thứ
  tự tham số **có thể làm trang trả về khác đi**. Đây là phép chuẩn hoá không
  an toàn.

**Mã thật.** `crawler/UrlCanonicalizer.canonicalize()` — chú ý chỗ điểm vào
duy nhất:

```java
public boolean addUrl(String rawUrl, int depth, int knownBacklinks) {
    // Chuan hoa ngay tai cua vao: day la choke point duy nhat ma moi URL
    // deu phai di qua, nen chuan hoa o day dam bao tap enqueued khong bao
    // gio chua 2 bien the cua cung mot trang.
    String url = com.vnsearch.crawler.UrlCanonicalizer.canonicalize(rawUrl);
    ...
}
```

Đặt phép chuẩn hoá tại **một** điểm vào duy nhất (thay vì rải ở mọi nơi gọi)
là một mẫu thiết kế đáng ghi nhớ: nó biến "phải nhớ chuẩn hoá" thành "không
thể quên chuẩn hoá".

**Độ phức tạp.** $O(L)$ với `L` là độ dài URL.

---

### 1.6. Longest-prefix-match cho robots.txt

**Vấn đề.** Chuẩn Robots Exclusion Protocol cho phép nhiều luật `Allow` /
`Disallow` cùng khớp một đường dẫn. Điểm dễ sai: **luật nào thắng?**

**Ý tưởng.** Luật có đường dẫn **dài nhất** (cụ thể nhất) thắng.

```
Disallow: /admin
Allow: /admin/public
```

→ `/admin/public/x` **được phép** (luật `Allow` dài hơn: 13 ký tự so với 6),
còn `/admin/secret` **bị cấm**.

**Mã giả.**

```
IS-PATH-ALLOWED(rules, path):
    best ← null
    for mỗi rule trong rules:
        nếu path bắt đầu bằng rule.path:
            nếu best = null hoặc độ dài rule.path > độ dài best.path:
                best ← rule
    trả về (best = null) hoặc best.isAllow      # không luật nào khớp → cho phép
```

**Mã thật.** `crawler/RobotsTxtParser.isPathAllowed()`:

```java
boolean isPathAllowed(List<Rule> rules, String path) {
    Rule best = null;
    for (Rule rule : rules) {
        if (path.startsWith(rule.path())) {
            if (best == null || rule.path().length() > best.path().length()) {
                best = rule;
            }
        }
    }
    return best == null || best.isAllow();
}
```

**Ba quyết định thiết kế đi kèm:**

1. **Section riêng thay thế hoàn toàn section `*`.** Nếu robots.txt có mục
   riêng cho user-agent của ta thì mục `User-agent: *` bị **bỏ hẳn**, không
   gộp lại: `out.addAll(specificRules.isEmpty() ? wildcardRules : specificRules)`.
2. **Cache theo domain.** Fetch robots.txt qua mạng là thao tác chậm, không
   thể gọi lại cho mọi URL của cùng một domain →
   `Map<domainKey, List<Rule>>` bằng `ConcurrentHashMap`.
3. **Lỗi mạng → mặc định CHO PHÉP.** Đúng theo hành vi khuyến nghị của đặc
   tả khi không có robots.txt: không chặn crawl chỉ vì lỗi hạ tầng.

**Hạn chế đã biết:** bỏ qua wildcard `*` và `$` trong đường dẫn, và khi hai
luật khớp **cùng độ dài** thì luật xuất hiện trước thắng (chuẩn quy định
`Allow` thắng).

**Độ phức tạp.** Fetch + parse $O(\lvert\text{file}\rvert)$ **một lần** cho mỗi
domain; `isAllowed` sau đó là $O(R)$, thực tế dưới 50 luật nên coi như
$O(1)$.

---

### 1.7. Retry có giới hạn số lần

**Vấn đề.** Lỗi mạng tạm thời (timeout, reset connection) rất thường xuyên
khi crawl hàng nghìn trang. Bỏ luôn trang thì mất dữ liệu; thử lại vô hạn thì
một URL chết sẽ treo cả worker.

**Mã thật.** `CrawlerService.fetchWithRetry()`:

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
            lastError = e;                      // ghi nho, chua bao
        }
    }
    notifyError(url, lastError);                // <- Observer: listener tu quyet dinh log the nao
    return null;
}
```

Tối đa **3 lần thử** (`attempt = 0, 1, 2`), mỗi lần timeout 10 giây → chặn
trên 30 giây cho một URL chết. Chỉ log ở lần thử **cuối** để không spam
console.

> **Ghi chú:** đây là retry đơn giản, **không có exponential backoff**. Với
> crawler nghiêm túc nên giãn khoảng chờ theo số lần thất bại để không dồn
> tải lên một server đang gặp sự cố.

---

## 2. Giai đoạn TOKENIZE — tách từ tiếng Việt

### 2.1. Longest Matching

**Vấn đề — đây là vấn đề riêng của tiếng Việt.** Tiếng Anh tách từ bằng
khoảng trắng: `computer science` → 2 từ, mỗi từ có nghĩa riêng. Tiếng Việt
**không** như vậy: `máy tính` là **một từ** (computer), nhưng viết thành 2
tiếng cách nhau bởi khoảng trắng. Tách theo khoảng trắng sẽ được `máy`
(machine) và `tính` (to calculate) — **sai hoàn toàn về nghĩa**.

Hệ quả trực tiếp cho tìm kiếm: nếu index `máy` và `tính` riêng lẻ thì truy
vấn `máy tính` sẽ khớp cả bài viết về "máy giặt" có chữ "tính tiền".

**Ý tưởng.** Thuật toán tham lam kinh điển: tại mỗi vị trí, thử ghép **nhiều
tiếng nhất có thể** và tra từ điển; cụm dài nhất khớp được sẽ thắng.

**Mã giả.**

```
TOKENIZE(syllables):
    i ← 0
    while i < độ dài syllables:
        matchedLen ← 1
        maxLen ← min(MAX_COMPOUND_LENGTH, còn lại)
        for len từ maxLen giảm về 2:
            nếu từ điển chứa ghép(syllables[i..i+len]):
                matchedLen ← len; dừng
        nếu matchedLen > 1:
            term ← nối bằng "_"; không phải stopword
        ngược lại:
            term ← syllables[i]; kiểm tra stopword
        nếu không phải stopword: phát ra Token(term, bỏ dấu(term), position++)
        i ← i + matchedLen
```

Ví dụ với `khoa học máy tính rất hay`:

| Vị trí | Thử | Kết quả |
|---|---|---|
| `khoa` | `khoa học máy tính` (4 tiếng) | ✅ có trong từ điển → token `khoa_học_máy_tính` |
| `rất` | `rất hay` (2 tiếng) | ❌ → token đơn `rất` |

**Mã thật.** `index/VietnameseTokenizer.tokenize()`:

```java
private static final int MAX_COMPOUND_LENGTH = 4;
...
while (i < syllables.length) {
    int matchedLen = 1;
    int maxLen = Math.min(MAX_COMPOUND_LENGTH, syllables.length - i);
    for (int len = maxLen; len >= 2; len--) {
        String candidate = String.join(" ", Arrays.copyOfRange(syllables, i, i + len));
        if (bigramDictionary.contains(candidate)) {
            matchedLen = len;
            break;                       // ← dài nhất thắng, dừng ngay
        }
    }
    String term;
    boolean isStopword;
    if (matchedLen > 1) {
        term = String.join("_", Arrays.copyOfRange(syllables, i, i + matchedLen));
        isStopword = false;              // ← từ ghép KHÔNG bao giờ bị coi là stopword
    } else {
        term = syllables[i];
        isStopword = stopwords.contains(term);
    }
    if (!isStopword) {
        tokens.add(new Token(term, stripDiacritics(term), position));
        position++;
    }
    i += matchedLen;
}
```

Hai chi tiết dễ bỏ qua:

- **Vòng `for` đi từ `maxLen` giảm xuống**, và `break` ngay khi khớp — đó
  chính là chữ "Longest" trong Longest Matching. Nếu đi từ 2 lên 4 thì thành
  *shortest matching* và `khoa học máy tính` sẽ bị cắt thành `khoa_học` +
  `máy_tính`.
- **`position` chỉ tăng khi token được phát ra**, tức là stopword bị loại
  **không chiếm** một vị trí. Điều này quan trọng cho tìm cụm từ: cụm
  `"trình duyệt web"` vẫn khớp dù giữa các tiếng có stopword bị loại.

**Độ phức tạp.** $O(n \cdot \texttt{MAX\_COMPOUND\_LENGTH}) = O(n)$ vì 4 là hằng số.

> **Hạn chế thật của dự án — trần chất lượng của toàn hệ thống.** Từ điển
> `vietnamese-bigrams.txt` chỉ có **154 mục** (131 cụm 2 tiếng, 11 cụm 3
> tiếng, 12 cụm 4 tiếng). Thuật toán cài **đúng**, nhưng chạy trên từ điển
> nhỏ này thì nhiều cụm từ phổ biến không được ghép: `máy tính` **có** trong
> từ điển nên ghép đúng, còn `bóng đá` **không có** nên bị tách thành `bóng`
> + `đá`. Một từ điển tiếng Việt đầy đủ cần 30.000–70.000 mục.
>
> Lưu ý cả cách đặt tên: biến trong code gọi là `bigramDictionary` nhưng thực
> chất từ điển chứa cụm **tới 4 tiếng**, không chỉ bigram.

---

### 2.2. Chuẩn hoá Unicode NFC

**Vấn đề.** Chữ `ế` có **hai cách** biểu diễn hợp lệ trong Unicode:

| Dạng | Biểu diễn | Số ký tự |
|---|---|---|
| **NFC** (dựng sẵn) | `U+1EBF` | 1 |
| **NFD** (tổ hợp) | `e` + `◌̂` (U+0302) + `◌́` (U+0301) | 3 |

Hai chuỗi trông **y hệt nhau trên màn hình** nhưng **khác nhau về byte**.
Không chuẩn hoá thì cùng một từ tạo ra **hai khoá khác nhau** trong chỉ mục,
và người gõ kiểu này sẽ không tìm được tài liệu gõ kiểu kia.

**Ý tưởng.** Luôn chuẩn hoá về **một** dạng duy nhất (dự án chọn NFC) ở
**mọi** điểm vào: khi tokenize văn bản, khi nạp từ điển, khi chèn vào Trie.

**Mã thật.**

```java
private static String normalizeForLookup(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.forLanguageTag("vi"));
}

private static String[] splitIntoSyllables(String text) {
    String nfc = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.forLanguageTag("vi"));
    String cleaned = nfc.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    ...
}
```

Chú ý `Locale.forLanguageTag("vi")` trong `toLowerCase` — hạ chữ thường phụ
thuộc ngôn ngữ (ví dụ nổi tiếng: tiếng Thổ Nhĩ Kỳ có `I` → `ı`), nên chỉ rõ
locale là thói quen đúng chứ không phải dư thừa.

Và lớp `Trie` cũng chuẩn hoá độc lập, vì nó là một điểm vào khác:

```java
private static String normalize(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFC);
}
```

**Độ phức tạp.** $O(L)$.

---

### 2.3. Sinh bản không dấu

**Vấn đề.** Người Việt hay gõ không dấu trên bàn phím quốc tế: `may tinh`
thay vì `máy tính`. Hệ thống phải tìm được.

**Ý tưởng.** Ba bước, và bước thứ ba là chỗ **hầu như ai cũng sai lần đầu**:

1. Chuẩn hoá về **NFD** — tách dấu ra thành ký tự riêng.
2. Xoá mọi ký tự thuộc nhóm `\p{M}` (combining mark).
3. **Riêng `đ`/`Đ` phải xử lý thủ công** — nó là một **chữ cái Latin độc
   lập** trong bảng chữ cái tiếng Việt, không phải `d` + dấu, nên NFD
   **không tách được**. Nếu bỏ bước này thì `đồng` → `đong` (vẫn còn `đ`) và
   người gõ `dong` sẽ không tìm ra.

**Mã thật.** `VietnameseTokenizer.stripDiacritics()` — chú ý thứ tự: xử lý
`đ` **trước** rồi mới NFD:

```java
public static String stripDiacritics(String s) {
    String withoutDd = s.replace('đ', 'd').replace('Đ', 'D');
    String nfd = Normalizer.normalize(withoutDd, Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{M}", "");
}
```

**Độ phức tạp.** $O(L)$.

---

### 2.4. Lọc từ dừng (stopword)

**Vấn đề.** Từ như `của`, `và`, `là` xuất hiện trong gần như **mọi** tài liệu
nên không mang thông tin phân biệt, nhưng lại chiếm chỗ lớn nhất trong chỉ
mục (posting list của chúng dài nhất).

**Ý tưởng.** Loại bỏ chúng ở khâu tokenize. Dự án dùng danh sách **91 từ** ở
`vietnamese-stopwords.txt`.

**Quyết định thiết kế đáng chú ý: chỉ áp dụng cho token 1 tiếng.**

```java
if (matchedLen > 1) {
    term = String.join("_", ...);
    isStopword = false;        // ← từ ghép không bao giờ bị loại
} else {
    term = syllables[i];
    isStopword = stopwords.contains(term);
}
```

Vì sao: một tiếng có thể là stopword khi đứng riêng nhưng lại là thành phần
mang nghĩa của một từ ghép. Nếu lọc stopword *trước* khi ghép từ, ta sẽ phá
vỡ chính những cụm từ mình muốn giữ.

**Độ phức tạp.** $O(1)$ mỗi token (tra `HashSet`).

---

## 3. Giai đoạn INDEX — lập chỉ mục

### 3.1. Dựng chỉ mục đảo

**Vấn đề.** Chỉ mục **xuôi** (`doc → danh sách từ`) là thứ tự tự nhiên, nhưng
muốn tìm tài liệu chứa `máy_tính` phải duyệt hết **mọi** tài liệu: 5.011 tài
liệu × 1.043 token = **5,2 triệu phép so sánh** cho mỗi truy vấn.

**Ý tưởng.** Lật ngược quan hệ: `từ → danh sách tài liệu`. Tra một từ trở
thành một phép tra `HashMap`: **$O(1)$**.

```
Chỉ mục xuôi                      Chỉ mục đảo
doc1 → [máy_tính, xách_tay, rẻ]   máy_tính  → [doc1, doc2]
doc2 → [công_nghệ, máy_tính]      xách_tay  → [doc1]
                                  công_nghệ → [doc2]
```

**Mỗi posting chứa gì.** `index/Posting.java`:

```java
public record Posting(int docId, int termFrequency, List<Integer> positions) {
}
```

| Trường | Dùng để |
|---|---|
| `docId` | Định danh tài liệu; cơ sở cho phép giao posting list |
| `termFrequency` | TF trong TF-IDF và BM25 |
| `positions` | Tìm theo cụm từ (hai term "cạnh nhau" khi vị trí sau = vị trí trước + 1) |

Là `record` (bất biến) vì một `Posting` không bao giờ thay đổi sau khi tạo —
index lại thì tạo `Posting` mới.

**Mã thật.** `index/InvertedIndex.addDocument()`:

```java
String combinedText = String.join(" ",
        doc.getTitle() != null ? doc.getTitle() : "",
        doc.getMetaDescription() != null ? doc.getMetaDescription() : "",
        doc.getBodyText() != null ? doc.getBodyText() : "");

List<VietnameseTokenizer.Token> tokens = tokenizer.tokenize(combinedText);
documents.put(docId, doc);
docLength.put(docId, tokens.size());
totalTokens += tokens.size();

Map<String, List<Integer>> positionsByTerm = new LinkedHashMap<>();
for (VietnameseTokenizer.Token token : tokens) {
    String term = termDictionary.intern(token.term());          // ← FLYWEIGHT
    positionsByTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(token.position());
    if (!token.noDiacriticTerm().equals(token.term())) {
        String noDiacritic = termDictionary.intern(token.noDiacriticTerm());
        positionsByTerm.computeIfAbsent(noDiacritic, k -> new ArrayList<>()).add(token.position());
    }
}

for (Map.Entry<String, List<Integer>> entry : positionsByTerm.entrySet()) {
    List<Integer> positions = entry.getValue();
    Posting posting = new Posting(docId, positions.size(), positions);
    index.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(posting);   // ← APPEND
}
```

Ba điều đáng học từ đoạn code này:

1. **Gom vị trí vào `positionsByTerm` trước, rồi mới tạo `Posting`.** Nếu tạo
   `Posting` ngay khi gặp token thì một term xuất hiện 5 lần trong tài liệu
   sẽ sinh ra 5 `Posting` cho **cùng** một `docId`, phá vỡ giả định "mỗi
   (term, doc) một posting" mà binary search dựa vào.
2. **`totalTokens` được cộng dồn** thay vì tính lại bằng cách duyệt `docLength`
   mỗi lần cần độ dài trung bình. BM25 gọi `getAverageDocLength()` cho **mọi**
   tài liệu ứng viên của **mọi** truy vấn, nên một phép cộng $O(N)$ ở đó sẽ
   biến việc xếp hạng thành $O(N\cdot c)$.
3. **`termDictionary.intern(...)`** — Flyweight cho khoá term. Tokenizer tạo
   chuỗi **mới** mỗi lần gặp, nên 5.011 tài liệu × ~1.400 tiếng sinh ra
   ~7 triệu object `String` cho chỉ **136.768** giá trị phân biệt. Pool trả về
   instance chuẩn tắc, chuỗi mới thành rác ngay — xem
   [`Math/03-index/TermDictionary.md`](Math/03-index/TermDictionary.md).
4. **`addDocument` tự ép bất biến sắp xếp**: ném `IllegalArgumentException`
   nếu bị gọi với `docId` không tăng dần. Nhờ vậy `totalTokens` cộng thẳng
   được, không cần phép trừ `- previousLength` của bản cũ — index lại cùng
   `docId` nay là **lỗi bị chặn**, không phải trường hợp phải chữa.

**Độ phức tạp.** `addDocument` $O(L)$ với `L` là số token;
`getPostings` / `getDocumentFrequency` $O(1)$. Bộ nhớ $O(\lvert\{(t,d)\}\rvert)$.

---

### 3.2. Bất biến quan trọng nhất: posting list luôn sắp xếp theo `docId`

**Đây là chi tiết dễ bỏ qua nhưng quyết định toàn bộ hiệu năng phía sau.**

Bất biến được đảm bảo **miễn phí**: `addDocument()` luôn được gọi theo thứ tự
`docId` tăng dần, và mỗi lần chỉ **append** vào cuối posting list. Không tốn
**một phép sort nào**.

Tiền đề đó được ép ở **hai lớp độc lập**. Lớp thứ nhất — `IndexBuilder` gom
việc sort về một chỗ duy nhất (trước đây nó bị lặp ở ba nơi: `SearchEngineFacade`,
`EvaluationRunner`, `GinBaselineRunner`):

```java
public InvertedIndex build(List<WebDocument> documents) {
    InvertedIndex index = new InvertedIndex(tokenizer);
    List<WebDocument> sorted = new ArrayList<>(documents);
    sorted.sort(Comparator.comparingInt(WebDocument::getDocId));   // ← TIỀN ĐỀ bắt buộc
    for (WebDocument doc : sorted) {
        index.addDocument(doc);
    }
    return index;
}
```

Lớp thứ hai — `InvertedIndex` **tự ép**, biến một lỗi im lặng thành một lỗi
ồn ào ngay tại chỗ gây ra:

```java
if (docId <= lastDocId) {
    throw new IllegalArgumentException(
            "addDocument phai duoc goi theo docId TANG DAN de giu bat bien"
                    + " 'posting list sap xep theo docId'. docId truoc = " + lastDocId
                    + ", docId hien tai = " + docId
                    + ". Hay sap xep danh sach tai lieu truoc khi index.");
}
```

Bất biến này mở khoá **bốn** thứ, và đó chính là toàn bộ lý do nó tồn tại:

| Mở khoá | Thay vì |
|---|---|
| Giao posting list bằng two-pointer $O(m+n)$ (mục 4.2) | Sort lại $O(n\log n)$ mỗi truy vấn |
| Binary search $O(\log n)$ để tra tần suất/vị trí (mục 3.3) | Quét tuyến tính $O(n)$ |
| **Delta encoding** cho nén (`VByteCodec`) | Hiệu có thể âm → không mã hoá được |
| **Galloping skip** $O(\log d)$ (`PostingCursor`) | Bước tuần tự qua cả list dài |

> **Bài học tổng quát:** chọn đúng **bất biến** khi xây dựng cấu trúc dữ liệu
> thường có giá trị hơn tối ưu thuật toán về sau.

---

### 3.3. Binary search trên posting list

**Vấn đề.** Khi chấm điểm TF-IDF cho tài liệu `docId = 3.500`, cần biết term
`công_nghệ` xuất hiện bao nhiêu lần **trong đúng tài liệu đó**. Posting list
của `công_nghệ` có 1.639 mục. Quét tuyến tính là $O(1639)$ — và phải làm vậy
cho **mọi** ứng viên × **mọi** term.

**Ý tưởng.** Posting list đã sắp xếp theo `docId` (mục 3.2) → binary search.

**Mã thật.** `index/InvertedIndex.binarySearchPosting()` — **một** cài đặt duy
nhất, lộ ra cho hai scorer qua `SearchIndex.getTermFrequency(term, docId)`
(trước đây hàm này bị sao chép gần như y hệt ở ba nơi):

```java
private static int binarySearchPosting(List<Posting> postings, int docId) {
    int low = 0, high = postings.size() - 1;
    while (low <= high) {
        int mid = (low + high) >>> 1;        // ← >>> chứ không phải /2
        int midDocId = postings.get(mid).docId();
        if (midDocId == docId) {
            return mid;
        } else if (midDocId < docId) {
            low = mid + 1;
        } else {
            high = mid - 1;
        }
    }
    return -1;                               // term không xuất hiện trong tài liệu này
}

@Override
public int getTermFrequency(String term, int docId) {
    int position = binarySearchPosting(getPostings(term), docId);
    return position < 0 ? 0 : getPostings(term).get(position).termFrequency();
}
```

Chi tiết `(low + high) >>> 1` thay vì `(low + high) / 2`: với danh sách rất
lớn, `low + high` có thể **tràn `int` thành số âm**, và `/2` giữ nguyên dấu
âm → chỉ số âm. Dịch bit không dấu `>>>` xử lý đúng cả khi tràn. Đây là lỗi
kinh điển từng tồn tại nhiều năm trong `java.util.Arrays.binarySearch` của
chính JDK.

**Độ phức tạp.** $O(\log n)$ thay vì $O(n)$. Với `n = 1639`: 11 phép so sánh
thay vì 1.639.

---

### 3.4. Chỉ mục kép có dấu / không dấu

**Vấn đề.** Cần hỗ trợ gõ không dấu mà không phải xây và đồng bộ **hai** cấu
trúc dữ liệu riêng.

**Ý tưởng.** Dùng **cùng một** `LinkedHashMap`, chèn hai khoá cùng trỏ tới
các `Posting` giống nhau:

```
máy_tính → [Posting(doc1, 3, [5, 20, 88]), ...]
may_tinh → [Posting(doc1, 3, [5, 20, 88]), ...]    ← cùng nội dung
```

Nhờ vậy truy vấn không dấu hoạt động **mà không có thêm một dòng code nào ở
tầng truy vấn** — `CandidateResolver` không hề biết chuyện này tồn tại.

**Cái giá phải trả, nói cho công bằng:** số khoá trong chỉ mục tăng lên, và
`getDocumentFrequency` của một khoá không dấu có thể **lớn hơn thực tế** khi
hai từ có dấu khác nhau cùng rút về một dạng không dấu (`ngân` và `ngàn` đều
thành `ngan`). Đây chính là gốc rễ của lỗi bôi sáng snippet — xem mục 4.4 của
`ARCHITECTURE.md`.

Điều kiện `if` tránh chèn trùng khi từ vốn không có dấu (`web`, `robot`):

```java
if (!token.noDiacriticTerm().equals(token.term())) { ... }
```

---

### 3.5. Flyweight cho khoá term

**Vấn đề.** Tokenizer tạo chuỗi **mới** mỗi lần gặp một term:

```java
term = String.join("_", Arrays.copyOfRange(syllables, i, i + matchedLen));
```

$$5011 \text{ tài liệu} \times \approx 1400 \text{ tiếng} \approx \mathbf{7\ \text{triệu}}\ \texttt{String}$$

cho chỉ **136.768** giá trị phân biệt — tỷ lệ trùng lặp $\approx 51:1$. Mỗi
`String` tốn $\approx 44 + L$ byte.

**Ý tưởng.** Giữ một kho (pool) ánh xạ nội dung chuỗi sang **một instance
chuẩn tắc** duy nhất.

**Mã thật.** `index/TermDictionary.java`:

```java
public String intern(String term) {
    if (term == null) return null;
    String existing = pool.putIfAbsent(term, term);
    return existing != null ? existing : term;
}
```

`putIfAbsent` làm **cả hai việc trong một lần băm** — `containsKey` rồi `put`
sẽ băm hai lần, tức thêm $7 \times 10^7$ phép tính hash vô ích.

**Vì sao không dùng `String.intern()` của JDK:** nó dùng bảng chuỗi nội bộ
JVM — **không giải phóng được** (rò rỉ sau mỗi lần reindex), kích thước cấu
hình cứng, và **không đo được**. Pool tự quản lý thì kiểm soát được vòng đời
(`clear()`) và đo được (`size()`, `estimatedBytes()`).

**Độ phức tạp.** `intern` $O(L)$; bộ nhớ $O(\sum_{t \in V} L_t)$ — tổng ký tự
của các term **phân biệt**, không phụ thuộc số lần xuất hiện.

Chi tiết kèm định luật Zipf/Heaps: [`Math/03-index/TermDictionary.md`](Math/03-index/TermDictionary.md).

---

### 3.6. Nén chỉ mục bằng delta + variable-byte

**Vấn đề.** Posting list lưu `docId` là `int` — 4 byte mỗi số, kể cả khi số
đó là `3`. Hai tính chất của dữ liệu bị bỏ phí: danh sách **đã sắp xếp** (nên
hiệu nhỏ hơn giá trị tuyệt đối), và **số nhỏ không cần 4 byte**.

**Ý tưởng — hai bước.**

*Bước 1 — delta encoding.* Lưu hiệu giữa hai phần tử liên tiếp:

$$\delta_i = x_i - x_{i-1}, \qquad \bar{\delta} \approx \frac{N}{n}$$

```
gốc   : [3, 17, 19, 40, 1041]
delta : [3, 14,  2, 21, 1001]
```

Với term `công_nghệ` (1.639 mục trên 5.011 tài liệu): $\bar\delta \approx 3{,}06$.

*Bước 2 — variable-byte.* 7 bit thấp mỗi byte mang dữ liệu; bit cao nhất là
cờ *"còn byte nữa"*:

| Số byte | Khoảng giá trị |
|---|---|
| 1 | $0 \ldots 127$ |
| 2 | $128 \ldots 16\,383$ |
| 3 | $16\,384 \ldots 2\,097\,151$ |

**Mã thật.** `index/VByteCodec.java`:

```java
private static void writeVInt(ByteArrayOutputStream out, int value) {
    while ((value & ~0x7F) != 0) {          // còn bit ngoài 7 bit thấp
        out.write((value & 0x7F) | 0x80);   // ghi 7 bit + bật cờ "còn nữa"
        value >>>= 7;                       // >>> chứ không phải >> (số âm lặp vô hạn)
    }
    out.write(value & 0x7F);                // byte cuối: bit cao = 0
}
```

**Kết quả đo thật** (`VByteCodec.main`, posting list 1.639 mục):

```
Không nén (int)  : 6556 byte
Đã nén (VByte)   : 1639 byte
Tỷ lệ nén        : 25,0 % (tiết kiệm 75,0 %)
Giải nén đúng nguyên vẹn: true
```

**Độ phức tạp.** Mã hoá và giải mã đều $O(n)$ một lượt, không cấp phát trung
gian ngoài bộ đệm kết quả.

Chi tiết kèm ví dụ tính tay từng bit: [`Math/03-index/VByteCodec.md`](Math/03-index/VByteCodec.md).

---

## 4. Giai đoạn QUERY — xử lý truy vấn

### 4.1. Phân tích truy vấn bằng regex

**Vấn đề.** Truy vấn `"trình duyệt web" máy tính -giá` chứa ba loại thành
phần khác nhau, phải tách ra trước khi làm gì tiếp.

**Ý tưởng.** Một lần quét regex tìm cụm trong ngoặc kép, phần còn lại xử lý
tiền tố `-`.

| Thành phần | Giá trị | Ý nghĩa |
|---|---|---|
| `phrases` | `[[trình_duyệt_web]]` | Phải xuất hiện **liên tiếp** đúng thứ tự |
| `mustTerms` | `[máy_tính]` | Phải có (AND ngầm định) |
| `excludedTerms` | `[giá]` | Tài liệu chứa từ này bị loại |

**Mã thật.** `query/QueryParser.java`:

```java
private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]*)\"");
...
Matcher matcher = PHRASE_PATTERN.matcher(rawQuery);
StringBuilder remaining = new StringBuilder();
int lastEnd = 0;
while (matcher.find()) {
    remaining.append(rawQuery, lastEnd, matcher.start());   // giữ phần NGOÀI ngoặc kép
    if (!matcher.group(1).isBlank()) {
        phrasesRaw.add(matcher.group(1));
    }
    lastEnd = matcher.end();
}
remaining.append(rawQuery.substring(lastEnd));
```

**Điểm then chốt về tính đúng đắn — không được sai chỗ này.** Truy vấn phải
được tokenize bằng **chính** tokenizer đã dùng lúc index:

```java
public QueryParser(Tokenizer tokenizer) {
    this.tokenizer = tokenizer;
}
```

Nếu lúc index tạo ra `máy_tính` mà lúc truy vấn tạo ra `máy` + `tính` thì
**không bao giờ khớp** — và lỗi này im lặng, không có ngoại lệ nào được ném
ra, chỉ là kết quả rỗng một cách khó hiểu.

**Độ phức tạp.** $O(L)$ với `L` là độ dài chuỗi truy vấn.

**Hạn chế đã biết.** Dấu `-` chỉ loại trừ **một tiếng** ngay sau nó (giống
toán tử `-word` của Google). `-quảng cáo` chỉ loại trừ `quảng`, còn `cáo`
vẫn là `mustTerm`. Muốn loại trừ cả cụm phải viết `-"quảng cáo"` — **chưa hỗ
trợ**.

---

### 4.2. Two-pointer intersect / union

**Vấn đề.** Truy vấn `máy tính công nghệ` cần các tài liệu có **cả hai** term
— tức lấy **giao** của hai posting list.

**Ý tưởng.** Vì cả hai đã sắp xếp (mục 3.2), dùng kỹ thuật **two-pointer**
giống bước merge của merge sort: mỗi phần tử được xét đúng **một** lần.

**Mã giả và ví dụ chạy tay.**

```
A: [1, 3, 5, 7, 9]
B: [3, 5, 8]

i=0,j=0: A[0]=1 < B[0]=3  → i++
i=1,j=0: A[1]=3 = B[0]=3  → ghi 3, i++, j++
i=2,j=1: A[2]=5 = B[1]=5  → ghi 5, i++, j++
i=3,j=2: A[3]=7 < B[2]=8  → i++
i=4,j=2: A[4]=9 > B[2]=8  → j++
j hết → dừng

Kết quả: [3, 5]
```

**Mã thật.** `query/PostingListMerger.intersect()`:

```java
public static List<Integer> intersect(List<Integer> a, List<Integer> b) {
    List<Integer> result = new ArrayList<>();
    int i = 0, j = 0;
    while (i < a.size() && j < b.size()) {
        int docA = a.get(i);
        int docB = b.get(j);
        if (docA == docB) {
            result.add(docA); i++; j++;
        } else if (docA < docB) {
            i++;
        } else {
            j++;
        }
    }
    return result;
}
```

`union` gần như y hệt, chỉ khác là ghi cả phần tử nhỏ hơn thay vì bỏ, và có
hai vòng `while` dọn phần đuôi còn lại.

**Vì sao không dùng `HashSet.retainAll`?** Đo thực tế với 2 danh sách 500.000
phần tử:

| Cách làm | Thời gian trung bình/lần |
|---|---|
| Two-pointer `intersect` | **~10,0 ms** |
| `HashSet.retainAll` (không tính chi phí dựng HashSet) | ~15,5 ms (chậm hơn ~55%) |
| `HashSet.retainAll` (tính cả chi phí dựng 2 HashSet) | ~27,0 ms (chậm hơn ~2,7 lần) |

Two-pointer thắng ở **cả hai** kịch bản vì: không có chi phí băm và xử lý va
chạm, tận dụng trực tiếp tính đã-sắp-xếp có sẵn, và không cần cấp phát cấu
trúc trung gian nào. Trong hệ thống thật, posting list là `List<Posting>` lấy
thẳng từ chỉ mục nên **phải tính cả** chi phí dựng HashSet mỗi truy vấn —
dòng thứ 3 là so sánh công bằng nhất.

**Độ phức tạp.** $O(m+n)$ tuyệt đối, không có hằng số ẩn của hashing.

---

### 4.3. Sắp xếp shortest-first trước khi giao nhiều tập

**Vấn đề.** Khi truy vấn có 3+ term, **thứ tự giao rất quan trọng**.

**Ý tưởng.** Gọi `A` là kết quả giao sau `k` bước. Luôn có

$$
|A| \;\le\; \min\bigl(\text{các list đã xét}\bigr)
$$

Vậy nên bắt đầu từ list **ngắn nhất** để `|A|` nhỏ ngay từ đầu, khiến các
bước giao kế tiếp — mỗi bước tốn $O(\lvert A\rvert + \lvert\text{list ke tiep}\rvert)$ — rẻ hơn
đáng kể.

Ví dụ: `iPhone` (df = 5) và `của` (df = 4000)

| Thứ tự | Chi phí |
|---|---|
| Ngắn trước: `5 ∩ 4000` | duyệt 4.005 phần tử, kết quả ≤ 5 → bước sau rất rẻ |
| Dài trước | vẫn 4.005 ở bước này, nhưng kết quả trung gian có thể lớn → bước sau tốn hơn |

Đặc biệt lợi khi một term **hiếm** (df nhỏ) trộn với nhiều term **phổ biến**.

**Mã thật.** `PostingListMerger.intersectAll()`:

```java
List<List<Posting>> sorted = new ArrayList<>(postingLists);
sorted.sort(Comparator.comparingInt(List::size));       // ← shortest-first

List<Integer> result = docIdsOf(sorted.get(0));
for (int i = 1; i < sorted.size() && !result.isEmpty(); i++) {
    result = intersect(result, docIdsOf(sorted.get(i)));
}
return result;
```

Chú ý điều kiện `&& !result.isEmpty()`: giao rỗng thì **dừng ngay**, không
duyệt các list còn lại. Với AND ngầm định, rỗng là rỗng mãi.

Ngoài ra `CandidateResolver` còn một tối ưu sớm hơn nữa — thoát trước khi
gọi `intersectAll` nếu **bất kỳ** term nào có df = 0:

```java
for (String term : allRequiredTerms) {
    List<Posting> postings = index.getPostings(term);
    if (postings.isEmpty()) {
        // AND ngầm định: chỉ cần một term không xuất hiện là kết quả rỗng.
        return new ResolvedQuery(new ArrayList<>(), queryTermFrequency);
    }
    postingLists.add(postings);
}
```

---

### 4.4. Galloping search — nhảy cóc thay vì bước từng bước

**Vấn đề.** Shortest-first (mục 4.3) giảm kích thước kết quả trung gian, nhưng
**không** giảm số bước duyệt: giao list 5 phần tử với list 4.000 phần tử vẫn
tốn $O(m+n) = 4005$ bước, vì two-pointer thuần phải bước qua gần hết list dài.

Nhưng cả hai list **đã sắp xếp**. Sao phải bước từng bước khi chỉ cần 5 vị trí?

**Ý tưởng — galloping search** (exponential search), hai pha:

*Pha 1 — nhảy theo cấp số nhân* `1, 2, 4, 8, …` cho tới khi vượt mục tiêu. Sau
$k$ vòng bước nhảy là $2^k$, dừng khi $2^k \ge d$ với $d$ là khoảng cách thật.

*Pha 2 — binary search* trong đoạn vừa khoanh (độ dài $\le d$).

$$O(m + n) = 4005 \text{ bước} \quad\longrightarrow\quad O\!\left(m\log\frac{n}{m}\right) = 5 \times \log_2 800 \approx \mathbf{48}\ \text{bước}$$

**Mã thật.** `index/ArrayPostingCursor.skipTo()`:

```java
// Pha 1: nhảy theo cấp số nhân
int step = 1, low = index, high = index + step;
while (high < n && postings.get(high).docId() < targetDocId) {
    low = high;
    step <<= 1;                     // 1, 2, 4, 8, ...
    high = index + step;
}
if (high >= n) high = n - 1;

// Pha 2: binary search (lower_bound) trong đoạn (low, high]
int lo = low, hi = high;
while (lo < hi) {
    int mid = (lo + hi) >>> 1;      // >>> chống tràn
    if (postings.get(mid).docId() < targetDocId) lo = mid + 1;
    else                                          hi = mid;
}
```

**Điểm mạnh so với binary search thuần trên cả mảng:** chi phí phụ thuộc
**khoảng cách thật** $d$, **không phụ thuộc kích thước mảng** $n$. Khi hai
posting list chồng lấn nhiều — trường hợp phổ biến — $d$ nhỏ nên galloping
gần như miễn phí.

**Lợi ích thứ hai — không cấp phát.** Cursor duyệt thẳng trên dữ liệu gốc,
toàn bộ trạng thái là **một `int`**. Cách cũ vật chất hoá posting list thành
`List<Integer>`, mỗi `docId` bị autobox thành object 16 byte thay vì 4 —
$4000 \times 16 = \mathbf{64\ KB}$ rác GC mỗi lần gọi, nhân với $k$ term.

**Sentinel `NO_MORE = Integer.MAX_VALUE`** lớn hơn mọi docId hợp lệ, nên vòng
lặp giao *"tiến cursor có docId nhỏ hơn"* tự dừng đúng chỗ mà **không cần một
nhánh `if` riêng** kiểm tra hết list.

**Kiểm chứng.** `PostingCursorTest` đối chiếu galloping với **quét tuyến tính
ở mọi vị trí** từ 0 tới 1100 — phủ trọn trước phần tử đầu, sau phần tử cuối,
và trùng khớp chính xác.

Chi tiết kèm chứng minh cận bằng bất đẳng thức Jensen:
[`Math/06-datastructures/ArrayPostingCursor.md`](Math/06-datastructures/ArrayPostingCursor.md).

---

### 4.5. Khớp cụm từ theo vị trí liên tiếp

**Vấn đề.** `"trình duyệt web"` yêu cầu 3 từ xuất hiện **liên tiếp đúng thứ
tự**, không chỉ là cùng có mặt trong tài liệu.

**Ý tưởng.** Đây là lúc `positions` phát huy tác dụng: với mỗi vị trí xuất
hiện của từ **đầu tiên**, kiểm tra từ thứ `i` có nằm đúng ở `start + i`.

```
Trong doc5:
  trình  xuất hiện ở vị trí [2, 17]
  duyệt  xuất hiện ở vị trí [3, 40]
  web    xuất hiện ở vị trí [4, 41]

Thử start = 2: cần duyệt ở 3 ✅, web ở 4 ✅ → KHỚP
```

**Mã thật.** `PostingListMerger.matchesPhrase()`:

```java
public static boolean matchesPhrase(SearchIndex index, List<String> phraseTerms, int docId) {
    if (phraseTerms.isEmpty()) {
        return true;
    }
    List<Integer> firstPositions = index.getPositions(phraseTerms.get(0), docId);
    for (int start : firstPositions) {
        boolean allMatch = true;
        for (int i = 1; i < phraseTerms.size(); i++) {
            List<Integer> positions = index.getPositions(phraseTerms.get(i), docId);
            if (!positions.contains(start + i)) {
                allMatch = false;
                break;
            }
        }
        if (allMatch) {
            return true;
        }
    }
    return false;
}
```

**Độ phức tạp.** $O(p_1 \cdot k \cdot \log n)$ với `p₁` là số vị trí của từ đầu, `k` là
số từ trong cụm, `log n` là binary search trong `getPositions`.

> **Điểm còn tối ưu được:** `positions.contains(start + i)` là quét tuyến
> tính trên một danh sách vốn **đã sắp xếp** — có thể đổi sang binary search.
> Và `getPositions` bị gọi lại cho cùng một term ở mọi vòng `start`, có thể
> lấy trước một lần. Với số vị trí thực tế (vài chục) thì chưa thành vấn đề,
> nhưng đây là chỗ đáng sửa nếu mở rộng.

---

## 5. Giai đoạn RANK — xếp hạng

### 5.1. TF-IDF + cosine similarity

**Vấn đề.** Đã tìm được 1.639 tài liệu chứa `công nghệ`. Tài liệu nào **liên
quan nhất**?

**Ý tưởng.** Một tài liệu liên quan tới từ khoá khi: (1) từ khoá xuất hiện
**nhiều lần** trong nó — **TF**; và (2) từ khoá **hiếm gặp** trong toàn
corpus — **IDF**.

$$
\mathrm{tf}(t,d) = 1 + \log_{10} f_{t,d}
\qquad
\mathrm{idf}(t) = \log_{10}\frac{N}{\mathrm{df}_t}
\qquad
w_{t,d} = \mathrm{tf}(t,d)\cdot\mathrm{idf}(t)
$$

$$
\mathrm{sim}(q,d) = \cos\theta = \frac{\vec{q} \cdot \vec{d}}{\lVert\vec{q}\rVert\,\lVert\vec{d}\rVert}
\qquad\text{với}\qquad
\lVert\vec{d}\rVert \approx \sqrt{\lvert d \rvert}
$$

Vì sao TF dùng logarit: nếu dùng `tf` thô, tài liệu lặp từ khoá 100 lần được
điểm gấp 100 lần tài liệu có 1 lần — nhưng nó **không liên quan gấp 100 lần**,
chỉ là nhồi từ khoá. Logarit nén khoảng cách đó lại.

**Mã thật.** `ranking/TfIdfScorer.java`:

```java
public static double tf(int termFrequency) {
    return termFrequency > 0 ? 1 + Math.log10(termFrequency) : 0.0;
}

public static double idf(int totalDocs, int documentFrequency) {
    if (documentFrequency <= 0 || totalDocs <= 0) {
        return 0.0;
    }
    return Math.log10((double) totalDocs / documentFrequency);
}

@Override
public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
    int totalDocs = index.getTotalDocs();
    double dot = 0.0, queryNormSq = 0.0;

    for (Map.Entry<String, Integer> entry : queryTermFrequency.entrySet()) {
        List<Posting> postings = index.getPostings(entry.getKey());
        double idfValue = idf(totalDocs, postings.size());
        if (idfValue <= 0.0) {
            continue;   // term không tồn tại, HOẶC có trong TẤT CẢ tài liệu → không phân biệt được
        }
        double queryWeight = tf(entry.getValue()) * idfValue;
        queryNormSq += queryWeight * queryWeight;

        int docTermFrequency = index.getTermFrequency(term, docId);  // binary search O(log n)
        if (docTermFrequency > 0) {
            dot += queryWeight * tf(docTermFrequency) * idfValue;
        }
    }

    if (dot == 0.0) {
        return 0.0;
    }
    double queryNorm = Math.sqrt(queryNormSq);
    double docNorm = Math.sqrt(Math.max(index.getDocLength(docId), 1));   // ← xấp xỉ Lucene
    return dot / (queryNorm * docNorm);
}
```

**Vì sao chia cho độ dài vector.** Nếu không, tài liệu **dài** luôn thắng —
chỉ vì chứa nhiều từ hơn nên tích vô hướng lớn hơn, chứ không phải vì liên
quan hơn.

**Xấp xỉ `‖d‖ ≈ √(độ dài tài liệu)`** là xấp xỉ kinh điển của Lucene classic
Similarity. Tính `‖d‖` chuẩn xác đòi hỏi duyệt **mọi** term của tài liệu, tốn
$O(\lvert V\rvert)$ cho **mỗi** tài liệu — trong khi `getDocLength(docId)` là
$O(1)$ vì đã lưu sẵn.

Chú ý `Math.max(..., 1)`: chặn chia cho 0 với tài liệu rỗng.

**Độ phức tạp.** $O(q\log d)$ với `q` là số term phân biệt trong truy vấn,
`d` là độ dài posting list dài nhất.

---

### 5.2. BM25 (Okapi BM25)

**Vấn đề.** TF-IDF cosine có ba nhược điểm cụ thể, và BM25 sửa **đúng** ba
điểm đó. Đây là chuẩn công nghiệp hiện nay (Elasticsearch dùng làm mặc định),
nên nó là **baseline bắt buộc** để đối chiếu.

$$
\mathrm{score}(D,Q) = \sum_{q \in Q} \mathrm{IDF}(q)\cdot
\frac{f(q,D)\,\bigl(k_1 + 1\bigr)}
     {f(q,D) + k_1\left(1 - b + b\,\dfrac{|D|}{\mathrm{avgdl}}\right)}
$$

$$
\mathrm{IDF}(q) = \ln\!\left(1 + \frac{N - \mathrm{df}_q + 0{,}5}{\mathrm{df}_q + 0{,}5}\right)
$$

với `k₁ = 1,2` và `b = 0,75` — giá trị chuẩn qua nhiều thập kỷ thực nghiệm
TREC.

**Ba cải tiến so với TF-IDF:**

| # | Cải tiến | Chi tiết |
|---|---|---|
| 1 | **Bão hoà tần suất** | Ở TF-IDF, `tf = 1 + log₁₀(f)` vẫn **tăng vô hạn** theo `f`. Ở BM25, phân thức `f/(f + k₁(…))` tiến tới **trần** `k₁ + 1`. Lặp từ khoá gấp 10 lần chỉ tăng điểm 1,21 lần |
| 2 | **Chuẩn hoá độ dài có tham số** | TF-IDF chia **cứng** cho `√(docLength)`. BM25 có `b`: `b=0` không phạt gì, `b=1` chuẩn hoá hoàn toàn, `b=0,75` dung hoà. Có tham số nghĩa là **điều chỉnh được theo corpus** |
| 3 | **IDF không bao giờ âm** | `log₁₀(N/df)` **âm** khi `df > N/2` → tài liệu chứa term đó bị **trừ** điểm, vô lý. `ln(1 + …)` luôn dương |

**Mã thật.** `ranking/BM25Scorer.java`:

```java
public static final double DEFAULT_K1 = 1.2;
public static final double DEFAULT_B = 0.75;

public static double idf(int totalDocs, int documentFrequency) {
    if (documentFrequency <= 0 || totalDocs <= 0) {
        return 0.0;
    }
    return Math.log(1 + ((double) totalDocs - documentFrequency + 0.5) / (documentFrequency + 0.5));
}

@Override
public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
    ...
    int docLength = index.getDocLength(docId);
    // Hệ số chuẩn hoá độ dài, tính một lần cho cả truy vấn vì không phụ thuộc term.
    double lengthNorm = k1 * (1 - b + b * (docLength / avgDocLength));

    double total = 0.0;
    for (Map.Entry<String, Integer> entry : queryTermFrequency.entrySet()) {
        List<Posting> postings = index.getPostings(entry.getKey());
        int df = postings.size();
        if (df == 0) continue;
        int termFrequency = index.getTermFrequency(entry.getKey(), docId);
        if (termFrequency == 0) continue;
        double saturated = (termFrequency * (k1 + 1)) / (termFrequency + lengthNorm);
        total += idf(totalDocs, df) * saturated;
    }
    return total;
}
```

Chú ý `lengthNorm` được tính **ngoài** vòng lặp term — nó chỉ phụ thuộc tài
liệu, không phụ thuộc term, nên tính trong vòng lặp là lặp lại vô ích.

**Kết quả thực nghiệm** (200 truy vấn known-item, corpus 5.011 trang): BM25
thuần đạt MRR **0,8989** so với **0,8537** của TF-IDF cosine thuần, hơn
**+5,3%** — đúng như lý thuyết dự đoán. Chi tiết: `docs/EVALUATION.md`.

**Cả hai scorer cùng một giao diện.** `ranking/RelevanceScorer.java` cho phép
`EvaluationHarness` thay mô hình tính điểm mà không sửa gì ở `ResultRanker` —
đây chính là điều làm thí nghiệm ablation "chỉ thay một biến số" khả thi.

**Độ phức tạp.** $O(q\log d)$ — giống TF-IDF, cũng dùng binary search.

---

### 5.3. PageRank bằng power iteration

**Vấn đề.** TF-IDF và BM25 chỉ nhìn vào **nội dung** — mà nội dung có thể bị
giả mạo: ai cũng viết được một trang nhồi từ "máy tính" 500 lần. Cần một tín
hiệu **độc lập với truy vấn** và khó giả mạo hơn.

**Ý tưởng.** PageRank nhìn vào **cấu trúc liên kết**, thứ phụ thuộc vào hành
vi của **người khác**. Trực giác "người lướt web ngẫu nhiên": 85% thời gian
bấm một liên kết ngẫu nhiên trên trang hiện tại, 15% thời gian gõ một URL bất
kỳ. **PageRank của một trang = xác suất người đó đang ở trang đó tại một thời
điểm ngẫu nhiên.**

$$
PR(j) = \frac{1-d}{N} + d\left(
\sum_{i \,\rightarrow\, j} \frac{PR(i)}{L(i)}
\;+\; \frac{M_{\text{dangling}}}{N}
\right)
$$

| Thành phần | Ý nghĩa |
|---|---|
| `(1−d)/N` | Xác suất nhảy ngẫu nhiên (teleport) tới trang này |
| `Σ PR(i)/L(i)` | "Phiếu bầu" từ các trang trỏ tới, **chia đều** cho số outlink |
| `M_dangling/N` | Xử lý trang cụt (xem dưới) |

Điểm tinh tế: một trang **chia đều** PageRank của nó cho các trang nó trỏ
tới. Trang trỏ đi 100 link thì mỗi link chỉ mang 1/100 giá trị — nên **spam
link không có tác dụng**.

**Mã giả.**

```
POWER-ITERATION():
    PR[i] ← 1/N cho mọi i
    lặp:
        danglingSum ← Σ PR[i] với i là dangling node
        linkContribution ← M.multiply(PR)              # O(nnz)
        for mỗi j:
            PR_mới[j] ← (1−d)/N + d·linkContribution[j] + d·danglingSum/N
        diff ← Σ |PR_mới[j] − PR[j]|
        PR ← PR_mới
    cho tới khi diff < 1e−6 HOẶC đủ 100 vòng
```

**Mã thật.** `ranking/PageRankService.java`:

```java
private static final double DAMPING = 0.85;
private static final double EPSILON = 1e-6;
private static final int MAX_ITERATIONS = 100;
...
do {
    double danglingSum = 0.0;
    for (int i = 0; i < n; i++) {
        if (dangling[i]) danglingSum += pr[i];
    }
    double danglingContribution = DAMPING * danglingSum / n;

    double[] linkContribution = incoming.multiply(pr);       // O(nnz)
    double[] newPr = new double[n];
    diff = 0.0;
    for (int j = 0; j < n; j++) {
        newPr[j] = teleport + DAMPING * linkContribution[j] + danglingContribution;
        diff += Math.abs(newPr[j] - pr[j]);                  // chuẩn L1
    }
    pr = newPr;
    iteration++;
} while (diff >= EPSILON && iteration < MAX_ITERATIONS);
```

**Ba chi tiết cài đặt quan trọng, cả ba đều dễ làm sai:**

**(a) Không cần phép transpose.** Định nghĩa toán học là `M[i][j] = 1/L(i)`
nếu `i → j`, rồi phải tính `Mᵀ · PR`. Dự án **lưu ngược chiều ngay từ đầu** —
hàng `j` là danh sách các nguồn `i` trỏ tới `j`:

```java
incoming.set(targetIdx, idx, weight);   // set(hàng = đích, cột = nguồn)
```

Nhờ vậy `SparseMatrix.multiply` tính đúng `Mᵀ · PR` mà **không cần** thao tác
transpose riêng. Đây chỉ là cách chọn "chiều lưu" của ma trận.

**(b) Dangling node — cái bẫy kinh điển.** Trang **không có outlink nào** (
file PDF, trang cụt) làm "rò rỉ" xác suất ra khỏi hệ thống: người lướt vào đó
rồi mắc kẹt, và tổng PageRank tụt dần về 0 — vi phạm tính chất `Σ PR = 1`.
Cách xử lý: gom toàn bộ PageRank của các trang cụt rồi **phân phối đều** cho
tất cả `N` trang. Tương đương với việc người lướt gõ URL ngẫu nhiên khi mắc
kẹt.

**(c) Chỉ tính liên kết **trong** corpus, và bỏ self-link.**

```java
for (String outlink : doc.getOutlinks()) {
    Integer targetIdx = urlToIndex.get(outlink);
    if (targetIdx != null && targetIdx != idx) {   // ← trong corpus VÀ không tự trỏ
        outDegree[idx]++;
    }
}
```

Nghĩa là: trong 394.940 outlink thu được, chỉ **239.691** trở thành cạnh của
đồ thị PageRank — phần còn lại trỏ ra ngoài corpus. Hệ quả: một trang có
hàng trăm outlink nhưng tất cả đều trỏ ra ngoài thì vẫn là **dangling node**
theo định nghĩa này.

**Số vòng lặp thực đo:**

| Corpus | Số vòng lặp tới hội tụ |
|---|---|
| Đồ thị 6 node tự tạo (test đơn vị) | 1 – 28 |
| 150 trang, 1 domain | 44 |
| **5.011 trang, 6 domain** | **53** |

Ví dụ hội tụ **tức thì**: chu trình đối xứng A→B→C→A. Mỗi trang có đúng 1
liên kết vào và 1 liên kết ra, nên theo đối xứng `PR = 1/3` cho cả ba, và đó
đã là điểm bất động ngay từ vòng đầu. `PageRankServiceTest` kiểm chứng bằng
đúng **tính chất toán học** này (tổng ≈ 1, chu trình đối xứng cho điểm bằng
nhau) thay vì hardcode số.

**Vì sao bắt buộc dùng ma trận thưa.** Đồ thị liên kết là ma trận `N × N`:

```
Ma trận đặc:  5011 × 5011 × 8 byte = 191,5 MB
Thực tế chỉ có 239.691 ô khác 0 → adjacency list: ~3,7 MB
```

**Độ phức tạp.** $O(\text{iter}\cdot(\text{nnz} + N))$. Bộ nhớ $O(N + \text{nnz})$.

---

### 5.4. Kết hợp nhiều tín hiệu — và cái bẫy thang đo

#### Bản cũ: cộng tuyến tính

```
finalScore = α·relevance + β·pageRank + γ·titleBonus     ← BẢN CŨ
```

với `α = 0,6`, `β = 0,3`, `γ = 0,1` chôn cứng trong `ResultRanker`.

> ### ⚠️ Cái bẫy lớn nhất của dự án: thang đo không tương thích
>
> Công thức trên **ngầm giả định ba đại lượng cùng thang đo**. Đo thực tế
> trên 852 cặp (truy vấn, kết quả top-10):
>
> | Thành phần | Trung bình | Sau khi nhân trọng số |
> |---|---|---|
> | TF-IDF cosine | 0,177687 | 0,106612 ($\alpha = 0{,}6$) |
> | PageRank | 0,00035388 | 0,00010616 ($\beta = 0{,}3$) |
>
> $$\frac{\beta\,\overline{\text{PR}}}{\alpha\,\overline{\text{TF-IDF}}} \approx \mathbf{0{,}1\,\%}$$
>
> **Nguyên nhân:** PageRank là một **phân phối xác suất tổng bằng 1** trên
> 5.011 tài liệu, nên giá trị điển hình buộc phải quanh $1/N \approx 0{,}0002$
> — và **co lại** khi corpus lớn hơn. Cộng một độ tương tự với một phân phối
> xác suất là phép toán không có ý nghĩa; **bất kỳ $\beta$ nào cũng không sửa
> được**. Bằng chứng: quét $\beta$ từ 0,05 tới 0,80 (gấp 16 lần) chỉ làm MRR
> đổi 0,0040.

#### Bản hiện tại: Decorator, nhân thay vì cộng

Việc kết hợp tín hiệu đã chuyển khỏi `ResultRanker` sang chuỗi **Decorator**
bọc quanh `RelevanceScorer`:

$$\text{final} = \text{base} \times \bigl(1 + w \cdot \hat{p}\bigr),
\qquad \hat{p} = \frac{\log(1 + p/p_{\min})}{\log(1 + p_{\max}/p_{\min})} \in [0,1]$$

**Mã thật.** `ranking/decorator/PageRankBoostScorer.score()`:

```java
public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
    double base = inner.score(queryTermFrequency, docId, index);
    if (base == 0.0 || weight == 0.0) return base;          // thoát sớm
    double pageRank   = pageRankScores.getOrDefault(docId, minPageRank);
    double normalized = Math.log1p(pageRank / minPageRank) / logRange;   // ∈ [0,1]
    return base * (1 + weight * normalized);
}
```

Hai lý do:

1. **Logarit nén dải động** — PageRank trải trên nhiều bậc độ lớn; $\log$
   biến nó thành đại lượng cộng được, và chuẩn hoá về $[0,1]$ làm `weight`
   trở thành tỷ lệ đóng góp **thật**.
2. **Phép nhân bất biến với thang đo của scorer cơ sở** — đổi TF-IDF sang
   BM25 (thang 0,18 so với 12,1) **không cần chỉnh lại trọng số**. Có test
   khẳng định đúng tính chất đó (`pageRankBoostIsInvariantToBaseScorerScale`).

Lý do (2) giải thích luôn nghịch lý trong bảng đánh giá cũ: *"BM25 + PR +
title" (0,9089) thua "TF-IDF + PR + title" (0,9229)* — vì bộ trọng số cộng
được tinh chỉnh cho thang TF-IDF.

**Lắp ghép** do `ScorerFactory` lo, đọc từ `application.properties`:

```properties
app.ranking.scorer=bm25
app.ranking.beta=0.30     # trọng số PageRank
app.ranking.gamma=0.10    # trọng số khớp tiêu đề
```

Trọng số bằng 0 thì lớp bọc tương ứng **bị bỏ hẳn** — không trả chi phí cho
tín hiệu đã tắt.

> **Bài học tổng quát:** khi kết hợp nhiều tín hiệu, **luôn kiểm tra độ lớn
> thực tế** của từng thành phần trước khi diễn giải trọng số. Phân tích đầy
> đủ: [`Math/09-design-patterns/03-DECORATOR.md`](Math/09-design-patterns/03-DECORATOR.md)
> và mục 6 của [`Math/05-ranking/ResultRanker.md`](Math/05-ranking/ResultRanker.md).

---

### 5.5. Top-K bằng MinHeap (không sort toàn bộ)

**Vấn đề.** Có 1.639 ứng viên, cần 10 kết quả tốt nhất. Sort toàn bộ là
$O(n\log n)$ — lãng phí, vì ta **vứt đi 1.629 kết quả**.

**Ý tưởng.** Duy trì một min-heap kích thước tối đa `K`. Đỉnh heap là **phần
tử nhỏ nhất trong K tốt nhất hiện tại** — nên nó chính là "ngưỡng cửa": phần
tử mới chỉ cần so với đỉnh là biết có đáng vào hay không.

**Mã giả.**

```
TOP-K(items, k, cmp):
    heap ← min-heap rỗng theo cmp
    for mỗi item:
        nếu heap.size < k: heap.insert(item)
        ngược lại nếu cmp(item, heap.peek()) > 0:
            heap.extractMin(); heap.insert(item)
    lấy hết heap ra (được thứ tự tăng) rồi ĐẢO NGƯỢC
```

**Mã thật.** `datastructure/MinHeap.topK()`:

```java
public static <T> List<T> topK(Collection<T> items, int k, Comparator<T> cmp) {
    if (k <= 0) return new ArrayList<>();
    MinHeap<T> heap = new MinHeap<>(cmp);
    for (T item : items) {
        if (heap.size() < k) {
            heap.insert(item);
        } else if (cmp.compare(item, heap.peek()) > 0) {
            heap.extractMin();
            heap.insert(item);
        }
    }
    List<T> result = new ArrayList<>(heap.size());
    while (!heap.isEmpty()) {
        result.add(heap.extractMin());
    }
    java.util.Collections.reverse(result);      // ← tăng dần → giảm dần
    return result;
}
```

Bản thân heap là mảng, không con trỏ: phần tử tại `i` có con trái ở `2i+1`,
con phải ở `2i+2`, cha ở `(i−1)/2`.

**Độ phức tạp.** $O(n\log K)$. Với `n = 1639`, `K = 10`:

| Cách | Phép so sánh (xấp xỉ) |
|---|---|
| Sort toàn bộ | `1639 × log₂(1639)` ≈ **17.300** |
| Heap top-K | `1639 × log₂(10)` ≈ **5.400** |

Nhanh hơn ~3,2 lần, và khoảng cách càng giãn khi `n` lớn. Kỹ thuật này được
dùng ở **hai** nơi: `ResultRanker.rank()` và `Trie.getSuggestions()`.

---

### 5.6. Cửa sổ trượt sinh snippet

**Vấn đề.** Trong một bài viết 1.043 token, chọn đoạn 25 từ **chứa nhiều từ
khoá nhất**. Cách ngây thơ — với mỗi vị trí đếm lại số từ khoá trong cửa sổ —
là $O(n\cdot w)$.

**Ý tưởng.** Khi trượt sang phải một bước, chỉ có **một** từ ra khỏi cửa sổ
và **một** từ vào:

```
count = count − (từ vừa ra là từ khoá ? 1 : 0)
              + (từ vừa vào là từ khoá ? 1 : 0)
```

Cập nhật $O(1)$ mỗi bước → tổng **$O(n)$**.

**Mã thật.** `ranking/ResultRanker.buildSnippet()`:

```java
private static final int SNIPPET_WINDOW_SIZE = 25;
...
boolean[] isMatch = new boolean[words.length];
for (int i = 0; i < words.length; i++) {
    isMatch[i] = queryKeywordSyllables.matches(stripPunctuation(words[i]));
}

int windowSize = Math.min(SNIPPET_WINDOW_SIZE, words.length);
int currentMatches = 0;
for (int i = 0; i < windowSize; i++) {
    if (isMatch[i]) currentMatches++;
}
int bestStart = 0, bestMatches = currentMatches;
for (int start = 1; start + windowSize <= words.length; start++) {
    if (isMatch[start - 1])              currentMatches--;   // ra khỏi cửa sổ
    if (isMatch[start + windowSize - 1]) currentMatches++;   // vào cửa sổ
    if (currentMatches > bestMatches) {
        bestMatches = currentMatches;
        bestStart = start;
    }
}
```

Hai chi tiết:

- **Tiền xử lý `isMatch[]` một lần** thay vì gọi `matches()` lại ở mỗi vòng
  trượt. Không có bước này thì phép so khớp (có cả bỏ dấu) bị lặp
  `windowSize` lần cho mỗi từ.
- **`currentMatches > bestMatches`** (dấu `>` chặt) → khi có nhiều cửa sổ
  cùng số khớp thì lấy cửa sổ **sớm nhất**. Với bài báo thì đoạn đầu thường
  là đoạn dẫn, nên đây là mặc định tốt.

**Chỉ sinh snippet cho top-N.** Đây là lỗi hiệu năng thật đã gặp trong dự án:
`buildSnippet()` từng được gọi cho **mọi** ứng viên rồi mới cắt top-N. Với
500 ứng viên thì 490 snippet bị tạo ra rồi vứt đi ngay. Sửa thành ba bước —
chấm điểm → lấy top-K → **chỉ** sinh snippet cho K sống sót — hạ chi phí từ
$O(c\cdot\lvert d\rvert)$ xuống $O(\text{topN}\cdot\lvert d\rvert)$.

**Độ phức tạp.** $O(\lvert d\rvert)$ cho một snippet;
$O(\text{topN}\cdot\lvert d\rvert)$ cho cả truy vấn.

---

## 6. Giai đoạn EVALUATE — đo chất lượng

> **Vì sao phần này tồn tại.** Đo được "truy vấn mất 3,41 ms" và "cache hit
> rate 90%" là đo **tốc độ**. Nó không trả lời được câu hỏi quan trọng nhất:
> **kết quả trả về có đúng không?** Một hệ thống trả về kết quả sai trong 1 ms
> vẫn vô dụng.

Tất cả cài đặt trong `eval/EvaluationMetrics.java`. Quy ước chung: kết quả là
`List<String>` các **URL** đã xếp hạng; nhãn liên quan (qrels) là
`Map<URL, mức độ>` với 0 = không liên quan, 1 = liên quan, 2 = rất liên quan.

**Vì sao dùng URL làm định danh chứ không phải `docId`:** `docId` được gán
lại mỗi lần crawl, nên nhãn gán tay sẽ hỏng hết sau lần crawl kế tiếp; URL
thì ổn định.

### 6.1. Precision@k, Recall@k, F1@k

$$
P@k = \frac{\lvert\{\text{liên quan}\} \cap \{k \text{ đầu}\}\rvert}{k}
\qquad
R@k = \frac{\lvert\{\text{liên quan}\} \cap \{k \text{ đầu}\}\rvert}{\lvert\{\text{liên quan}\}\rvert}
$$

**Quyết định thiết kế đáng bảo vệ:** mẫu số của `P@k` là **`k`**, không phải
`min(k, số kết quả trả về)` — đúng quy ước TREC.

```java
public static double precisionAtK(List<String> ranked, Map<String, Integer> qrels, int k) {
    if (k <= 0) return 0.0;
    int hits = 0;
    int limit = Math.min(k, ranked.size());
    for (int i = 0; i < limit; i++) {
        if (isRelevant(qrels, ranked.get(i))) hits++;
    }
    return (double) hits / k;          // ← chia cho k, KHÔNG phải limit
}
```

Lý do: một hệ thống trả về 3 kết quả đúng cả 3 **không nên** được chấm
`P@10 = 1,0` ngang với hệ thống trả đủ 10 kết quả đúng cả 10. Trả về quá ít
kết quả **tự nó** là một khiếm khuyết và phải bị phạt.

`F1@k` là trung bình điều hoà của `P@k` và `R@k`.

### 6.2. Average Precision và MAP

$$
AP = \frac{1}{R}\sum_{i\,:\,rel_i = 1} P@i
\qquad
\mathrm{MAP} = \frac{1}{|Q|}\sum_{q \in Q} AP(q)
$$

```java
public static double averagePrecision(List<String> ranked, Map<String, Integer> qrels) {
    long totalRelevant = countRelevant(qrels);
    if (totalRelevant == 0) return 0.0;
    double sumPrecision = 0.0;
    int hits = 0;
    for (int i = 0; i < ranked.size(); i++) {
        if (isRelevant(qrels, ranked.get(i))) {
            hits++;
            sumPrecision += (double) hits / (i + 1);   // Precision tại đúng vị trí này
        }
    }
    return sumPrecision / totalRelevant;    // ← chia cho TỔNG số liên quan
}
```

Chia cho **tổng** số tài liệu liên quan (không phải số tìm được), nên **bỏ
sót vẫn bị phạt**.

**Vì sao cần MAP khi đã có `P@k`?** Vì `P@k` **không nhạy với thứ tự**. Hai
hệ thống cùng `P@4 = 0,5`:

$$
\text{Hệ A: } [\checkmark, \checkmark, \times, \times]
\;\Rightarrow\; AP = \frac{\tfrac{1}{1} + \tfrac{2}{2}}{2} = \mathbf{1{,}00}
$$

$$
\text{Hệ B: } [\times, \times, \checkmark, \checkmark]
\;\Rightarrow\; AP = \frac{\tfrac{1}{3} + \tfrac{2}{4}}{2} = \mathbf{0{,}42}
$$

`P@4` không phân biệt được, MAP thì có. Mà người dùng thật **luôn** nhìn kết
quả đầu tiên trước.

### 6.3. nDCG@k

$$
DCG@k = \sum_{i=1}^{k} \frac{2^{rel_i} - 1}{\log_2(i+1)}
\qquad
nDCG@k = \frac{DCG@k}{IDCG@k}
$$

Đây là độ đo **duy nhất** dùng được mức độ liên quan **nhiều bậc** (0/1/2).

```java
private static double gain(int grade) {
    return Math.pow(2, grade) - 1;
}

/** Hệ số chiết khấu theo vị trí (i tính từ 0): log2(i + 2) = log2(hạng + 1). */
private static double discount(int zeroBasedIndex) {
    return Math.log(zeroBasedIndex + 2) / Math.log(2);
}
```

**Vì sao độ lợi hàm mũ `2^rel − 1` thay vì tuyến tính `rel`:** với thang
0/1/2 thì "rất liên quan" được 3 điểm còn "liên quan" được 1 điểm — tỷ lệ
**3:1** thay vì 2:1. Cách tuyến tính không phản ánh đúng thực tế là người
dùng quan tâm kết quả xuất sắc **hơn nhiều** so với kết quả tạm được.

`IDCG@k` là `DCG@k` của thứ tự **lý tưởng** (sắp mọi nhãn giảm dần), nên
`nDCG` luôn trong `[0, 1]` và so sánh được giữa các truy vấn có số tài liệu
liên quan khác nhau.

### 6.4. MRR và Success@k

$$
\mathrm{MRR} = \frac{1}{|Q|}\sum_{q \in Q} \frac{1}{\mathrm{rank}_q}
$$

```java
public static double reciprocalRank(List<String> ranked, String targetUrl) {
    int rank = ranked.indexOf(targetUrl);
    return rank < 0 ? 0.0 : 1.0 / (rank + 1);
}

public static double successAtK(List<String> ranked, String targetUrl, int k) {
    int rank = ranked.indexOf(targetUrl);
    return rank >= 0 && rank < k ? 1.0 : 0.0;
}
```

Hạng 1 được 1,0; hạng 2 được 0,5; hạng 10 được 0,1; không tìm thấy được 0.
Đây là độ đo **phù hợp nhất cho known-item search**: người dùng chỉ cần một
kết quả đúng, và điều duy nhất quan trọng là nó nằm ở hạng bao nhiêu.

Có **hai** phiên bản `reciprocalRank`: một nhận `qrels` (nhiều tài liệu liên
quan), một nhận `targetUrl` (đúng một đáp án) — dùng cho known-item.

### 6.5. Sinh truy vấn known-item

**Vấn đề.** Muốn tính các độ đo trên thì phải biết tài liệu nào liên quan —
mà cái đó thường phải **người gán tay**, vừa tốn công vừa chủ quan.

**Ý tưởng.** Lật ngược bài toán: thay vì hỏi "tài liệu nào liên quan tới truy
vấn này", ta **chọn trước một tài liệu**, sinh truy vấn từ chính các từ khoá
đặc trưng nhất của nó, và đáp án đúng hiển nhiên là tài liệu đó. Mô phỏng
đúng tình huống người dùng nhớ mang máng một bài báo rồi gõ vài từ khoá tìm
lại.

> **Chi tiết dễ làm sai nhất.** Nếu chọn các term **hiếm nhất** (df = 1) thì
> phép giao posting list chỉ còn đúng một tài liệu — **hệ thống nào cũng đạt
> MRR = 1,0** và bài đánh giá mất hết ý nghĩa phân biệt.

**Mã thật.** `eval/KnownItemQueryGenerator.java`:

```java
public static final int MIN_DF = 3;
public static final double MAX_DF_RATIO = 0.10;
private static final double TITLE_BOOST = 2.0;
...
int df = postings.size();
if (df < MIN_DF || df > maxDf) {
    continue;
}
double score = TfIdfScorer.tf(entry.getValue()) * TfIdfScorer.idf(totalDocs, df);
if (titleTerms.contains(term)) {
    score *= TITLE_BOOST;       // term ở tiêu đề chính là thứ người dùng nhớ
}
```

Ba quyết định:

| Quyết định | Lý do |
|---|---|
| `df ≥ 3` | Loại term quá hiếm khiến truy vấn trở nên tầm thường; đồng thời loại nhiễu như lỗi chính tả, mã số |
| `df ≤ 10% corpus` | Loại term quá phổ biến, gần như không mang thông tin phân biệt |
| Nhân đôi điểm cho term ở tiêu đề | Đó chính là thứ người dùng nhớ và gõ lại |

**Tính tái lập** — điều kiện bắt buộc để con số trong báo cáo kiểm chứng
được:

```java
docIds.sort(Integer::compareTo);                       // sắp trước để ổn định
java.util.Collections.shuffle(docIds, new Random(seed));  // seed = 42
```

Và loại truy vấn trùng, vì hai tài liệu sinh ra cùng một truy vấn thì ground
truth nhập nhằng:

```java
if (!usedQueryTexts.add(queryText)) {
    continue;
}
```

### 6.6. TREC pooling

**Vấn đề.** Không ai gán nhãn nổi 5.011 tài liệu × 30 truy vấn = **150.000
lượt** đánh giá.

**Ý tưởng.** Chỉ gán nhãn **phần hợp của top-k từ nhiều hệ thống khác nhau**.
Giả định nền tảng: tài liệu thực sự liên quan gần như chắc chắn sẽ được **ít
nhất một** hệ thống đưa lên top; tài liệu không hệ thống nào đưa lên top thì
coi như không liên quan.

Khối lượng giảm từ 150.000 xuống **vài trăm**, mà thứ tự xếp hạng giữa các hệ
thống hầu như không đổi. Cài trong `eval/PoolBuilder.java`, chạy qua
`QrelsEvaluationRunner`.

---

## 7. Giai đoạn SERVE — phục vụ và gợi ý

### 7.1. Trie prefix search + DFS + top-K

**Vấn đề.** Người dùng gõ `cong`, cần gợi ý ngay `công nghệ`, `công ty`… Yêu
cầu: phản hồi trong vài milli-giây, ở **mỗi lần nhấn phím**.

**Ý tưởng.** **Trie** (cây tiền tố): mỗi cạnh là một ký tự, đường đi từ gốc
tới một node là một tiền tố.

**Mã thật.** `datastructure/Trie.getSuggestions()`:

```java
public List<String> getSuggestions(String prefix, int limit) {
    String normalizedPrefix = prefix == null ? "" : normalize(prefix);
    TrieNode prefixNode = findNode(normalizedPrefix);          // O(L)
    if (prefixNode == null) return result;

    List<WordFrequency> candidates = new ArrayList<>();
    collectWords(prefixNode, new StringBuilder(normalizedPrefix), candidates);  // DFS, O(m)

    // Gộp các mục trùng chuỗi hiển thị: cùng một gợi ý được chèn hai lần
    // (khoá có dấu và khoá không dấu) nên một tiền tố ngắn có thể chạm
    // tới cả hai node và làm gợi ý bị lặp.
    Map<String, Integer> bestFrequency = new LinkedHashMap<>();
    for (WordFrequency wf : candidates) {
        bestFrequency.merge(wf.word, wf.frequency, Math::max);
    }
    ...
    List<WordFrequency> top = MinHeap.topK(deduplicated, limit,
            Comparator.comparingInt(wf -> wf.frequency));      // O(m log k)
}
```

**Vấn đề riêng của tiếng Việt.** Trie khớp **từng ký tự chính xác**, nên tiền
tố `cong` **không bao giờ** đi tới được nhánh `công nghệ`.

**Giải pháp: tách khoá tra cứu khỏi chuỗi hiển thị.** Chèn cùng một mục hai
lần — một lần dưới khoá có dấu, một lần dưới khoá không dấu — nhưng **cả hai
node ghi nhớ cùng một chuỗi hiển thị có dấu**:

```java
suggestTrie.insert(phrase, phrase, frequency);
String withoutDiacritics = VietnameseTokenizer.stripDiacritics(phrase);
if (!withoutDiacritics.equals(phrase)) {
    suggestTrie.insert(withoutDiacritics, phrase, frequency);
}
```

Gõ kiểu nào cũng ra, mà thứ hiển thị **luôn đúng chính tả**. Chính vì chèn
hai lần nên mới cần bước gộp trùng ở trên.

**Nguồn dữ liệu gợi ý cũng quan trọng** — xem mục 3.4 của `ARCHITECTURE.md`
để biết ba lỗi đã sửa (chèn nguyên tiêu đề, chèn tiếng lẻ, quên `clear()`).

**Độ phức tạp.** `insert` $O(L)$; `search` $O(L)$;
`getSuggestions` $O(L + m\log k)$ với `m` là số từ trong cây con của prefix.
Bộ nhớ $O(\textstyle\sum \lvert w_i\rvert)$, tiết kiệm hơn khi nhiều từ chung
tiền tố.

---

### 7.2. LRU eviction (Doubly Linked List + HashMap)

**Vấn đề.** Truy vấn phổ biến được lặp lại rất nhiều. Cache kết quả theo khoá
`query + page + size`, nhưng bộ nhớ có hạn nên phải loại bỏ mục nào đó khi
đầy — **loại mục nào?**

**Ý tưởng.** **LRU (Least Recently Used)**: loại mục **lâu nhất không được
dùng**. Cần hai thao tác đồng thời $O(1)$: tra cứu theo khoá, và di chuyển
một mục lên đầu thứ tự sử dụng. Một cấu trúc không làm được cả hai, nên ghép
hai cấu trúc:

```
HashMap:   khoá → node          (tra cứu O(1))
Danh sách: MRU ⟷ ... ⟷ LRU      (thứ tự sử dụng)
```

**Mã thật.** `datastructure/LRUCache.java`:

```java
private void addToFront(Node<K, V> node) {
    node.prev = head;
    node.next = head.next;
    head.next.prev = node;
    head.next = node;
}

private void removeNode(Node<K, V> node) {
    node.prev.next = node.next;
    node.next.prev = node.prev;
}

private void moveToFront(Node<K, V> node) {
    removeNode(node);
    addToFront(node);
}
```

**Ba câu hỏi hay bị hỏi, và câu trả lời:**

**(a) Vì sao danh sách liên kết *đôi*?** Để xoá một node ở **giữa** danh
sách trong $O(1)$, cần biết **cả** node trước và node sau. Danh sách liên kết
đơn phải duyệt từ đầu để tìm node trước → $O(n)$.

**(b) Vì sao 2 sentinel node?** Hai node giả ở đầu và cuối, không chứa dữ
liệu. Nhờ chúng, `removeNode` chỉ cần **2 dòng** và **không bao giờ** phải
kiểm tra `null` cho trường hợp đặc biệt ở biên — mọi node thật đều chắc chắn
có `prev` và `next`.

**(c) Vì sao `get()` dùng write lock?** Xem mục 4.5 của `ARCHITECTURE.md` —
`get()` phải `moveToFront`, tức là một thao tác **ghi**.

**Vì sao tự viết thay vì dùng `LinkedHashMap`?** `LinkedHashMap` với
`accessOrder = true` và override `removeEldestEntry` làm được LRU "miễn phí".
Tự viết là để **chứng minh hiểu cơ chế** — đúng yêu cầu cốt lõi của đồ án
DSA. Xem mục 2.4 của `DSA-REPORT.md`.

**Độ phức tạp.** `get` và `put` đều $O(1)$. Bộ nhớ $O(\text{capacity})$, mặc định
200 mục.

Đo thực tế qua HTTP: cache miss 34,5 ms → cache hit 12,8 ms (nhanh **2,7
lần**; phần lớn thời gian còn lại là chi phí round-trip HTTP, không phải xử
lý tìm kiếm).

---

## 8. Phía trình duyệt (Electron + TypeScript)

### 8.1. Stack (LIFO) cho back/forward

**Ý tưởng.** Hai `Stack<string>` cho **mỗi tab**: `backStack` và
`forwardStack`.

```
recordNavigation(url mới):  push URL hiện tại vào backStack
                            CLEAR forwardStack        ← chi tiết quan trọng
                            currentUrl ← url mới
goBack():   pop backStack → push URL hiện tại vào forwardStack → trả về URL vừa pop
goForward(): đối xứng với goBack()
```

Chi tiết `CLEAR forwardStack` là điều làm mô hình này **đúng như trình duyệt
thật**: khi đang ở giữa lịch sử mà điều hướng sang trang mới, nhánh "tiến"
cũ bị bỏ hẳn.

**Bẫy cài đặt.** Cần một cờ `suppressNextRecord` để `recordNavigation` không
push lại vào stack khi việc điều hướng **do chính** `goBack`/`goForward` gây
ra — không có nó thì bấm Back sẽ vô tình ghi thêm một mục lịch sử mới.

**Đóng gói.** `lib/Stack.ts` bọc mảng JS lại; `historyStore` **không** được
gọi trực tiếp `array.push/pop` mà phải qua method của class — để thể hiện
đúng tính đóng gói của cấu trúc Stack.

**Độ phức tạp.** `push` / `pop` / `peek` đều $O(1)$.

### 8.2. Trie prefix search bằng TypeScript

`lib/BookmarkTrie.ts` — cài đặt **song song** với `Trie.java`, cùng ý tưởng
(`children` là `Map<ký tự, node>`, `isEndOfWord` đánh dấu từ hoàn chỉnh) để
so sánh hai cách cài đặt Java và TypeScript trong báo cáo.

Khác biệt có ý nghĩa: mỗi node kết thúc từ lưu **một danh sách** `bookmarkIds`
thay vì một từ khoá duy nhất, vì nhiều bookmark khác nhau có thể có cùng một
từ trong tiêu đề (`tin tức công nghệ` và `tin tức thể thao` đều có `tin`).

**Đánh đổi được ghi rõ:** Trie được **xây lại** mỗi lần gọi `searchByPrefix`
(không lưu thường trú trong Zustand state, vì Trie không serialize được sang
JSON để persist). Chấp nhận $O(B)$ mỗi lần tìm, vì số bookmark
thực tế rất nhỏ, đổi lại đơn giản hoá đáng kể việc đồng bộ Trie với cây khi
thêm/xoá.

---

## Tóm tắt: toàn bộ luồng thuật toán cho MỘT truy vấn

```
"công nghệ" từ người dùng
   │
   ├─ QueryParser.parse()                       O(L), regex + CÙNG tokenizer lúc index
   │     → mustTerms=[công_nghệ], phrases=[], excludedTerms=[]
   │
   ├─ CandidateResolver.resolve()
   │     ├─ InvertedIndex.getPostings()          O(1) tra LinkedHashMap
   │     ├─ (df = 0 ở bất kỳ term → trả rỗng ngay)
   │     ├─ PostingListMerger.intersectAll()     O(Σ|list|), two-pointer + shortest-first
   │     │     → 1.639 docId ứng viên
   │     ├─ PhraseNode  — filter-and-refine      giao thô rồi khớp vị trí liên tiếp
   │     └─ NotNode.evaluateAgainst()            two-pointer O(m+n)
   │
   ├─ CandidateFilter chain  — Chain of Responsibility
   │     ├─ DomainFilter          (site:vnexpress.net)
   │     └─ MaxCandidatesFilter   (chặn trên 10.000)
   │
   ├─ ResultRanker.rank()  — BA BƯỚC TÁCH RỜI
   │     ├─ BƯỚC 1: chấm điểm mọi ứng viên       O(c · q · log d)
   │     │     scorer.score(...) — một lời gọi đa hình:
   │     │       TitleBoostScorer( PageRankBoostScorer( BM25Scorer ))
   │     │       do ScorerFactory lắp từ application.properties
   │     │       final = base × (1 + w·p̂)   ← NHÂN, bất biến với thang đo
   │     ├─ BƯỚC 2: MinHeap.topK(topN)           O(c log topN), KHÔNG sort toàn bộ
   │     └─ BƯỚC 3: SnippetBuilder CHỈ cho topN  O(topN · docLength), cửa sổ trượt
   │
   ├─ cắt trang [fromIndex, toIndex)
   ├─ LRUCache.put(cacheKey, response)           O(1)
   └─ ghi truy vấn vào Trie gợi ý                O(L)
                    → JSON trả về client → highlight <mark>
```

## Đọc thêm

| Tài liệu | Nội dung |
|---|---|
| [`SEARCH-ENGINE-101.md`](SEARCH-ENGINE-101.md) | Lý thuyết IR đầy đủ, ví dụ tính tay chi tiết |
| [`DSA-REPORT.md`](DSA-REPORT.md) | Bảng Big-O đầy đủ, số liệu đo thực tế, so sánh có đối chứng |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Sơ đồ kiến trúc, sequence diagram, các quyết định thiết kế |
| [`EVALUATION.md`](EVALUATION.md) | Kết quả đánh giá chất lượng (sinh tự động) |
| [`GIN-BASELINE.md`](GIN-BASELINE.md) | Đối chứng với PostgreSQL GIN (sinh tự động) |

### Công trình gốc nên trích dẫn

| Chủ đề | Nguồn |
|---|---|
| PageRank | Brin & Page (1998), *The Anatomy of a Large-Scale Hypertextual Web Search Engine* |
| BM25 | Robertson & Sparck Jones (1976); Robertson & Zaragoza (2009) |
| Kiến trúc crawler | Heydon & Najork (1999), *Mercator: A Scalable, Extensible Web Crawler* |
| Bloom Filter | Bloom (1970); Kirsch & Mitzenmacher (2008) — double hashing |
| Đánh giá IR | Manning, Raghavan & Schütze (2008), *Introduction to Information Retrieval* — chương 8 |
| Pooling | Voorhees & Harman (2005), *TREC: Experiment and Evaluation in Information Retrieval* |
