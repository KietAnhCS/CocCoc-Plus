# UrlFrontier — hàng đợi ưu tiên tách theo host (mô hình Mercator)

**File nguồn:** `search-engine/src/main/java/com/vnsearch/datastructure/UrlFrontier.java`
**Việc nó làm:** Quyết định **crawl URL nào tiếp theo**, vừa theo độ ưu tiên vừa tôn trọng giới hạn 1 request/giây cho mỗi host.

> 📖 Chưa quen ký hiệu toán? Đọc [00 — Từ điển ký hiệu toán](../00-KY-HIEU-TOAN.md) trước.

---

## 📌 Hiểu trong 30 giây

Frontier là "danh sách việc cần làm" của crawler. Nó phải giải **hai bài toán cùng lúc**, và chính sự xung đột giữa hai bài toán này là toàn bộ nội dung thú vị của lớp:

1. **Chọn URL tốt nhất** — trang nào quan trọng hơn thì crawl trước.
2. **Không được spam host** — mỗi host tối đa 1 request/giây.

Riêng lẻ thì mỗi bài toán đều dễ: bài 1 dùng heap, bài 2 dùng một bảng `host → thời điểm truy cập cuối`. Ghép lại thì **hỏng**: URL ưu tiên cao nhất rất có thể thuộc host vừa mới truy cập, nên không dùng được — mà heap chỉ cho ta lấy phần tử đỉnh.

Bản đầu tiên của dự án dùng **một heap toàn cục** và đã thực sự **đứng hình** khi crawl 5.000 trang. Lời giải là mô hình **Mercator**: thay một heap bằng `Map<host, MinHeap>`.

---

## 1. Điểm ưu tiên — công thức và ba quyết định đằng sau nó

```java
private double computePriority(String url, int depth, int knownBacklinks) {
    double score = 0;
    score -= depth * 2.0;                          // càng sâu càng ít ưu tiên
    score += Math.min(knownBacklinks, 50) * 0.5;   // nhiều backlink -> ưu tiên hơn
    if (isVnDomain(url)) {
        score += 5.0;                              // ưu tiên domain .vn theo yêu cầu đề bài
    }
    return score;
}
```

Viết thành công thức:

$$\text{priority}(u) \;=\; -2\,\text{depth}(u) \;+\; 0{,}5 \cdot \min\bigl(\text{backlinks}(u),\, 50\bigr) \;+\; 5 \cdot \mathbb{1}\bigl[u \in \texttt{.vn}\bigr]$$

**Bảng giá trị với các trường hợp thật:**

| URL | depth | backlinks | .vn | priority |
|---|---|---|---|---|
| `https://vnexpress.net/` (seed) | 0 | 10 | không | $0 + 5 + 0 = \mathbf{5{,}0}$ |
| `https://tuoitre.vn/` (seed) | 0 | 10 | **có** | $0 + 5 + 5 = \mathbf{10{,}0}$ |
| Bài viết depth 1 | 1 | 1 | có | $-2 + 0{,}5 + 5 = \mathbf{3{,}5}$ |
| Bài viết depth 3 | 3 | 1 | có | $-6 + 0{,}5 + 5 = \mathbf{-0{,}5}$ |
| Trang cực nóng, depth 2 | 2 | 5000 | không | $-4 + 25 + 0 = \mathbf{21{,}0}$ |

Ba chi tiết đáng chú ý trong công thức này:

### 1.1 `min(backlinks, 50)` — chặn trên bắt buộc phải có

Không có nó, dòng cuối bảng trên sẽ là $-4 + 2500 = 2496$, áp đảo hoàn toàn mọi tín hiệu khác. Hàng đợi ưu tiên khi đó **thoái hoá thành "chỉ xét backlink"** và crawler không còn giống BFS nữa.

Chặn ở 50 nghĩa là: đóng góp tối đa của backlink là $25$ điểm, tương đương **12,5 lớp độ sâu**. Vẫn đủ mạnh để một trang rất quan trọng ở sâu được kéo lên, nhưng không đủ để phá vỡ trật tự theo lớp.

### 1.2 Hệ số $-2$ cho độ sâu lớn hơn hệ số $0{,}5$ cho backlink

Nghĩa là **giảm một lớp độ sâu quan trọng hơn có thêm 4 backlink**:

$$2 \text{ điểm (1 lớp)} \;=\; 0{,}5 \times 4 \text{ backlink}$$

