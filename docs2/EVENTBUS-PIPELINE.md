Bản rút gọn dạng cây

Cùng một tập khối xử lý, hai cách nối dây — chọn bằng MỘT khoá cấu hình:

```
app.crawler.bus = memory   (mặc định)   → InProcessCrawlEventBus   , 1 tiến trình
app.crawler.bus = kafka                 → KafkaCrawlEventBus       , nhiều tiến trình
                                          @ConditionalOnProperty trên KafkaCrawlConfig
```

Bốn loại sự kiện, bốn hướng đi:

```
CrawlEventBus (interface)
├─ publishPage(PageEvent)              trang vừa tải xong: url, html, docId, thời điểm
├─ publishDiscoveredUrl(DiscoveredUrl) URL mới phát hiện, cần đưa vào frontier
├─ publishOutlinks(OutlinksExtracted)  liên kết ra của MỘT trang, gắn ngược vào tài liệu
├─ publishImage(ImageFound)            ảnh của một trang
├─ getPublishFailureCount()            số lần gửi hỏng — có mặt ở CẢ hai cài đặt
└─ CrawlEventBus.noop()                vứt hết mọi sự kiện
   ↳ dùng khi crawl chạy mà KHÔNG cần các khối phía sau (test, chạy thử)
     — một cài đặt rỗng tường minh, thay cho `if (bus != null)` rải khắp nơi
```

Chế độ in-process — mặc định:

```
CrawlerService.processPage
└─ bus.publishPage(PageEvent)
   └─ InProcessCrawlEventBus                       CopyOnWriteArrayList cho mỗi loại handler
      ├─ UrlExtractorService.onPage                          ĐỒNG BỘ, trong chính luồng worker
      │  ├─ html rỗng → pagesWithoutHtml++ , thoát
      │  ├─ Jsoup.parse(html, baseUri)
      │  ├─ LinkExtractor.extract → LinkedHashSet, absUrl, canonicalize
      │  ├─ bus.publishOutlinks(OutlinksExtracted) → ContentStorage.applyOutlinks
      │  │  ↳ đây là lý do outlinks tới SAU nội dung
      │  └─ ∀ link: UrlFilter.accept → rejectedByFilter++
      │             UrlSeenFilter.markSeenIfNew → rejectedAsSeen++
      │             còn lại → linksAccepted++ , publishDiscoveredUrl → frontier ↺
      │     ↳ urlFilter/urlSeenFilter truyền vào dạng Supplier: mỗi phiên crawl có bộ lọc
      │       RIÊNG, service thì sống lâu hơn phiên
      ├─ ImageDownloadService.onPage
      │  ├─ resolveSource (srcset, data-src, lazy-load…)
      │  ├─ app.crawler.images.download = false → chỉ lấy SIÊU DỮ LIỆU, không tải ảnh
      │  ├─ trần app.crawler.images.max-per-page = 50
      │  └─ publishImage → ImageStore.add
      └─ CrawlAnalyticsService.onPage → Micrometer

   ↳ handler ném ngoại lệ KHÔNG được phép làm hỏng phiên crawl: bus bắt, đếm vào
     publishFailures và đi tiếp
```

Chế độ Kafka — cùng những khối đó, khác chỗ chúng chạy:

```
KafkaCrawlConfig  (@ConditionalOnProperty app.crawler.bus = kafka)
├─ 4 topic công việc, mỗi topic app.crawler.kafka.partitions = 12
│  vnsearch.pages / vnsearch.urls.discovered / vnsearch.outlinks / vnsearch.images
├─ 1 topic thư chết, ĐÚNG 1 phân vùng          ← thứ tự quan trọng hơn thông lượng ở đây
├─ Producer:  acks=all + enable.idempotence=true
│  ↳ acks=1 nghĩa là một bản sao chết sau khi xác nhận = MẤT trang đã crawl
├─ Consumer:  auto-offset-reset = earliest, max.poll.records = 50
├─ ObjectMapper + JavaTimeModule                ← PageEvent có Instant; mặc định Jackson
│                                                 ghi ra mảng số, đọc lại thành lỗi kiểu
└─ DefaultErrorHandler(ExponentialBackOff) + DeadLetterPublishingRecoverer
   ↳ bản ghi hỏng lặp lại mãi sẽ CHẶN cả phân vùng; đẩy sang thư chết để phân vùng chạy tiếp
   ↳ cảnh báo VnSearchDeadLetterGrowing theo dõi chính topic đó

CrawlKafkaListeners (crawler-service) — mỗi khối là một GROUP RIÊNG trên cùng topic pages
├─ group url-extractor   → UrlExtractorService.onPage
├─ group image-download  → ImageDownloadService.onPage
├─ group analytics       → CrawlAnalyticsService.onPage
├─ group frontier-feeder ← topic urls     → CrawlJobManager.feedDiscoveredUrl
├─ group frontier-feeder ← topic outlinks → CrawlJobManager.feedOutlinks
└─ group analytics       ← topic images   → CrawlAnalyticsService.onImage
   ↳ ba group cùng đọc topic pages = mỗi khối nhận BẢN SAO của mọi trang, và một khối
     chậm không kéo hai khối kia chậm theo
   ↳ hai listener frontier-feeder ghi vào CrawlerService của một JOB — trạng thái đó
     chỉ tồn tại ở tiến trình nhận POST /api/admin/crawl, nên bị khoá bằng
     autoStartup = app.crawler.role.is-api (mặc định false ở crawler-service, worker
     KHÔNG chạy hai listener này). Từng bị lỗi thật: thiếu autoStartup thì cả backend
     lẫn worker cùng chạy, chung group nên Kafka CHIA ĐÔI luồng URL giữa hai tiến
     trình — frontier không bao giờ nạp đủ, crawl dừng sớm

ImageStoreListener (search-service, KHÔNG cùng lớp với CrawlKafkaListeners)
└─ group image-store ← topic images → ImageStore.add   ← autoStartup = role.is-api
   ↳ phải là GROUP RIÊNG, khác group analytics ở trên: nếu dùng chung group thì hai
     bên CHIA NHAU thông điệp ảnh thay vì mỗi bên nhận đủ luồng
   ↳ ImageStore sống trong RAM của tiến trình, nên listener này chỉ bật ở vai trò
     api (search-service, role=api mặc định) — worker (crawler-service, role=worker
     mặc định) không giữ kho ảnh
```

ImageStore — kho ảnh trong RAM, một ảnh ĐẠI DIỆN cho mỗi trang:

```
ImageStore.add(ImageFound)
├─ trang mới VÀ byPage.size() ≥ MAX_PAGES 50 000 → droppedPageLimit++ , từ chối
└─ byPage.compute(pageUrl, …)                     ← nguyên tử, nhiều luồng cùng ghi
   ├─ chưa có          → nhận, pagesAdded++
   ├─ ImageQuality.isBetter(mới, cũ) → thay, replaced++
   └─ ngược lại        → giữ cũ, rejected++
hai đường GHI, một kho ĐỌC:
├─ in-process: CrawlJobManager đổ ảnh vào
├─ Kafka:      CrawlKafkaListeners đổ ảnh vào
└─ ImageStorePreloader @PostConstruct
   └─ ImageStorage.loadQuietly(pathFor(app.crawler.data-path)) → nạp lại kho của phiên trước
      ↳ nếu thiếu bước này, /api/images và /api/feed trống trơn sau mỗi lần khởi động lại
đọc: ImageStore.forPage(url) / forPages(urls, max)  → /api/images, /api/feed
```

CrawlJobManager — chạy crawl từ HTTP, không phải từ dòng lệnh:

```
POST /api/admin/crawl → CrawlJobManager.start(seedUrls, maxDepth, maxPages, …)
├─ ThreadPoolExecutor, MAX_CONCURRENT_JOBS = 2          ← trần công việc nền
├─ jobs: ConcurrentHashMap<jobId, CrawlJob>
│  └─ CrawlJob giữ CrawlerService + CrawlStatus (STARTED → RUNNING → COMPLETED/FAILED)
│     và chụp lại số liệu CUỐI (pagesCrawled, queueSize, bloomFilterBits)
│     ↳ CrawlerService bị bỏ đi sau khi xong, nên số liệu phải được chụp trước
├─ evictExpiredJobs: JOB_RETENTION_MINUTES 30           ← bảng job không phình vô hạn
├─ feedDiscoveredUrl(url) → định tuyến URL từ Kafka về đúng frontier đang chạy
│  └─ không có job nào nhận → unroutableEvents++
└─ xong → ContentStorage.saveToJson + reindex + refreshDerivedState
```

Vì sao tách khối rồi mới cho chọn cách nối dây:

```
UrlExtractorService / ImageDownloadService / CrawlAnalyticsService đều là POJO thuần:
├─ nhận phụ thuộc qua constructor, không biết gì về Spring lẫn Kafka
├─ khai báo bean nằm ở tầng CẤU HÌNH (SearchConfig, KafkaCrawlConfig)
└─ nhờ vậy chúng chạy được từ MultiDomainCrawlRunner (dòng lệnh, không Spring),
   test được bằng JUnit thuần, và đổi memory ⇄ kafka mà KHÔNG sửa một dòng nào
   trong chính các khối đó
```
