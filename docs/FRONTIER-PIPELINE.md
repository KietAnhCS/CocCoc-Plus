Bản rút gọn dạng cây

Phần bên trong của `frontier.addUrl` / `frontier.nextUrl` và bảy tầng chặn mà mỗi URL phải đi qua.

Nạp vào — addUrl:

```
UrlFrontier.addUrl(rawUrl, depth, knownBacklinks)
├─ UrlCanonicalizer.canonicalize(rawUrl)                    ← NGOÀI khối khoá
│  ├─ bỏ fragment "#..."
│  ├─ scheme + host → chữ thường
│  ├─ bỏ cổng mặc định (80 với http, 443 với https)
│  ├─ bỏ dấu "/" ở cuối; đường dẫn gốc "/" thành chuỗi rỗng
│  ├─ GIỮ nguyên query (thứ tự tham số có thể đổi nội dung trang)
│  └─ URI hỏng → trả lại chuỗi đã bỏ fragment, không ném
├─ hostOf(url) → CrawlTask(url, host, depth)
│  ↳ phân tích URL ĐÚNG MỘT lần; host đi theo task nên tầng sau khỏi phân tích lại
├─ prioritizer.levelOf(url, host, depth, knownBacklinks)     DefaultPrioritizer
│  ├─ level = depth
│  ├─ host kết thúc ".vn"           → level − 1
│  ├─ backlink ≥ BACKLINK_BOOST_THRESHOLD 5 → level − 1
│  └─ kẹp về [0, DEFAULT_LEVELS 5 − 1]      0 = ưu tiên cao nhất
└─ synchronized (lock)
   ├─ enqueued.contains(url) → false        ← chống trùng TRONG hàng đợi
   ├─ totalSize ≥ maxSize DEFAULT_MAX_SIZE 500 000 → droppedDueToCapacity++ , false
   └─ frontQueues.add(task, level) ; enqueued.add ; pendingPerHost.merge ; totalSize++
```

Lấy ra — nextUrl, hai tầng hàng đợi kiểu Mercator:

```
UrlFrontier.nextUrl()
└─ vòng lặp:
   ├─ synchronized (lock)
   │  ├─ totalSize == 0 → null                            ← hết việc thật
   │  ├─ backQueues.refillFrom(frontQueues)                ── BỘ ĐỊNH TUYẾN
   │  │  └─ còn ô trống VÀ tầng trước còn hàng:
   │  │     ├─ nextFreeSlot() < 0 → dừng (mọi hàng đợi đều đang có việc)
   │  │     └─ fillSlot: FrontQueues.poll → gán HOST cho ô đó
   │  │        └─ FrontQueues.poll → selector.select(sizes[])
   │  │           ├─ WeightedRandomSelector (mặc định)
   │  │           │  ├─ trọng số mức i = 2^(levels − 1 − i)   → mức 0 nặng gấp 16 lần mức 4
   │  │           │  ├─ chỉ cộng trọng số của hàng đợi CÒN HÀNG ; tổng = 0 → −1
   │  │           │  ├─ bốc điểm trong [0, tổng) rồi trừ dần
   │  │           │  ├─ seed cố định DEFAULT_SEED 20240801 → chạy lại lặp lại được
   │  │           │  └─ MAX_LEVELS 30 (2^30 còn nằm gọn trong long)
   │  │           │     ↳ vì sao NGẪU NHIÊN CÓ TRỌNG SỐ: ưu tiên tuyệt đối làm mức thấp
   │  │           │       CHẾT ĐÓI — trang sâu không bao giờ tới lượt khi mức 0 còn hàng
   │  │           └─ StrictPrioritySelector: lấy mức thấp nhất còn hàng (dùng để so sánh)
   │  ├─ backQueues.poll(now)                              ── BỘ CHỌN, có lịch sự
   │  │  ├─ ready rỗng → null
   │  │  ├─ slot = ready.peek()                            ← MinHeap theo availableAt
   │  │  ├─ availableAt[slot] > now → null
   │  │  │  ↳ phần tử NHỎ NHẤT chưa tới giờ ⇒ không phần tử nào tới giờ
   │  │  ├─ extractMin ; inReady[slot] = false
   │  │  │  ↳ chỉ khi ra khỏi heap thì availableAt mới được phép đổi — sửa khoá của
   │  │  │    một phần tử đang nằm trong heap sẽ phá bất biến heap
   │  │  ├─ task = queues[slot].pollFirst() ; pending--
   │  │  ├─ availableAt[slot] = now + POLITENESS_DELAY_MS 1000   ← MỘT request/giây/host
   │  │  └─ hàng đợi cạn → markEmpty(slot), trả ô về freeSlots ; ngược lại chèn lại heap
   │  ├─ có task → enqueued.remove, releaseHost, totalSize-- , TRẢ VỀ
   │  └─ không → sleepMs = sleepUntilNextSlot(now)   , trần MAX_SLEEP_MS 50
   └─ Thread.sleep NGOÀI khối khoá
      ↳ ngủ trong khoá sẽ chặn mọi luồng đang muốn addUrl — 32 worker đứng chờ một
        luồng đang ngủ chờ phép lịch sự của MỘT host
   DEFAULT_BACK_QUEUE_COUNT 128 ô ⇒ tối đa 128 host được crawl song song, mỗi host
   vẫn chỉ một request mỗi giây
```