Đây chính là điều làm thuật toán vẫn *giống BFS* thay vì biến thành thuần greedy theo backlink. BFS quan trọng vì các trang gần seed thường là trang chủ và trang chuyên mục — những trang quan trọng nhất của một site.

### 1.3 `+5` cho `.vn` là yêu cầu đề bài, không phải nguyên lý IR

Nói rõ điều này trong báo cáo là chuyện phải làm: đây là một **thiên lệch có chủ ý** vì dự án xây máy tìm kiếm tiếng Việt, không phải một tín hiệu chất lượng phổ quát.

> **Hạn chế của công thức:** `knownBacklinks` được truyền vào cứng bằng `1` cho mọi outlink (`frontier.addUrl(outlink, task.depth() + 1, 1)`), và `10` cho seed. Nghĩa là **thành phần backlink hiện đang là hằng số**, không mang thông tin gì. Muốn nó có tác dụng thật, phải đếm số lần một URL được trỏ tới trong quá trình crawl — nhưng URL đã vào `enqueued` thì `addUrl` trả về `false` ngay, nên thông tin đó bị mất. Đây là một khoảng cách thật giữa thiết kế và cài đặt, đáng ghi trong phần hạn chế của đồ án.

---

## 2. Biến min-heap thành max-heap — kỹ thuật phủ định

`MinHeap` luôn trả về phần tử **nhỏ nhất**, nhưng ta cần phần tử **ưu tiên cao nhất**. Không cần viết lại cấu trúc:

```java
byDomain.computeIfAbsent(domain,
        d -> new MinHeap<>((a, b) -> Double.compare(-a.priority(), -b.priority())))
    .insert(new FrontierEntry(url, depth, priority));
```

**Vì sao đúng.** Phép phủ định **đảo ngược thứ tự** trên số thực:

$$a > b \iff -a < -b$$

nên phần tử có `priority` lớn nhất có `−priority` nhỏ nhất, và vẫn là phần tử `extractMin()` trả về đầu tiên. Kỹ thuật này biến một min-heap thành "max-heap theo tiêu chí X" mà không viết thêm dòng cấu trúc nào.

> ⚠️ **Bẫy cần biết:** cách này chỉ an toàn với `double`/`Comparator`. Với `int`, phủ định `Integer.MIN_VALUE` bị **tràn** về chính nó ($-(-2^{31}) = -2^{31}$ vì $2^{31}$ không biểu diễn được), làm hỏng thứ tự. Ở đây dùng `Double.compare` nên không dính, nhưng đó là lý do cách viết an toàn hơn là **đảo thứ tự tham số**: `Double.compare(b.priority(), a.priority())`.

---

## 3. Bài toán trung tâm: một heap toàn cục thì hỏng thế nào

**Thiết kế đầu tiên (đã bỏ):** một `MinHeap` duy nhất chứa toàn bộ frontier.

Khi lấy URL, phải:

```
lấy phần tử đỉnh
nếu host của nó đang trong thời gian hoãn:
    gác nó sang danh sách tạm
    lấy phần tử tiếp theo
    ... lặp lại
cuối cùng: nhét toàn bộ danh sách tạm trở lại heap
```

**Trường hợp xấu nhất** — *mọi* URL đang chờ đều thuộc các host vừa được truy cập — phải rút **cạn** cả heap rồi nhét lại toàn bộ:

$$\text{Chi phí mỗi lần lấy MỘT URL} = \underbrace{n \log n}_{\text{rút cạn}} + \underbrace{n \log n}_{\text{nhét lại}} = O(n \log n)$$

**Vì sao lỗi này không lộ ra ở quy mô nhỏ.** Với $n = 150$ URL, $n\log n \approx 1085$ phép so sánh — chưa tới một phần nghìn giây, hoàn toàn không quan sát được.

Nhưng frontier **tăng nhanh hơn số trang crawl rất nhiều**. Mỗi trang tin tức sinh trung bình **78,8 outlink**, nên:

$$n \approx 78{,}8 \times P$$

| Số trang crawl $P$ | Frontier $n$ | $n \log_2 n$ |
|---|---|---|
| 150 | ~11 800 | ~160 000 |
| 1 000 | ~78 800 | ~1,3 triệu |
| **5 000** | **~394 000** | **~7,3 triệu** |

7,3 triệu phép so sánh **cho mỗi URL lấy ra**, nhân với 5.000 lần lấy → hơn **36 tỉ** phép so sánh chỉ để lập lịch. Crawler thực tế **đứng hình**.

