Bản rút gọn dạng cây

Toàn bộ bề mặt HTTP của backend qua api-gateway (trừ football-service — bóng đá không
liên quan tới máy tìm kiếm, xem riêng `FootballController`):

```
/
├─ GET  /api/search      công khai   → SearchController      → xem QUERY-PIPELINE.md
├─ GET  /api/suggest     công khai   → SuggestController
├─ GET  /api/health      công khai   → HealthController
├─ GET  /api/images      công khai   → ImageSearchController
├─ GET  /api/feed        công khai   → FeedController
├─ POST /api/events      công khai   → EventController
├─ /ws/**                công khai   → search-service, cổng SOAP (cùng dữ liệu /api/search)
├─ /oauth2/**, /.well-known/**   công khai   → auth-service (lấy/thu hồi token, JWKS)
├─ /api/auth/**          hỗn hợp     → AuthController        → xem AUTH-PIPELINE.md
├─ /api/admin/users/**   ROLE_ADMIN  → AdminUserController    (ĐỨNG TRƯỚC /api/admin/**
│                                      ở bảng tuyến Gateway — tuyến hẹp phải khớp trước)
├─ /api/admin/analytics/**  ROLE_ADMIN → AdminAnalyticsController (cũng đứng trước)
├─ /api/admin/**         ROLE_ADMIN  → AdminController (crawler-service, tuyến rộng nhất
│                                      trong nhóm /api/admin/, đứng CUỐI)
├─ /api/history/**       cần đăng nhập → HistoryController
├─ /api/downloads/**     cần đăng nhập → DownloadController
├─ /api/settings/**      cần đăng nhập → SettingsController
└─ /actuator/**          ROLE_ADMIN  (trừ /actuator/health/** và /actuator/prometheus công khai)
```

Không dùng service discovery (Eureka/Consul): 8 service cố định, bảng tuyến YAML tĩnh ở
api-gateway, khớp theo Path predicate, THỨ TỰ tuyến hẹp trước tuyến rộng.

Mỗi request đi qua đúng thứ tự này:

```
HTTP request
├─ [chỉ ở Gateway] JWT hợp lệ + hasRole("ADMIN") cho /api/admin/**, /actuator/**
│                  hoặc permitAll theo danh sách trắng (search/suggest/images/feed/health,
│                  /ws/**, /oauth2/**, /api/auth/register|login|logout|refresh, /api/football/**)
│                  ↳ GatewaySecurityConfig — mọi tuyến KHÔNG có trong danh sách trắng rơi
│                    vào .anyExchange().authenticated(), MẶC ĐỊNH ĐÓNG
├─ RateLimitFilter        order = Integer.MIN_VALUE   → 429 (chạy Ở TỪNG SERVICE, không
│                         phải ở Gateway; Gateway chỉ giới hạn riêng route /api/search,/suggest,
│                         /images,/feed qua RequestRateLimiter dựa trên Redis)
├─ TokenAuthFilter        Authorization: Bearer …    → ROLE_USER | ROLE_ADMIN
├─ ApiKeyAuthFilter       X-API-Key                  → ROLE_ADMIN
│                         ★ chuỗi lọc này là của TỪNG SERVICE (ServiceSecurityConfig).
│                           Gateway KHÔNG có ApiKeyAuthFilter: /api/admin/** ở cổng 8080
│                           đòi JWT mang vai trò ADMIN và TỪ CHỐI X-API-Key. Khoá chỉ
│                           dùng được khi gọi thẳng service, ví dụ reindex ở :8083.
│                           requireAdminApiKey = true chỉ ở crawler-service và
│                           analytics-service; history/downloads/settings không có
│                           endpoint quản trị nên không bắt buộc khoá.
├─ authorizeHttpRequests  danh sách trắng, anyRequest().authenticated() → 401 nếu chưa
│                         đăng nhập; /api/admin/**, /actuator/** đòi thêm hasRole("ADMIN")
├─ CorsConfig             /api/** ; GET POST PUT PATCH DELETE OPTIONS ; allowCredentials(true)
│                         ; maxAge 3600 — khai riêng ở TỪNG SERVICE (để gọi thẳng lúc gỡ lỗi)
│                         VÀ ở Gateway (GatewaySecurityConfig); Gateway lại DedupeResponseHeader
│                         RETAIN_UNIQUE để trình duyệt không thấy header CORS bị lặp
│                         ↳ PATCH cho PATCH /api/settings/{khoa}, PUT /api/settings và
│                           PATCH /api/downloads/{id}. Danh sách này phải KHỚP danh sách của
│                           Gateway: Gateway cho preflight qua mà service từ chối thì lỗi
│                           hiện ở tầng service.
│                         allowedHeaders: Accept, Authorization, Content-Type, X-API-Key,
│                         X-Idempotency-Key, X-Device-Id, If-Match
│                         allowedOriginPatterns (từng service): app.cors.allowed-origins +
│                         "file://*" + "null" ↳ hai giá trị sau cho Electron mở tệp cục bộ
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

GET /api/feed?page&size
├─ order = [0..totalDocs), sort theo crawledAt GIẢM DẦN (null xuống cuối)
│  ↳ tất định: cùng chỉ mục = cùng thứ tự, nên trang 2 không lặp lại trang 1
│    (tham số seed cũ đã bỏ — thứ tự không còn ngẫu nhiên)
├─ bỏ tài liệu không có URL, bỏ tài liệu KHÔNG có ảnh   (thẻ feed cần ảnh bìa)
├─ dừng ở MAX_FEED_ITEMS 200                     ← trần công việc mỗi request
└─ toCard: url, title (rỗng thì lấy url), snippet SNIPPET_LENGTH 160, imageUrl, altText, crawledAt

POST /api/events         công khai, @Valid
└─ EventController → UsageAnalyticsService.recordVisit / recordSearch / recordClick
   ↳ công khai vì khách chưa đăng nhập cũng phải đếm được; mọi trường đều bị CẮT
     độ dài trước khi vào bảng đếm (xem ANALYTICS-PIPELINE.md)
```