Bảy tầng chặn, xếp theo thứ tự "rẻ và loại nhiều trước":

```
1. UrlFilter.accept(url, depth)                    thuần chuỗi, không chạm mạng
   ├─ depth > maxDepth                → rejectedByDepth
   ├─ URI hỏng / scheme ≠ http(s)     → rejectedByScheme
   ├─ host không thuộc allowedDomains → rejectedByDomain
   ├─ tiền tố host bị loại            → rejectedByHostPrefix
   │  NON_VI_EN_HOST_PREFIXES: bản dịch ngôn ngữ khác của cùng một trang báo
   └─ đuôi tệp bị chặn (.pdf, .zip, ảnh…) → rejectedByExtension
      ↳ mọi lý do đều có bộ đếm riêng, nên in được bảng "tầng nào loại bao nhiêu"

2. UrlSeenFilter.markSeenIfNew(url)                BloomFilter
   └─ dương tính giả = bỏ sót một URL chưa thăm — đổi lấy việc không giữ hàng triệu chuỗi

3. UrlFilter.isAllowedByRobots(url)                RobotsTxtParser, cache theo domain
   └─ tải robots.txt một lần cho mỗi tên miền, khớp User-agent + Allow/Disallow

4. Chặn SSRF — HAI điểm gọi khác nhau, dùng chung phép kiểm tra tĩnh
   ├─ SeedUrlValidator.validate(url)     CHỈ gọi MỘT lần, khi nhận seed từ admin API
   │  ├─ chỉ http/https, phải có host ; isBlockedHostname: localhost, metadata nội bộ…
   │  ├─ InetAddress.getAllByName → ∀ địa chỉ: isBlockedAddress (loopback, private, link-local…)
   │  └─ MỌI ca từ chối trả về CÙNG MỘT thông điệp REJECTED
   │     ↳ kể cả ca "không phân giải được tên máy" — nếu hai ca trả lời khác nhau thì kẻ gọi
   │       phân biệt được "host không tồn tại" với "host tồn tại và nằm trong mạng nội bộ",
   │       tức biến chính lớp chặn SSRF thành công cụ quét mạng nội bộ
   │     ↳ KHÔNG áp dụng cho hạt giống viết sẵn trong mã nguồn (MultiDomainCrawlRunner)
   └─ HtmlDownloader.assertTargetAllowed(url)   gọi TRƯỚC MỖI hop, kể cả từng chặng redirect
      ├─ dùng lại NGUYÊN hai hàm tĩnh isBlockedHostname/isBlockedAddress ở trên — không viết
      │  lại luật, chỉ đổi cách lấy địa chỉ
      ├─ lấy địa chỉ qua DnsResolver.resolve (MỘT địa chỉ, có cache) — KHÔNG phải
      │  InetAddress.getAllByName như validate(); khác ∀-địa-chỉ ở trên
      └─ chặn thì ném BlockedTargetException với thông điệp RIÊNG từng ca
         ("Ten may bi chan" / "Dia chi khong duoc phep crawl"), KHÔNG dùng chung REJECTED
         ↳ lý do tách: liên kết do LinkExtractor moi ra không đi qua AdminController nên
           trước đây chưa từng được kiểm tra; nay mọi URL — kể cả từng chặng chuyển hướng —
           đều phải qua đây mới tải được, bất kể đến từ đâu

5. HtmlDownloader.download(url)                    DEFAULT_TIMEOUT_MS 10 000
   ├─ DEFAULT_MAX_RETRIES 2 , MAX_REDIRECTS 5
   ├─ DnsResolver.resolveHostOf → LRUCache DEFAULT_CACHE_SIZE 1 000 (có hitRate)
   ├─ BlockedTargetException  → KHÔNG thử lại: địa chỉ nội bộ không tự thành công khai,
   │  và mỗi lần thử lại là thêm một lần chạm vào hạ tầng nội bộ
   ├─ UnknownHostException    → KHÔNG thử lại: 3 lần × 10 giây cho một tên miền chết
   └─ ngoại lệ unchecked của Jsoup → gói thành IOException để phía gọi chỉ bắt một kiểu
      User-Agent: "VnSearchBot/1.0 (+do an DSA; hoc thuat)"   ← khai danh tính thật

6. LanguageFilter.accept(doc)                      ba tầng, dừng sớm khi đủ chắc
   ├─ văn bản = TIÊU ĐỀ + thân bài, lấy mẫu SAMPLE_LIMIT 20 000 ký tự
   │  ↳ trang danh mục có thân bài rất ngắn, khi đó tiêu đề là bằng chứng đáng tin duy nhất
   ├─ tầng 1 — HỆ CHỮ VIẾT: tỷ lệ chữ ngoài Latinh > FOREIGN_SCRIPT_THRESHOLD 10%
   │           → trả về ngôn ngữ của hệ chữ chiếm đa số
   ├─ tầng 2 — DẤU PHỤ tiếng Việt ≥ VIETNAMESE_DIACRITIC_THRESHOLD 0.5% → "vi"
   ├─ tầng 3 — TỪ CHỨC NĂNG: ngưỡng vi 5%, en 12% (5% nếu thẻ lang đã gợi ý en)
   │           cần ít nhất MIN_TOKENS_FOR_CONTENT_EVIDENCE 40 token mới tin bằng chứng nội dung
   └─ kết quả ghi vào doc.setLanguage; chỉ vi / en / und được nhận, còn lại bị loại
      (kèm bộ đếm theo TỪNG ngôn ngữ bị loại)

7. ContentSeenFilter.seenBefore(bodyText)          chống trùng NỘI DUNG
   ├─ thân bài rỗng → blankSkipped, KHÔNG coi là trùng
   ├─ fingerprint = SHA-256(normalize(bodyText)) dạng hex
   └─ ConcurrentHashMap.newKeySet().add → false nghĩa là đã gặp
      ↳ khác tầng 2: hai URL khác nhau vẫn có thể là CÙNG một bài (bản in, bản AMP,
        bản có tham số theo dõi)
```

Vì sao chia hai tầng hàng đợi thay vì một:

```
một hàng đợi duy nhất buộc phải chọn MỘT trong hai:
├─ sắp theo ưu tiên  → mọi worker cùng lao vào host của trang ưu tiên nhất, vi phạm lịch sự
└─ sắp theo host     → mất hoàn toàn khái niệm ưu tiên

hai tầng tách đúng hai mối quan tâm đó:
├─ FrontQueues  giữ ƯU TIÊN   (chọn bằng ngẫu nhiên có trọng số, không gây chết đói)
└─ BackQueues   giữ LỊCH SỰ   (mỗi ô một host, MinHeap theo thời điểm sẵn sàng)
   và MinHeap đúng ở đây vì câu hỏi mỗi vòng lặp luôn là "ô nào tới giờ SỚM NHẤT" —
   O(log n) thay vì quét cả 128 ô
```