**Bài học tổng quát:** một lỗi hiệu năng $O(n\log n)$ với $n$ tăng tuyến tính theo số vòng lặp cho ra tổng $O(n^2 \log n)$ — và loại lỗi này **chỉ lộ ra khi tăng quy mô**, đúng lúc muộn nhất.

---

## 4. Lời giải Mercator — tách hàng đợi theo host

Thay một heap toàn cục bằng `Map<host, MinHeap>` — mỗi host một hàng đợi riêng. Đây chính là mô hình "back queue theo host" của crawler **Mercator** (Heydon & Najork, 1999).

```java
private final Map<String, MinHeap<FrontierEntry>> byDomain = new HashMap<>();
private final Map<String, Long> lastAccessTime = new HashMap<>();
private final Set<String> enqueued = new HashSet<>();
```

**Mã giả của `nextUrl()`:**

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

**Mã thật:**

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

**Vì sao rẻ hơn hẳn.** Ta chỉ **quét qua các host** (số host $D$ nhỏ), và chỉ gọi `extractMin` **đúng một lần** trên đúng một heap:

| Thiết kế | Chi phí mỗi `nextUrl()` |
|---|---|
| Một heap toàn cục | $O(n \log n)$ — phụ thuộc **tổng** kích thước frontier |
| **Tách theo host** | $\mathbf{O(D + \log n_d)}$ — **không** phụ thuộc tổng kích thước |

Với $D = 52$ và $n_d \approx 7600$: $52 + 13 = \mathbf{65}$ thao tác thay vì 7,3 triệu. Nhanh hơn **112 000 lần**.

**Điểm sâu hơn:** không chỉ là hằng số nhỏ hơn, mà là **độ phức tạp không còn phụ thuộc $n$**. Frontier có phình lên 10 triệu URL thì `nextUrl()` vẫn tốn đúng ngần ấy. Đó là khác biệt về **chất**, không phải về **lượng**.

### 4.1 Hai chi tiết cài đặt đáng học

**(a) `it.remove()` cho heap rỗng.** Không dọn thì các host đã cạn URL vẫn bị quét lại ở mọi lần gọi `nextUrl()`. Vì $D$ trong công thức $O(D + \log n_d)$ là **số mục trong map**, không phải số host còn việc, nên $D$ chỉ tăng chứ không bao giờ giảm trong suốt phiên crawl — biến hằng số nhỏ thành hằng số lớn dần.

Dùng `Iterator.remove()` chứ không phải `map.remove()` là bắt buộc: sửa map trong lúc đang duyệt bằng `for-each` sẽ ném `ConcurrentModificationException`.

**(b) Ngủ *ngoài* khối `synchronized`:**

```java
        }   // ← đóng khối synchronized ở đây
        // Mọi domain đều đang bị hoãn -> ngủ NGOÀI khối synchronized để
        // không giữ khoá, tránh chặn các thread đang muốn addUrl.
        try {
            Thread.sleep(50);
        } catch (InterruptedException ie) { ... }
```

Nếu `Thread.sleep(50)` nằm **trong** khối đồng bộ, thread đang ngủ vẫn **giữ khoá** và chặn mọi thread khác đang muốn `addUrl`. Với 12 worker thread và mỗi lần ngủ 50ms, đó là một điểm nghẽn nghiêm trọng: một tối ưu biến thành một nút cổ chai.

Đây là quy tắc chung đáng nhớ: **không bao giờ ngủ, chờ I/O, hay gọi hàm chậm khi đang giữ khoá.**

---

## 5. Politeness — ràng buộc đặt trần cứng lên thông lượng

```java
public static final long POLITENESS_DELAY_MS = 1000L;
```

Politeness không phải một chi tiết lễ nghi mà là một **ràng buộc toán học** đặt trần lên thông lượng:

$$\text{thông lượng tối đa (trang/giây)} \;\le\; \frac{\text{số host được crawl đồng thời}}{\text{POLITENESS\_DELAY (giây)}} \;=\; D$$

**Chứng minh.** Mỗi host cho tối đa 1 request/giây. Có $D$ host độc lập ⇒ tối đa $D$ request/giây trên toàn hệ thống. ∎

**Kiểm chứng bằng số đo thật:**

| Đại lượng | Giá trị |
|---|---|
| Số host phân biệt $D$ | **52** |
| Trần lý thuyết | 52 trang/giây |
| **Thực đo** | **26,2 trang/giây** (5.011 trang trong 3,2 phút) |
| Hiệu suất so với trần | **50,4 %** |

