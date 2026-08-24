Bản rút gọn dạng cây

Đăng ký — POST /api/auth/register:

```
AuthController.register(Credentials{username, password})     ← @Valid, permitAll
└─ UserService.register → createAccount(username, password, Role.USER)
   ├─ normalize(username)                → trim + toLowerCase(Locale.ROOT)
   ├─ validateUsername  USERNAME_PATTERN "^[a-zA-Z0-9._-]{3,32}$"
   ├─ validatePassword  MIN_PASSWORD_LENGTH 8 … MAX_PASSWORD_LENGTH 200
   │  ↳ chặn trên là bắt buộc: BCrypt cost 12 trên chuỗi dài tuỳ ý = DoS bằng CPU
   ├─ store.find(normalized).isPresent() → AuthException "tên đã tồn tại"
   ├─ encoder.encode(password)           BCryptPasswordEncoder(BCRYPT_COST = 12)
   └─ JsonUserStore.save(user)
      ├─ ConcurrentHashMap.put(key(username), user)
      └─ ghi data/users.json qua tệp .tmp + ATOMIC_MOVE      ← cùng cách ContentStorage ghi
   → 201 + User.PublicView (KHÔNG bao giờ trả passwordHash)
```

Đăng nhập — POST /api/auth/login:

```
AuthController.login(Credentials)
├─ UserService.login(username, password)
│  ├─ đang bị khoá tạm? (failures[username].lockedUntil > now)
│  │  → InvalidCredentialsException "thử lại sau N phút"
│  ├─ không có tài khoản
│  │  ├─ VẪN gọi encoder.encode(password)        ← chống dò tên tài khoản qua thời gian
│  │  ├─ recordFailure
│  │  └─ ném "Tên tài khoản hoặc mật khẩu không đúng."   ← MỘT thông điệp cho cả hai ca
│  ├─ encoder.matches sai → recordFailure + cùng thông điệp trên
│  │  └─ recordFailure: count++ ; count ≥ MAX_FAILED_ATTEMPTS 5
│  │     → lockedUntil = now + LOCKOUT_MINUTES 15, count về 0
│  ├─ !user.enabled() → "Tài khoản đã bị vô hiệu hoá."
│  └─ thành công → failures.remove + store.save(withLastLoginAt)
│     ↳ ghi mốc đăng nhập hỏng chỉ log.warn: không được chặn một lần đăng nhập hợp lệ
└─ SessionStore.open(user)
   ├─ sessions.size() ≥ MAX_SESSIONS 10 000 → purgeExpired, vẫn đầy → IllegalStateException
   ├─ SecureRandom 32 byte → Base64 URL không đệm            ← token đục, không mang dữ liệu
   └─ sessions[token] = Session(username, role, now, now + SESSION_HOURS 12)
   → LoginResponse(token, expiresAt, user.toPublic())
```

Mỗi request sau đó — chuỗi filter:

```
HTTP request  →  /api/*
├─ [order = Integer.MIN_VALUE] RateLimitFilter                    ← TRƯỚC mọi filter khác
│  ├─ enabled = app.security.rate-limit.enabled (true)
│  ├─ khoá theo IP: trustProxy ? X-Forwarded-For : getRemoteAddr()
│  │  ↳ app.security.trust-proxy mặc định FALSE: tin header khi không có proxy thật
│  │    thì bất kỳ ai cũng tự đặt IP giả để thoát giới hạn
│  ├─ buckets: ConcurrentHashMap, trần MAX_TRACKED_CLIENTS 100 000  ← chống phình bộ nhớ
│  └─ Bucket.tryConsume (token bucket)
│     ├─ tokens += elapsed · (requestsPerMinute / 60 000)   , trần = capacity 120
│     └─ tokens < 1 → 429 Too Many Requests
├─ [Spring Security] TokenAuthFilter                              ← đặt TRƯỚC ApiKeyAuthFilter
│  ├─ Authorization: Bearer <token>   (so tiền tố KHÔNG phân biệt hoa thường)
│  └─ SessionStore.lookup(token)
│     ├─ hết hạn → xoá khỏi map, coi như không có
│     └─ hợp lệ  → SecurityContext = (username, ROLE_USER | ROLE_ADMIN)
├─ [Spring Security] ApiKeyAuthFilter                             ← trước UsernamePasswordAuthenticationFilter
│  ├─ header X-API-Key
│  ├─ MessageDigest.isEqual(...)          ← so sánh THỜI GIAN KHÔNG ĐỔI, không dùng equals
│  ├─ đúng → SecurityContext = ("admin-api-key", ROLE_ADMIN)
│  └─ sai  → log.warn rồi ĐI TIẾP (để authorize quyết định mã trả về)
└─ authorizeHttpRequests — chặn trước, mở sau
   ├─ OPTIONS /**                                   permitAll   ← preflight không mang xác thực
   ├─ DispatcherType.ERROR                          permitAll   ★ xem ghi chú dưới
   ├─ /api/search /api/suggest /api/health
   │  /api/images /api/feed                         permitAll
   ├─ POST /api/events                              permitAll
   ├─ /actuator/health/** /actuator/prometheus      permitAll
   ├─ POST /api/auth/register|login|logout          permitAll
   ├─ /api/auth/**                                  authenticated
   ├─ /api/admin/** /actuator/**                    hasRole("ADMIN")
   └─ anyRequest()                                  denyAll
      ↳ mặc định ĐÓNG: thêm một endpoint đọc dữ liệu mà quên khai báo thì nó trả 401
        (đúng thứ đã xảy ra với /api/images lần chạy đầu)
   → chưa xác thực: HttpStatusEntryPoint(401), phiên STATELESS, CSRF tắt (không dùng cookie)
```

