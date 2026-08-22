Bản rút gọn dạng cây

Toàn bộ bề mặt HTTP của backend (trừ football-service):

```
/api
├─ GET  /search      công khai   → SearchController      → xem QUERY-PIPELINE.md
├─ GET  /suggest     công khai   → SuggestController
├─ GET  /health      công khai   → HealthController
├─ GET  /images      công khai   → ImageSearchController
├─ GET  /feed        công khai   → FeedController
├─ POST /events      công khai   → EventController
├─ /auth/**          hỗn hợp     → AuthController        → xem AUTH-PIPELINE.md
├─ /admin/**         ROLE_ADMIN  → AdminController, AdminUserController, AdminAnalyticsController
└─ /actuator/**      ROLE_ADMIN  (trừ /health/** và /prometheus công khai)
```

Mỗi request đi qua đúng thứ tự này:

```
HTTP request
├─ RateLimitFilter        order = Integer.MIN_VALUE, url pattern /api/*   → 429
├─ TokenAuthFilter        Authorization: Bearer …    → ROLE_USER | ROLE_ADMIN
├─ ApiKeyAuthFilter       X-API-Key                  → ROLE_ADMIN
├─ authorizeHttpRequests  danh sách trắng, anyRequest().denyAll() → 401
├─ CorsConfig             /api/** ; GET POST DELETE OPTIONS ; allowCredentials(false) ; maxAge 3600
│                         allowedOriginPatterns: app.cors.allowed-origins + "file://*" + "null"
│                         ↳ hai giá trị sau cho Electron mở tệp cục bộ, Origin lúc đó là "null"
├─ @Valid trên @RequestBody / @RequestParam
└─ GlobalExceptionHandler (@RestControllerAdvice)
   ├─ MissingServletRequestParameter    → 400 "Thiếu tham số bắt buộc: q"
   ├─ MethodArgumentNotValid            → 400, ghép "trường: thông điệp" của mọi lỗi
   ├─ ConstraintViolation               → 400, chỉ lấy NÚT CUỐI của property path
   ├─ InvalidCredentialsException       → 401
   ├─ AuthException                     → 400
   ├─ HttpRequestMethodNotSupported     → 405, kèm danh sách phương thức cho phép
   ├─ IllegalArgumentException          → 400
   └─ Exception (bắt tất)               → 500
      ├─ reference = 8 ký tự đầu của một UUID
      ├─ log.error kèm reference + method + URI + toàn bộ stack trace
      └─ thân phản hồi KHÔNG lộ chi tiết, chỉ đưa reference cho người dùng đọc lại
   → thân lỗi luôn cùng hình dạng: {timestamp, status, error, message, reference?}
```

Các endpoint đọc dữ liệu:

```
GET /api/suggest?prefix=…&limit=…
└─ SearchEngineFacade.suggest → SuggestionService.suggest(prefix, limit)
   ├─ Trie tiền tố dựng lại mỗi lần refreshDerivedState — từ ghép và bigram của
   │  TIÊU ĐỀ tiếng Việt trong corpus, lọc theo tần suất tối thiểu
   ├─ + truy vấn THẬT đã học từ những lần tìm CÓ kết quả
   └─ mỗi cụm chèn hai dạng (có dấu / không dấu) nên gõ "ha noi" vẫn ra "hà nội"
   → {"suggestions": [...]}

GET /api/health
└─ documents = facade.getIndexedDocumentCount()
   ├─ > 0 → 200 {"status":"UP", "indexedDocuments": n}
   └─ = 0 → 503 {"status":"OUT_OF_SERVICE", …}
      ↳ chỉ mục rỗng KHÔNG phải là hệ thống khoẻ; Docker dựa vào mã này để khởi động lại

GET /api/images?q=…&page&size          (size trần MAX_SIZE, page trần MAX_PAGE)
├─ facade.search(q, 1, MAX_SCANNED_PAGES)        ← dùng lại đúng tầng xếp hạng của web search
├─ titleByUrl: LinkedHashMap giữ THỨ TỰ xếp hạng của trang
├─ imageStore.forPages(urls, MAX_TOTAL_IMAGES)
├─ sort theo missingAlt                          ← ảnh có alt lên trước (chất lượng + trợ năng)
└─ cắt trang → {results[], page, pageSize, totalResults, hasMore, pagesScanned, timeTakenMs}

GET /api/feed?seed=…&page&size
├─ order = [0..totalDocs), Collections.shuffle(order, new Random(seed))
│  ↳ seed đi theo client: cùng seed = cùng thứ tự, nên trang 2 không lặp lại trang 1
├─ bỏ tài liệu không có URL, bỏ tài liệu KHÔNG có ảnh   (thẻ feed cần ảnh bìa)
├─ dừng ở MAX_FEED_ITEMS 200                     ← trần công việc mỗi request
└─ toCard: url, title (rỗng thì lấy url), snippet SNIPPET_LENGTH 160, imageUrl, altText

POST /api/events         công khai, @Valid
└─ EventController → UsageAnalyticsService.recordVisit / recordSearch / recordClick
   ↳ công khai vì khách chưa đăng nhập cũng phải đếm được; mọi trường đều bị CẮT
     độ dài trước khi vào bảng đếm (xem ANALYTICS-PIPELINE.md)
```

Các endpoint quản trị:

```
POST /api/admin/crawl              @Valid CrawlRequest{seedUrls, maxDepth, maxPages}
└─ SearchEngineFacade.startCrawl → CrawlJobManager   → {"jobId": …}   (chạy nền)
GET  /api/admin/crawl/{jobId}/status → CrawlStatus (404 nếu không có job)
POST /api/admin/reindex            → facade.reindex()      → xem INDEX-PIPELINE.md
GET  /api/admin/stats              → facade.getStats()
     ├─ số tài liệu, số term, kích thước index.json, tỷ lệ trúng cache
     └─ tên scorer đang dùng, số bit BloomFilter
GET  /api/admin/analytics?…        → AdminDashboard        → xem ANALYTICS-PIPELINE.md
POST /api/admin/analytics/reset    → xoá mọi bộ đếm sử dụng
/api/admin/users/**                → xem AUTH-PIPELINE.md
GET  /actuator/prometheus          công khai
     └─ MetricsConfig đăng ba Gauge:
        vnsearch.index.documents / vnsearch.index.terms / vnsearch.cache.hit.rate
```

Một quy ước lặp lại ở mọi controller đọc dữ liệu:

```
size < 1 hoặc size > MAX_SIZE → DEFAULT_SIZE      ← KHÔNG ném lỗi, chỉ kẹp về mặc định
page → min(max(page, 1), MAX_PAGE)
totalResults / hasMore luôn tính trên tập ĐÃ lọc, không phải trên corpus
timeTakenMs đo ngay trong controller, tính cả phần cắt trang
   ↳ và `size` trả về là size ĐÃ ÁP DỤNG, không phải size client gửi lên: nếu trả lại
     con số client gửi, giao diện sẽ tính sai tổng số trang khi bị kẹp
```
