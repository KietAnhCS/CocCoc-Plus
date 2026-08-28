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
   └─ JsonUserStore.save(user)  (hoặc PostgresUserStore nếu bật CSDL quan hệ)
      ├─ ConcurrentHashMap.put(key(username), user)   ← bản JSON
      └─ ghi data/users.json qua tệp .tmp + ATOMIC_MOVE      ← cùng cách ContentStorage ghi
   → 201 + User.PublicView (KHÔNG bao giờ trả passwordHash)
   └─ AuditLogger.record(...)  ghi sự kiện REGISTER
```

Đăng nhập — POST /api/auth/login — phát JWT, không phải token đục:

```
AuthController.login(Credentials)
├─ UserService.authenticate(username, password)
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
└─ TokenService.issueFor(user)
   ├─ AccessTokenIssuer.issue(user)   → JWT ký RS256 (RsaKeyProvider), TTL PT15M
   │  ↳ claim role, sub = username; xác thực bởi mọi service qua oauth2ResourceServer
   └─ RefreshTokenStore.issue(username, family=null, TTL P30D)
      ↳ InMemoryRefreshTokenStore (dev/test) hoặc RedisRefreshTokenStore (prod)
   → LoginResponse(token=accessToken, refreshToken, expiresAt, user.toPublic())
   → AuditLogger.record(...)  ghi sự kiện LOGIN
```

Gia hạn và đăng xuất:

```
POST /api/auth/refresh   RefreshRequest{refreshToken}   permitAll
└─ TokenService.refresh(refreshToken)
   ├─ RefreshTokenStore.consume(token)  ← XOAY VÒNG: token cũ chết ngay khi dùng
   │  └─ không hợp lệ / đã dùng rồi → InvalidGrantException
   │     ↳ dùng lại một refresh token đã tiêu thụ là dấu hiệu bị đánh cắp;
   │       tuỳ triển khai của RefreshTokenStore mà cả "family" bị thu hồi
   ├─ tra lại User từ CSDL (KHÔNG tin vai trò cũ trong token)
   │  → tài khoản bị khoá / xoá → revokeAllFor + InvalidGrantException
   └─ build(user, family) → TokenPair mới, cùng LoginResponse như /login

POST /api/auth/logout    RefreshRequest{refreshToken?}   permitAll   Authentication tuỳ chọn
└─ TokenService.logout(refreshToken, jwt.getId(), jwt.getExpiresAt())
   ├─ refreshTokens.revoke(refreshToken)     nếu có gửi kèm
   └─ denyAccessToken(jti, expiry)           đưa access token hiện tại vào danh sách thu hồi
      ↳ thiếu bước này thì access token vẫn sống hết 15 phút sau khi "đăng xuất"
   → luôn 204, kể cả token đã hết hạn/không tồn tại

POST /api/auth/logout-all   authenticated
└─ TokenService.logoutEverywhere(username) → revokeAllFor  (đóng MỌI refresh token)
   + denyAccessToken cho access token hiện tại
   → {closedSessions: n}

GET  /api/auth/me   authenticated
└─ có User tương ứng username  → {authenticated, via:"jwt", user}
   không có (principal = "admin-api-key") → {authenticated, via:"api-key", user:{username,role:ADMIN}}