Các endpoint quản trị:

```
POST /api/admin/crawl              @Valid CrawlRequest{seedUrls, maxDepth, maxPages}
     ├─ maxDepth ≤ 10, maxPages ≤ 50 000, seedUrls ≤ 50 — trần API, KHÁC trần dòng lệnh
     └─ SeedUrlValidator.validate TỪNG seed TRƯỚC khi lập job — chặn SSRF sớm
└─ SearchEngineFacade.startCrawl → CrawlJobManager   → {"jobId", "status":"STARTED"} (chạy nền)
GET  /api/admin/crawl/{jobId}/status → trạng thái job (404 nếu không có job)
POST /api/admin/reindex            → facade.reindex()      → xem INDEX-PIPELINE.md
     ↳ CHỈ dựng lại chỉ mục TRONG TIẾN TRÌNH crawler-service; search-service không tự nạp lại
GET  /api/admin/stats              → facade.getStats()
     └─ số tài liệu, số term, kích thước index.json, tỷ lệ trúng cache, tên scorer, số bit
        BloomFilter — đọc THẲNG từ bộ nhớ, không duyệt corpus
GET  /api/admin/corpus-stats       → facade.getCorpusStats() = CorpusStats
     ↳ TÁCH khỏi /stats vì đây duyệt TOÀN corpus (trung vị độ dài, đếm liên kết) — không gọi
       trên mỗi lần vẽ dashboard, xem ANALYTICS-PIPELINE.md
GET  /api/admin/analytics?…        → AdminDashboard        → xem ANALYTICS-PIPELINE.md
POST /api/admin/analytics/reset    → xoá mọi bộ đếm sử dụng
/api/admin/users/**                → xem AUTH-PIPELINE.md
GET  /actuator/prometheus          công khai
     └─ MetricsConfig đăng ba Gauge:
        vnsearch.index.documents / vnsearch.index.terms / vnsearch.cache.hit.rate
```

Ba service còn lại — mỗi cái quản một loại dữ liệu RIÊNG CỦA NGƯỜI DÙNG, không đụng tới
chỉ mục hay corpus:

```
history-service   /api/history
├─ POST/GET    /visits            ghi/đọc lượt xem trang
├─ DELETE      /visits/{id}, /visits(xoá hết)
├─ POST/GET    /searches          ghi/đọc lượt tìm kiếm
├─ GET         /searches/suggest  gợi ý dựa trên lịch sử CHÍNH người dùng đó
└─ GET         /summary           tổng hợp cho trang lịch sử

downloads-service   /api/downloads
├─ POST         tạo bản ghi tải xuống
├─ PATCH /{id}  cập nhật tiến độ/trạng thái
├─ GET          danh sách, GET /active  đang chạy, GET /summary  tổng hợp
└─ DELETE /{id}, DELETE  (xoá hết)

settings-service   /api/settings
├─ GET / PATCH / PUT    đọc / sửa một phần / thay toàn bộ cài đặt người dùng
│  ↳ PATCH/PUT dùng If-Match để chống ghi đè khi sửa từ hai máy cùng lúc
└─ DELETE /{khoa}, DELETE  (xoá một khoá / xoá hết)
```

Một quy ước lặp lại ở mọi controller đọc dữ liệu (search/images/feed):

```
size < 1 hoặc size > MAX_SIZE → DEFAULT_SIZE      ← KHÔNG ném lỗi, chỉ kẹp về mặc định
page → min(max(page, 1), MAX_PAGE)
totalResults / hasMore luôn tính trên tập ĐÃ lọc, không phải trên corpus
timeTakenMs đo ngay trong controller, tính cả phần cắt trang
   ↳ và `size` trả về là size ĐÃ ÁP DỤNG, không phải size client gửi lên: nếu trả lại
     con số client gửi, giao diện sẽ tính sai tổng số trang khi bị kẹp
```
