Bản rút gọn dạng cây

Bốn việc, và chỉ bốn việc — api-gateway (:8080) là cửa duy nhất mà máy khách nhìn thấy,
đứng trước tám service. Xem `API-PIPELINE.md` cho toàn bộ bề mặt HTTP đi qua đây,
tài liệu này chỉ nói về CHÍNH cái Gateway — nó làm bốn việc, và cố tình không làm gì khác:

```
ApiGatewayApplication
├─ 1. ĐỊNH TUYẾN      Path predicate tĩnh trong application.yaml → 8 service phía sau
├─ 2. XÁC THỰC        kiểm chữ ký JWT một lần tại biên              GatewaySecurityConfig
├─ 3. THU HỒI          tra denylist theo jti trên Redis              TokenDenylistFilter
└─ 4. GIỚI HẠN TẦN SUẤT  theo NGƯỜI DÙNG, không theo IP              RateLimitConfig
   ↳ KHÔNG có nghiệp vụ: không ghép dữ liệu nhiều service, không đổi thân phản hồi,
     không quyết định thứ tự kết quả. Gateway nằm trên đường đi của TOÀN BỘ lưu lượng —
     thêm nghiệp vụ vào đây là biến nó thành một khối duy nhất mới, chỉ khác là hỏng
     một chỗ kéo sập cả tám service. Phép ghép dữ liệu duy nhất trong hệ này nằm ở
     AdminDashboardAssembler của analytics-service — một service bình thường, hỏng
     được mà không kéo theo ai.
```

Bảng tuyến — tĩnh, không service discovery, khớp theo THỨ TỰ khai báo trong YAML:

```
spring.cloud.gateway.server.webflux.routes  (application.yaml, api-gateway)

Path=/oauth2/**,/.well-known/**              → auth-service      :8081
Path=/api/admin/users/**                     → auth-service       ★ ĐỨNG TRƯỚC crawler-admin
Path=/api/auth/**                            → auth-service
Path=/api/search,/api/suggest,/api/images,
     /api/feed,/api/health                   → search-service    :8082
     filters: RequestRateLimiter (redis, xem khối "Giới hạn tần suất" dưới)
Path=/ws/**                                  → search-service     (cổng SOAP, cùng dữ liệu)
Path=/api/admin/analytics/**                 → analytics-service :8084  ★ ĐỨNG TRƯỚC crawler-admin
Path=/api/events                             → analytics-service
Path=/api/admin/**                           → crawler-service   :8083  ★ RỘNG NHẤT, đứng CUỐI
Path=/api/history/**                         → history-service   :8085
Path=/api/downloads/**                       → downloads-service :8086
Path=/api/settings/**                        → settings-service  :8087
Path=/api/football/**                        → football-service  :8090
     filters: RewritePath=/api/football/(?<duoi>.*), /api/${duoi}

↳ Spring Cloud Gateway lấy tuyến KHỚP ĐẦU TIÊN. Ba tuyến /api/admin/* phải xếp hẹp
  trước rộng: auth-admin-users và analytics-admin đứng trước crawler-admin, vì cả ba
  đều bắt đầu bằng /api/admin/ và crawler-admin (Path=/api/admin/**) sẽ nuốt hết nếu
  đứng trước.
↳ KHÔNG dùng Eureka/Consul: 8 service cố định, một bảng tuyến đọc được đáng giá hơn
  một sổ đăng ký phải dựng/theo dõi/sửa khi hỏng. Trong Kubernetes thì DNS của cụm đã
  là sổ đăng ký.
↳ football-service từng viết bằng Go, Gateway không hề biết chuyện đó — nó chỉ thấy
  một địa chỉ HTTP. Viết lại bằng Java không đụng một dòng nào ở đây.
```

Vì sao football-service dùng RewritePath chứ không phải StripPrefix — lỗi có thật:

```
StripPrefix=2 trên /api/football/v1/fixtures
└─ cắt còn /v1/fixtures
   └─ nhưng football-service phục vụ ở /api/v1/** (xem FootballController)
      → mọi request qua tuyến Gateway đều 404
      ↳ lỗi nằm im vì giao diện gọi thẳng cổng 8090, không đi qua Gateway lúc phát hiện

RewritePath=/api/football/(?<duoi>.*), /api/$\{duoi}
└─ giữ nguyên phần sau /api/football/, gắn lại tiền tố /api/ mà service thật lắng nghe
   /api/football/v1/fixtures  ->  /api/v1/fixtures                      ĐÚNG
```

Một request đi qua đúng thứ tự này (Gateway phía trước, chuỗi filter riêng của TỪNG
service phía sau — xem `AUTH-PIPELINE.md` cho chuỗi đó):