Vì sao dòng `DispatcherType.ERROR` phải có:

```
người ĐÃ đăng nhập gọi endpoint không đủ quyền
└─ AccessDeniedException → 403
   └─ Spring Boot FORWARD nội bộ tới /error để dựng thân phản hồi
      └─ lần forward đó đi lại chuỗi filter, lúc này SecurityContext ĐÃ BỊ XOÁ
         └─ /error không khớp danh sách nào → denyAll → 401 THAY THẾ mã 403 ban đầu
            └─ giao diện thấy 401 → đẩy về màn hình đăng nhập → đăng nhập lại thành công
               → lại bị đẩy về: vòng lặp không lối thoát
   ↳ chỉ lộ ra khi chạy thật — MockMvc mặc định không thực hiện lần gửi ERROR,
     nên bài kiểm thử tích hợp vẫn thấy 403 và vẫn xanh
```

Quản trị tài khoản — /api/admin/users/** (hasRole ADMIN):

```
GET    /api/admin/users                 → UserService.findAll → List<User.PublicView>
POST   /api/admin/users/{u}/role        → changeRole  → store.save
POST   /api/admin/users/{u}/disable     → setEnabled(false)
POST   /api/admin/users/{u}/enable      → setEnabled(true)
DELETE /api/admin/users/{u}             → delete + failures.remove
   ↳ mọi thao tác hạ quyền/vô hiệu/xoá đều kèm SessionStore huỷ phiên đang mở của
     tài khoản đó — nếu không, access token cũ vẫn sống hết TTL của nó với quyền CŨ
POST   /api/auth/password               → changePassword
   ├─ đang khoá tạm → từ chối
   ├─ sai mật khẩu hiện tại → recordFailure
   ├─ mật khẩu mới trùng mật khẩu cũ → AuthException
   └─ store.save(withPasswordHash)
```

Hai lối vào quyền ADMIN, cố ý khác nhau:

```
X-API-Key            khoá TĨNH từ biến môi trường ADMIN_API_KEY
                     ├─ thiếu     → IllegalStateException, ứng dụng KHÔNG khởi động
                     ├─ < 16 ký tự → IllegalStateException
                     └─ không có mật khẩu để đổi, không có phiên để huỷ
                        → dành cho script/CI, không dành cho người
Bearer <token>       cặp token OAuth2 của một tài khoản THẬT trong data/users.json
                     ├─ access token  TTL 15 phút   (app.auth.access-token-ttl, PT15M)
                     ├─ refresh token TTL 30 ngày   (app.auth.refresh-token-ttl, P30D)
                     └─ có vai trò, có thể bị vô hiệu hoá, có thể bị thu hồi
                        ↳ access ngắn để một token lọt ra ngoài chỉ dùng được 15 phút;
                          refresh dài để người dùng không phải đăng nhập lại mỗi ngày.
                          Thu hồi tác động lên refresh — access đang lưu hành vẫn sống
                          hết 15 phút của nó, đó là cái giá của token tự xác thực.
```