Hụt 50% so với trần là bình thường: không phải lúc nào cả 52 host cũng có URL sẵn sàng, và độ trễ mạng thật khiến một số fetch mất hơn 1 giây.

**Hệ quả quan trọng nhất, và nó đi ngược trực giác:** muốn crawl 400 trang/giây thì phải có ít nhất **400 host** được crawl song song — **không phải** mua máy nhanh hơn, không phải thêm thread, không phải tối ưu code. Nút thắt nằm ở **cấu trúc bài toán**, không ở tài nguyên.

`MultiDomainCrawlRunner` áp dụng trực tiếp suy luận này khi chọn số thread:

```java
// Politeness delay 1s/domain nghia la thong luong toi da = so domain
// (trang/giay). Dung so thread gap doi so domain de thread khong phai
// la nut that co, phan con lai da bi politeness khong che.
.threadCount(allowedDomains.size() * 2)
```

Hệ số 2 là để thread không phải là nút thắt (một thread đang chờ mạng thì thread kia vẫn làm việc được), nhưng không cần nhiều hơn vì politeness đã khống chế.

---

## 6. Chặn trên kích thước — kiểm soát bộ nhớ

```java
public static final int DEFAULT_MAX_SIZE = 500_000;
```

Vì mỗi trang sinh ~78,8 outlink, một phiên crawl 10.000 trang có thể đẩy vào frontier hơn **một triệu URL**. Với độ dài URL trung bình 60 ký tự, riêng chuỗi đã là:

$$10^6 \times (60 \times 2 \text{ byte} + 40 \text{ byte overhead}) \approx \mathbf{160\ MB}$$

chưa kể `enqueued` `HashSet` lưu **bản sao** của cùng những chuỗi đó (thực ra là cùng tham chiếu, nhưng bảng băm vẫn tốn ~32 byte/mục).

```java
if (totalSize >= maxSize) {
    droppedDueToCapacity++;
    return false;
}
```

**Đánh đổi có chủ ý:** crawler ưu tiên theo bề rộng nên các URL bị bỏ hầu hết là URL độ sâu lớn, vốn có điểm ưu tiên thấp nhất. Biến đếm `droppedDueToCapacity` được giữ lại để báo cáo, tức là hệ thống **biết** mình đã bỏ bao nhiêu — khác hẳn với việc âm thầm mất dữ liệu.

> **Hạn chế:** khi frontier đầy, URL mới bị bỏ **bất kể độ ưu tiên**. Cách đúng hơn là so sánh với phần tử ưu tiên thấp nhất và thay thế nếu URL mới tốt hơn — nhưng min-heap sắp theo `−priority` không cho tra phần tử ưu tiên **thấp** nhất trong $O(1)$; cần một **double-ended priority queue** (min-max heap). Với quy mô đồ án, chặn cứng là đánh đổi hợp lý.

---

## 7. Chuẩn hoá tại điểm vào duy nhất

```java
public boolean addUrl(String rawUrl, int depth, int knownBacklinks) {
    // Chuan hoa ngay tai cua vao: day la choke point duy nhat ma moi URL
    // deu phai di qua, nen chuan hoa o day dam bao tap enqueued khong bao
    // gio chua 2 bien the cua cung mot trang.
    String url = com.vnsearch.crawler.UrlCanonicalizer.canonicalize(rawUrl);
    ...
}
```

Đặt phép chuẩn hoá tại **một** điểm vào duy nhất (thay vì rải ở mọi nơi gọi) là một mẫu thiết kế đáng ghi nhớ: nó biến *"phải nhớ chuẩn hoá"* thành *"không thể quên chuẩn hoá"*.

Chi tiết đầy đủ ở [UrlCanonicalizer.md](UrlCanonicalizer.md).

---

## 8. Đồng bộ hoá — mô hình khoá của lớp

Toàn bộ trạng thái nội bộ được bảo vệ bằng một khoá duy nhất:

```java
private final Object lock = new Object();
```

**Vì sao một khoá thô cho cả bốn cấu trúc** (`byDomain`, `lastAccessTime`, `enqueued`, `totalSize`) thay vì `ConcurrentHashMap`:

Vì các thao tác cần **tính nguyên tử đa cấu trúc**. Ví dụ `addUrl` phải làm 4 việc **như một**: kiểm tra `enqueued`, kiểm tra `totalSize`, `insert` vào heap, `add` vào `enqueued`. Nếu mỗi cấu trúc tự thread-safe riêng thì hai thread vẫn có thể cùng vượt qua kiểm tra `enqueued.contains(url)` rồi cùng chèn — sinh ra URL trùng.