```
HTTP request tới :8080
├─ DedupeResponseHeader (default-filters, mọi tuyến)
│  RETAIN_UNIQUE trên 4 header Access-Control-Allow-*     ← xem khối "CORS nhân đôi" dưới
├─ [Path predicate] chọn tuyến — khớp ĐẦU TIÊN thắng, xem bảng tuyến ở trên
├─ [WebFilter chain, GatewaySecurityConfig.filterChain]
│  ├─ CSRF tắt            không cookie phiên nào để giả mạo — token đi trong header
│  │                       Authorization, trình duyệt không tự đính header đó vào
│  │                       request chéo trang
│  ├─ CORS                configurationSource — xem khối "CORS" dưới
│  ├─ httpBasic / formLogin tắt
│  ├─ authorizeExchange   khớp DANH SÁCH TRẮNG theo thứ tự khai, MẶC ĐỊNH ĐÓNG:
│  │  ├─ OPTIONS /**                                          permitAll  (preflight)
│  │  ├─ /oauth2/**, /.well-known/**                          permitAll  (lấy token)
│  │  ├─ POST /api/auth/register|login|logout|refresh         permitAll
│  │  ├─ GET /api/search|suggest|images|feed|health            permitAll
│  │  ├─ /ws/**                                               permitAll
│  │  ├─ POST /api/events                                     permitAll  (chỉ POST)
│  │  ├─ GET /api/football/**                                 permitAll
│  │  ├─ /actuator/health/**, /actuator/prometheus            permitAll
│  │  ├─ /swagger-ui/**, /swagger-ui.html, /v3/api-docs/**    permitAll
│  │  ├─ /api/admin/**, /actuator/**                          hasRole("ADMIN")
│  │  └─ anyExchange()                                        authenticated
│  │     ↳ tuyến mới quên khai ở đây → 401 ngay lần gọi đầu, hỏng to thấy ngay sửa
│  │       một dòng. Mặc định MỞ thì lặng lẽ phơi dữ liệu, không bài test nào bắt được
│  │       vì response vẫn đúng nội dung.
│  ├─ oauth2ResourceServer().jwt(...)
│  │  ├─ xác minh chữ ký RS256 bằng khoá công khai — jwk-set-uri trỏ auth-service
│  │  │  (/oauth2/jwks), audiences = vnsearch-api
│  │  └─ JwtRoleConverter: claim "roles" → GrantedAuthority ROLE_xxx
│  │     ↳ BẢN SAO CÓ CHỦ Ý của com.vnsearch.config.JwtRoleConverter trong
│  │       vnsearch-platform — module này chạy WebFlux, platform toàn servlet filter.
│  │       Hai bản phải giống nhau tuyệt đối: lệch nhau nghĩa là Gateway và service
│  │       hiểu khác nhau về vai trò của cùng một token — qua được cửa ngoài rồi bị
│  │       từ chối ở trong.
│  └─ sai/thiếu token trên tuyến cần xác thực → 401 trần (HttpStatusServerEntryPoint)
│     KHÔNG chuyển hướng trang đăng nhập — đây là API, không có trang nào để tới
├─ TokenDenylistFilter    order = Ordered.LOWEST_PRECEDENCE - 10  ← CHẠY SAU xác thực
│  ├─ bắt buộc chạy sau: lớp này đọc jti từ token ĐÃ được xác minh chữ ký; chạy trước
│  │  thì SecurityContext còn trống và filter lặng lẽ không làm gì — một biện pháp an
│  │  ninh bị vô hiệu hoá mà không có dấu hiệu nào
│  ├─ không phải JwtAuthenticationToken (endpoint công khai)  → đi tiếp, không tra gì
│  ├─ redis.hasKey("at:denied:" + jti)     cùng tiền tố auth-service dùng khi ghi
│  │  ├─ có          → log.info, 401, exchange.getResponse().setComplete()
│  │  └─ Redis lỗi   → log.error, CHO ĐI TIẾP (fail-open, xem khối riêng dưới)
│  └─ app.gateway.denylist.enabled = true mặc định — tắt được để chạy thử không cần
│     Redis; TẮT Ở MÔI TRƯỜNG THẬT nghĩa là "đăng xuất" không có hiệu lực thật 15 phút
├─ RequestRateLimiter     CHỈ trên tuyến search-api (/api/search,suggest,images,feed)
│  ├─ userOrIpKeyResolver — xem khối "Giới hạn tần suất" dưới
│  ├─ redis-rate-limiter.replenishRate = 10   (lượt/giây ổn định)
│  └─ redis-rate-limiter.burstCapacity = 30   (loạt ngắn — gõ, dừng, gõ tiếp)
│     vượt hạn mức → 429, header Retry-After (giao diện đọc qua exposedHeaders CORS)
└─ forward tới service đích (URI của tuyến) — chuỗi filter RIÊNG của service đó chạy
   tiếp từ đây, xem AUTH-PIPELINE.md / API-PIPELINE.md
```