```

Mỗi request sau đó — chuỗi filter (auth-service dùng SecurityFilterChain RIÊNG, khác 7 service
còn lại — xem `AuthSecurityConfig` / `ServiceSecurityConfig`):

```
HTTP request  →  /api/*  hoặc /oauth2/*
├─ [đăng ký ngoài chuỗi Security] RateLimitFilter — core-common, token bucket theo IP
│  ├─ enabled = app.security.rate-limit.enabled (true)
│  ├─ khoá theo IP: trustProxy ? X-Forwarded-For : getRemoteAddr()
│  │  ↳ app.security.trust-proxy mặc định FALSE: tin header khi không có proxy thật
│  │    thì bất kỳ ai cũng tự đặt IP giả để thoát giới hạn
│  ├─ buckets: ConcurrentHashMap; vượt MAX_TRACKED_CLIENTS 100 000 → CLEAR TOÀN BỘ map
│  │  ↳ đơn giản hơn LRU, đổi lại: hạn mức của mọi người bị reset khi tràn
│  └─ Bucket.tryConsume (token bucket, capacity = requestsPerMinute, nạp lại theo mili-giây)
│     └─ tokens < 1 → 429 + header Retry-After: 60
├─ [Spring Security] ApiKeyAuthFilter — addFilterBefore UsernamePasswordAuthenticationFilter
│  ├─ header X-API-Key
│  ├─ MessageDigest.isEqual(...)          ← so sánh THỜI GIAN KHÔNG ĐỔI, không dùng equals
│  ├─ đúng → SecurityContext = ("admin-api-key", ROLE_ADMIN)
│  └─ sai/thiếu → ĐI TIẾP (để authorize/oauth2ResourceServer quyết định)
├─ [Spring Security] oauth2ResourceServer().jwt(...)
│  ├─ xác minh chữ ký RS256 bằng khoá công khai (jwt-decoder trỏ /oauth2/jwks, hoặc
│  │  LocalJwtDecoderConfig khi auth-service tự giải mã token của chính nó)
│  ├─ JwtRoleConverter: claim role → GrantedAuthority ROLE_xxx
│  └─ hợp lệ → SecurityContext = JwtAuthenticationToken(username, ROLE_USER|ROLE_ADMIN)
│     ↳ KHÔNG tra bảng revoke tại filter này — việc kiểm access token đã bị logout hay
│       chưa (isAccessTokenDenied) do controller/AuthExceptionHandler tự gọi khi cần,
│       vì JWT là tự xác thực: revoke chỉ chặn được ở nơi có gọi tới TokenService
└─ authorizeHttpRequests — chặn trước, mở sau
   ├─ OPTIONS /**                                   permitAll   ← preflight không mang xác thực
   ├─ DispatcherType.ERROR                          permitAll   ★ xem ghi chú dưới
   ├─ /oauth2/token /oauth2/revoke /oauth2/jwks
   │  /.well-known/**                                permitAll   ← chỉ auth-service có nhóm này
   ├─ POST /api/auth/register|login|logout|refresh   permitAll
   ├─ /api/auth/**  (còn lại)                        authenticated
   ├─ /api/admin/** /actuator/**                     hasRole("ADMIN")
   └─ anyRequest()                                   denyAll
      ↳ mặc định ĐÓNG: thêm một endpoint đọc dữ liệu mà quên khai báo thì nó trả 401
   → chưa xác thực: HttpStatusEntryPoint(401), phiên STATELESS, CSRF tắt (token không
     đi trong cookie nên không có gì để CSRF giả mạo)
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
GET    /api/admin/users/stats           → AccountStats(total, admins, disabled, activeSessions)
   ↳ activeSessions = TokenService.activeSessionCount() = số refresh token còn sống
     (Redis: duyệt không gian khoá — CHỈ gọi cho bảng điều khiển, không trên đường request)
POST   /api/admin/users/{u}/role        → changeRole
   ├─ tự hạ quyền chính mình (khác ADMIN) → 400 BadRequest, chặn TRƯỚC khi đổi
   └─ đổi xong → tokens.logoutEverywhere(u)   ← đóng cả khi NÂNG quyền, không chỉ hạ
POST   /api/admin/users/{u}/disable     → setEnabled(false) + logoutEverywhere
   └─ tự vô hiệu hoá chính mình → 400 BadRequest
POST   /api/admin/users/{u}/enable      → setEnabled(true)   (không đóng phiên — không có gì để đóng)
DELETE /api/admin/users/{u}             → delete + logoutEverywhere
   └─ tự xoá chính mình → 400 BadRequest
   ↳ mọi thao tác hạ quyền/vô hiệu/xoá đều kèm đóng phiên đang mở của tài khoản đó —
     nếu không, access token cũ vẫn sống hết 15 phút với quyền CŨ
   ↳ mỗi thao tác đều gọi AuditLogger.record(actor, action, "auth_users:{u}", "SUCCESS", ...)
POST   /api/auth/password               → UserService.changePassword
   ├─ đang khoá tạm → từ chối
   ├─ sai mật khẩu hiện tại → recordFailure
   ├─ mật khẩu mới trùng mật khẩu cũ → AuthException
   └─ đổi xong → tokens.logoutEverywhere(username) + denyAccessToken(jti hiện tại)
      ↳ KHÔNG giữ lại phiên đang gọi — với refresh token xoay vòng, "giữ lại" nghĩa là
        giữ lại đúng token có thể đã bị đánh cắp; giao diện tự đăng nhập lại bằng mật
        khẩu mới ngay sau đó
```

Hai lối vào quyền ADMIN, cố ý khác nhau:

```
X-API-Key            khoá TĨNH từ biến môi trường ADMIN_API_KEY
                     ├─ thiếu     → IllegalStateException, ứng dụng KHÔNG khởi động
                     ├─ < 16 ký tự → IllegalStateException
                     └─ không có mật khẩu để đổi, không có refresh token để thu hồi
                        → dành cho script/CI, không dành cho người
Bearer <jwt>          JWT RS256 của một tài khoản THẬT, phát bởi TokenService
                     ├─ access token  TTL 15 phút   (app.auth.access-token-ttl,  PT15M)
                     ├─ refresh token TTL 30 ngày   (app.auth.refresh-token-ttl, P30D)
                     ├─ refresh token XOAY VÒNG mỗi lần /refresh — dùng lại token cũ bị
                     │  coi là dấu hiệu đánh cắp
                     └─ có vai trò (claim trong JWT), có thể bị vô hiệu hoá, có thể bị
                        thu hồi qua logout / logout-all / đổi vai trò / đổi mật khẩu
                        ↳ access ngắn để một token lọt ra ngoài chỉ dùng được 15 phút;
                          refresh dài để người dùng không phải đăng nhập lại mỗi ngày.
                          Thu hồi tác động lên refresh — access đang lưu hành vẫn sống
                          hết 15 phút của nó, đó là cái giá của token tự xác thực (JWT
                          không cần tra bảng để xác minh chữ ký, nên revoke tức thời
                          không miễn phí: phải tra denylist ở nơi thật sự cần).
```

`/oauth2/token`, `/oauth2/jwks` — lối vào OAuth2 chuẩn cho bên thứ ba, song song `/api/auth/**`:

```
OAuth2Controller — cùng TokenService, chỉ khác vỏ JSON (đặt tên trường theo RFC 6749 §5.1:
access_token, token_type, expires_in, refresh_token) thay vì vỏ riêng của AuthController.
Hai lối vào, MỘT cơ chế cấp/thu hồi — tránh hai đường lệch nhau (một đường quên ghi
refresh token vào kho, người dùng đi đường đó không đăng xuất được).
```