`ConcurrentHashMap` cho ta an toàn **từng thao tác**, còn `synchronized` cho ta an toàn **cả nhóm thao tác**. Ở đây cần cái thứ hai.

**Cái giá:** mọi worker thread tranh nhau một khoá. Đo thực tế không thấy vấn đề vì mỗi worker chỉ gọi `nextUrl()` khoảng 1 lần/giây (do politeness) trong khi phần trong khoá chạy hết vài microgiây — độ tranh chấp cực thấp.

---

## 9. Tổng hợp độ phức tạp

| Thao tác | Thời gian | Ghi chú |
|---|---|---|
| `addUrl` | **$O(\log n_d)$** | + $O(L)$ chuẩn hoá URL, + $O(1)$ tra `enqueued` |
| `nextUrl` | **$O(D + \log n_d)$** | Có thể **chặn** (ngủ 50ms) nếu mọi host đang hoãn |
| `size`, `isEmpty`, `domainCount` | $O(1)$ | Đều trong `synchronized` |
| Bộ nhớ | $O(n)$ | Chặn trên 500.000 URL |

Với $D = 52$, $n_d \approx 7600$: `nextUrl` ≈ **65 thao tác**.

---

## 10. Chủ đề DSA thể hiện

| Chủ đề | Ở đâu |
|---|---|
| **Hàng đợi ưu tiên (binary heap)** | `MinHeap<FrontierEntry>` mỗi host |
| **Phân hoạch cấu trúc dữ liệu** | một heap → `Map<host, heap>`, đổi $O(n\log n)$ thành $O(D + \log n_d)$ |
| **Đảo chiều comparator** | min-heap dùng như max-heap qua `−priority` |
| **Bảng băm** | `enqueued` chống trùng, `lastAccessTime` cho politeness |
| **BFS có trọng số** | hàm ưu tiên tuyến tính giữ trật tự theo lớp |
| **Chặn trên có kiểm soát** | `DEFAULT_MAX_SIZE` + đếm số bị bỏ |
| **Đồng bộ hoá đa cấu trúc** | một khoá thô cho nguyên tử nhóm thao tác |
| **Không giữ khoá khi ngủ** | `Thread.sleep` ngoài `synchronized` |
| **Dọn rác cấu trúc phụ trợ** | `it.remove()` cho heap rỗng |
| **Ràng buộc ngoài đặt trần thuật toán** | politeness ⇒ thông lượng $\le D$ |

---

## 11. Hạn chế đã biết

1. **`knownBacklinks` chưa hoạt động thật** (xem §1.3) — thành phần thứ hai của công thức ưu tiên hiện là hằng số.
2. **Thứ tự không tái lập được.** Khi hai host cùng ưu tiên bằng nhau, phép so sánh `priority > bestPriority` (dấu `>` chặt) khiến host được quét trước thắng — mà thứ tự quét là thứ tự nội bộ của `HashMap`, không xác định trước. Không sai, nhưng làm việc tái lập một phiên crawl khó hơn. Dùng `LinkedHashMap` sẽ khắc phục.
3. **Không bền vững qua lần khởi động.** Frontier nằm hoàn toàn trong RAM; crawler dừng giữa chừng là mất sạch. Crawler thật lưu frontier ra đĩa.
4. **Politeness cố định 1 giây**, không đọc `Crawl-delay` từ robots.txt (parser có bỏ qua trường này — xem [RobotsTxtParser](RobotsTxtParser.md)).
5. **Vòng lặp bận có ngủ.** `nextUrl` ngủ 50ms rồi thử lại thay vì dùng `wait`/`notify`. Đơn giản hơn nhưng lãng phí một chút CPU và thêm độ trễ tối đa 50ms.

---

## 12. Liên kết

- Cấu trúc nền: [MinHeap.md](../06-datastructures/MinHeap.md)
- Người dùng: [CrawlerService.md](CrawlerService.md)
- Chuẩn hoá tại cửa vào: [UrlCanonicalizer.md](UrlCanonicalizer.md)
- Khử trùng lặp ở tầng khác: [BloomFilter.md](BloomFilter.md)
- Ký hiệu chưa hiểu: [00 — Từ điển ký hiệu toán](../00-KY-HIEU-TOAN.md)