Vì sao JWT bị kiểm HAI LẦN — ở Gateway và lại ở từng service phía sau:

```
Request giả mạo / token hỏng
├─ CHỈ kiểm ở service   → tốn một chặng mạng nội bộ + một lượt xử lý MỖI service
│                          nó chạm tới trước khi bị chặn — một trận request giả mạo
│                          làm CẢ TÁM service bận, không chỉ Gateway
└─ Kiểm ở CẢ HAI         → hỏng bị chặn NGAY tại biên, trước khi tốn gì ở trong

Vì sao không bỏ lớp kiểm ở service, tin tưởng Gateway đã lọc xong:
└─ trong mạng container, mọi service gọi thẳng được tới nhau, KHÔNG đi qua Gateway
   → một service tin "mọi request tới đây đã qua cửa" là một service gọi thẳng được

= defense in depth: hai lớp ĐỘC LẬP, không lớp nào giả định lớp kia còn nguyên
```

CORS — khai Ở ĐÚNG MỘT NƠI, nhưng lại phải khử trùng vì lý do ngược đời:

```
Trình duyệt CHỈ nói chuyện với Gateway, không biết 7 service phía sau tồn tại
→ app.cors.allowed-origins chỉ khai ở đây (application.yaml), KHÔNG lặp lại ở service
  ↳ khai CORS ở 8 nơi = 8 danh sách origin phải giữ đồng bộ, chỗ bị quên hỏng đúng
    ngày ai đó gọi thẳng một service để gỡ lỗi

NHƯNG mỗi service (vnsearch-platform) VẪN tự bật CORS riêng — có chủ ý, để còn gọi
thẳng được lúc gỡ lỗi (CorsPreflightTest ghim lại điều đó)
└─ gọi QUA Gateway → response đi HAI VÒNG CORS: một ở service (Gateway forward nguyên
   header), một ở chính Gateway
   └─ trình duyệt nhận "Access-Control-Allow-Origin: X, X" — HAI giá trị trong một
      header là bất hợp lệ theo spec Fetch → trình duyệt CHẶN THẲNG, không phải lỗi
      mạng hay sai origin, và không có mã lỗi nào để đọc
   └─ default-filters: DedupeResponseHeader=...RETAIN_UNIQUE  ← giữ giá trị DUY NHẤT
      trong danh sách trùng, ở đây hai giá trị luôn giống hệt nhau nên gọn về một

CorsConfigurationSource (GatewaySecurityConfig)
├─ allowedOrigins   KHÔNG BAO GIỜ "*" — allowCredentials(true) thì trình duyệt từ chối
│                    thẳng dấu sao; và dù không có credentials, dấu sao mở API cho MỌI
│                    trang web đọc dữ liệu thay người dùng đang đăng nhập — đúng thứ
│                    CORS sinh ra để ngăn
├─ allowedMethods   GET POST PUT PATCH DELETE OPTIONS
├─ allowedHeaders   Accept, Authorization, Content-Type, X-API-Key, X-Idempotency-Key,
│                    X-Device-Id, If-Match
│                    ↳ danh sách này phải KHỚP danh sách của từng service: Gateway cho
│                      preflight qua mà service từ chối thì lỗi hiện ở tầng service,
│                      khó dò hơn nhiều vì Gateway không báo gì sai
├─ exposedHeaders   X-RateLimit-Remaining, Retry-After   ← giao diện đọc để biết còn
│                    bao nhiêu lượt gọi
└─ maxAge 3600, allowCredentials true
```

Giới hạn tần suất — theo NGƯỜI DÙNG (sub trong JWT), không theo địa chỉ IP:

```
Đếm theo IP sai ở CẢ HAI chiều, cả hai đều gây hại thật:
├─ QUÁ CHẶT   một trường đại học / toà nhà / nhà mạng NAT đẩy hàng nghìn người ra sau
│              MỘT địa chỉ — một người bấm nhanh chặn hết người còn lại, người bị chặn
│              không hiểu vì sao vì họ chưa làm gì cả
└─ QUÁ LỎNG   kẻ tấn công có sẵn dải địa chỉ, hoặc một máy chủ đám mây rẻ tiền — đổi IP
               là được cấp lại hạn ngạch

userOrIpKeyResolver (RateLimitConfig, tên bean được TRÍCH DẪN trong application.yaml
qua "#{@userOrIpKeyResolver}" — đổi tên hàm là hỏng cấu hình LÚC CHẠY, không phải lúc
biên dịch: Spring báo không tìm thấy bean khi tuyến đầu tiên được gọi tới)
├─ có Authentication đã xác thực (SecurityContext)  → key = "user:" + tên người dùng
│  ↳ đổi IP không giúp gì, người ngồi cạnh không bị vạ lây
└─ chưa đăng nhập                                    → key = "ip:" + địa chỉ từ xa
   ↳ chấp nhận được: phần lớn lưu lượng NẶNG đến từ người đã đăng nhập, và endpoint
     công khai (/api/search) chỉ ĐỌC — không đổi dữ liệu của ai
   ↳ tiền tố "ip:" để một tài khoản TÊN TRÙNG một địa chỉ IP không dùng chung gáo
     token với nó — va chạm không bao giờ phát hiện được bằng cách nhìn, chỉ hiện ra
     dưới dạng "thỉnh thoảng bị 429 vô cớ"

Chỉ áp cho search-api (search/suggest/images/feed) — endpoint quản trị và ghi dữ liệu
KHÔNG qua RequestRateLimiter ở Gateway; RateLimitFilter riêng chạy Ở TỪNG SERVICE
(order = Integer.MIN_VALUE), xem AUTH-PIPELINE.md cho bản của auth-service.
```

Thu hồi token (đăng xuất tức thời) — vì sao đặt ở Gateway, và cái giá của việc đó:

```
JWT tự chứng thực: ai cầm nó cũng chứng minh được danh tính mà không cần hỏi ai
→ đó là ưu điểm, và cũng là lý do KHÔNG XOÁ ĐƯỢC nó
  ba trường hợp để lại access token còn sống tới 15 phút: bấm "đăng xuất", tài khoản
  bị khoá, mật khẩu vừa đổi vì nghi bị chiếm

auth-service ghi jti của những token đó vào Redis (key "at:denied:" + jti, TTL = đúng
phần đời còn lại) → TokenDenylistFilter tra danh sách ấy

Vì sao đặt ở Gateway chứ không ở 7 service:
└─ đặt ở service = 7 lượt hỏi Redis cho MỘT trang chạm nhiều service, và 7 chỗ có thể
   quên hỏi. Đặt ở đây = 1 lượt, 1 chỗ.
   Cái giá: một service bị gọi THẲNG (không qua Gateway) vẫn chấp nhận token đã thu
   hồi tới khi hết hạn — chấp nhận được vì đường gọi thẳng chỉ tồn tại trong mạng nội
   bộ, cửa sổ tối đa 15 phút.

Vì sao Redis hỏng thì request vẫn ĐI TIẾP (fail-open), không chặn hết (fail-closed):
├─ CHẶN HẾT   nghe an toàn hơn, nhưng biến Redis thành điểm chết của TOÀN BỘ hệ thống
│              — Redis rớt là không ai đăng nhập được, không ai tìm kiếm được
└─ CHO QUA     thiệt hại giới hạn ở đúng những token vừa bị thu hồi trong 15 phút gần
                nhất — một tập rất nhỏ, mỗi lần xảy ra để lại một dòng log ERROR đếm được
   ↳ đây là đánh đổi CÓ CHỦ Ý cho một máy tìm kiếm. Ở hệ thống mà token mang quyền
     chuyển tiền, lựa chọn đúng sẽ là NGƯỢC LẠI (fail-closed).
```

Bề mặt quan sát được — vì sao KHÔNG dùng `management.endpoints.web.exposure.include: "*"`:

```
management.endpoints.web.exposure.include: health,metrics,prometheus,gateway
↳ nhóm mặc định ("*") còn chứa /actuator/env (phơi mọi biến môi trường — kể cả
  ADMIN_API_KEY, mật khẩu CSDL) và /actuator/heapdump (tải về TOÀN BỘ bộ nhớ tiến
  trình — ở Gateway, bộ nhớ đó chứa mọi access token vừa đi qua)
management.endpoint.health.show-details: never
/actuator/health/**, /actuator/prometheus  permitAll (Docker healthcheck + scrape)
/actuator/** còn lại                       hasRole("ADMIN")
GATEWAY_LOG_LEVEL (org.springframework.cloud.gateway) mặc định INFO, bật DEBUG để
xem một request rơi vào tuyến nào — rất ồn, chỉ bật lúc gỡ lỗi định tuyến, vì đó là
loại lỗi mà không có dòng log này thì chỉ còn cách đoán
```

Gộp tài liệu OpenAPI của 6 service qua `/swagger-ui.html` (springdoc, không proxy toàn
bộ vì crawler-service đứng sau tiền tố /api/admin, còn analytics-service và
football-service không có tài liệu riêng ở đây) — chỉ để xem, không ảnh hưởng luồng
request thật.
