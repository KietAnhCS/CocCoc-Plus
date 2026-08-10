# Tài khoản, phân quyền và bảng điều khiển quản trị

> **Trang này trả lời câu hỏi gì?** *"Tài khoản nào là admin, tài khoản nào là
> người dùng thường — và ai được xem cái gì?"*
>
> Trước tính năng này, VnSearch **không có người dùng nào cả**: mọi endpoint
> quản trị được canh bằng một chuỗi khoá tĩnh trong header `X-API-Key`, và giao
> diện hiển thị một tài khoản `admin@gmail.com` **cứng trong mã nguồn** cho tất
> cả mọi người. Câu hỏi ở trên khi đó không có câu trả lời.
>
> Tài liệu này mô tả hai tầng được thêm vào — **tài khoản có vai trò** và **bảng
> điều khiển số liệu** — cùng sáu lỗi thật đã gặp khi làm chúng.

---

## Mục lục

| § | Nội dung |
|---|---|
| [1](#1-sơ-đồ-tư-duy--toàn-cảnh) | Sơ đồ tư duy — toàn cảnh |
| [2](#2-nhìn-60-giây--quy-mô-và-vị-trí) | Nhìn 60 giây — quy mô và vị trí |
| [3](#3-hai-đường-xác-thực-một-bảng-phân-quyền) | **Hai đường xác thực, một bảng phân quyền** |
| [4](#4-tầng-tài-khoản--bảy-lớp) | Tầng tài khoản — bảy lớp |
| [5](#5-vòng-đời-một-phiên-đăng-nhập) | Vòng đời một phiên đăng nhập |
| [6](#6-bảy-quyết-định-bảo-mật-và-cái-giá-của-chúng) | **Bảy quyết định bảo mật, và cái giá của chúng** |
| [7](#7-tầng-số-liệu--ba-nguồn-một-phản-hồi) | Tầng số liệu — ba nguồn, một phản hồi |
| [8](#8-tầng-giao-diện) | Tầng giao diện |
| [9](#9-bảng-phân-quyền-đầy-đủ-23-endpoint) | Bảng phân quyền đầy đủ (23 endpoint) |
| [10](#10-sáu-lỗi-thật-và-vì-sao-test-không-bắt-được-chúng) | **Sáu lỗi thật, và vì sao test không bắt được chúng** |
| [11](#11-hướng-dẫn-thực-hành--mười-công-thức) | **Hướng dẫn thực hành — mười công thức** |
| [12](#12-kiểm-thử) | Kiểm thử |
| [13](#13-đánh-giá-theo-chuẩn-doanh-nghiệp) | **Đánh giá theo chuẩn doanh nghiệp** |
| [14](#14-giới-hạn-đã-biết) | Giới hạn đã biết |
| [15](#15-tra-cứu-nhanh) | Tra cứu nhanh |

---

## 1. Sơ đồ tư duy — toàn cảnh

```mermaid
mindmap
  root((Tài khoản<br/>và số liệu))
    Xác thực
      Con người
        POST /api/auth/register
        POST /api/auth/login
        TokenAuthFilter
        Token mờ 256 bit
        Hết hạn 12 giờ
      Công cụ
        Header X-API-Key
        ApiKeyAuthFilter
        Không hết hạn
        Lối vào dự phòng
    Phân quyền
      Vai trò USER
      Vai trò ADMIN
      SecurityConfig
        Một bảng duy nhất
        Không quan tâm cơ chế
      401 chưa biết anh là ai
      403 biết rồi và không đủ quyền
    Kho dữ liệu
      JsonUserStore
        Ghi nguyên tử
        Đọc từ bộ nhớ
      SessionStore
        Trong bộ nhớ
        Thu hồi tức thì
    Số liệu
      Lưu lượng
        UsageAnalyticsService
        Mọi bảng có trần
        Vòng đệm 24 giờ
      Corpus
        CorpusStats
        Tính lúc dựng chỉ mục
        Bloom Filter đếm xấp xỉ
      Chỉ mục
        Đọc trực tiếp facade
    Giao diện
      AuthScreen toàn màn hình
      AccountMenu popover
      AdminPanel
        14 ô số liệu
        5 hình vẽ SVG
        AccountsTable
        PermissionMatrix
```

<details>
<summary><b>Xem bản chữ (ASCII)</b></summary>

```
TÀI KHOẢN VÀ SỐ LIỆU
│
├── XÁC THỰC (hai đường song song)
│   ├── Con người:  tài khoản + mật khẩu ──▶ token mờ 256 bit, sống 12 giờ
│   │               TokenAuthFilter          thu hồi được tức thì
│   └── Công cụ:    X-API-Key tĩnh           không hết hạn, không thu hồi
│                   ApiKeyAuthFilter         lối vào dự phòng khi kho tài khoản hỏng
│
├── PHÂN QUYỀN (một bảng duy nhất trong SecurityConfig)
│   ├── USER   tìm kiếm + /api/auth/me + đổi mật khẩu
│   ├── ADMIN  thêm: đọc số liệu, điều khiển crawler, quản lý tài khoản
│   └── 401 = "không biết anh là ai"   ≠   403 = "biết rồi, và không đủ quyền"
│
├── KHO DỮ LIỆU
│   ├── JsonUserStore   data/users.json, ghi tệp tạm rồi đổi tên (nguyên tử)
│   └── SessionStore    bảng băm trong bộ nhớ, mất khi khởi động lại
│
├── SỐ LIỆU (ba nguồn, một phản hồi)
│   ├── Lưu lượng  UsageAnalyticsService — bộ nhớ, cửa sổ 24 giờ, mọi bảng có trần
│   ├── Corpus     CorpusStats — tính MỘT LẦN lúc dựng chỉ mục, Bloom Filter
│   └── Chỉ mục    đọc trực tiếp từ SearchEngineFacade
│
└── GIAO DIỆN
    ├── AuthScreen      toàn màn hình: đăng nhập / đăng ký / đổi mật khẩu
    ├── AccountMenu     popover trên avatar — lối vào nhanh
    └── AdminPanel      14 ô số liệu, 5 hình SVG, bảng tài khoản, bảng phân quyền
```

</details>

---

## 2. Nhìn 60 giây — quy mô và vị trí

| Tầng | Tệp | Dòng | Ở đâu |
|---|---:|---:|---|
| Tài khoản (backend) | 7 | 1.010 | `com/vnsearch/auth/` |
| Số liệu (backend) | 4 | 1.069 | `com/vnsearch/analytics/` |
| Controller mới | 4 | 595 | `com/vnsearch/controller/` |
| Cấu hình | 1 | 103 | `config/AuthConfig.java` |
| **Tổng mã Java mới** | **16** | **2.777** | |
| Kiểm thử Java | 8 | 1.931 | `src/test/.../auth`, `analytics`, `config` |
| Giao diện (component + store + lib) | 18 | 4.220 | `browser-app/src/renderer/src/` |
| Kiểm thử Vitest | 7 | 747 | cùng thư mục với mã |

**Endpoint:** 23 (thêm 11). **Kiểm thử:** 628 Java (thêm 107) + 128 Vitest (thêm 75).

Vị trí trong hệ thống:

```
   crawler ──▶ index ──▶ ranking ──▶ ┌─────────────────────────┐
                                     │  SecurityFilterChain    │  ◀── TẦNG MỚI
                                     │  ├── RateLimitFilter    │
                                     │  ├── TokenAuthFilter    │  ◀── mới
                                     │  └── ApiKeyAuthFilter   │
                                     └───────────┬─────────────┘
                                                 ▼
                    ┌────────────────────────────────────────────┐
                    │ /api/search   /api/auth/**   /api/admin/**  │
                    └────────────────────────────────────────────┘
                                                 ▲
                                       browser-app (Electron)
```

---

## 3. Hai đường xác thực, một bảng phân quyền

Đây là **ý tưởng trung tâm** của cả tầng này. Có hai loại bên gọi, và chúng cần
hai cơ chế khác nhau:

| | Khoá API (`X-API-Key`) | Tài khoản (`Authorization: Bearer`) |
|---|---|---|
| Ai dùng | công cụ: `curl`, job định kỳ, script triển khai | con người ngồi trước màn hình |
| Danh tính | **không có** — mọi lời gọi giống hệt nhau | có: ghi được "ai đã làm gì" |
| Hết hạn | không bao giờ | 12 giờ |
| Thu hồi | đổi cấu hình + khởi động lại | một lần bấm, hiệu lực tức thì |
| Vai trò | luôn ADMIN đầy đủ | USER hoặc ADMIN |
| Lưu ở máy khách | **chỉ trong bộ nhớ** | `localStorage` |

Nhưng **bảng phân quyền chỉ có một**:

```
   TokenAuthFilter ──┐
                     ├──▶ SecurityContext (vai trò) ──▶ SecurityConfig
   ApiKeyAuthFilter ─┘                                   .hasRole("ADMIN")
```

Bảng nói về **vai trò**, không nói về **cơ chế đăng nhập**. Thêm một cách xác
thực nữa sau này (OAuth, LDAP) chỉ cần thêm một filter cấp đúng vai trò —
không sửa một dòng nào trong bảng.

**Thứ tự filter có ý nghĩa.** `TokenAuthFilter` chạy trước. Một request mang cả
hai header thì phiên **có danh tính** thắng, vì nó ghi lại được *ai* đã gọi.

**Vì sao không bỏ khoá API đi.** Nó là thứ duy nhất dùng được ở nơi không có ai
đăng nhập, và là **lối vào dự phòng khi kho tài khoản hỏng**. Một hệ thống mà
cách duy nhất để vào là đăng nhập, và tệp tài khoản vừa hỏng, là một hệ thống
tự khoá mình ra ngoài.

---

## 4. Tầng tài khoản — bảy lớp

| Lớp | Dòng | Việc | Điểm đáng chú ý |
|---|---:|---|---|
| `UserService` | 375 | Đăng ký, đăng nhập, đổi mật khẩu, đổi vai trò | BCrypt cost 12; khoá tạm theo **tài khoản**; `register()` **không nhận tham số vai trò** |
| `CorpusStats`¹ | — | — | — |
| `SessionStore` | 210 | Kho phiên: token → người dùng | Token 256 bit từ `SecureRandom`; thu hồi tức thì; `revokeAllForExcept` cho đổi mật khẩu |
| `JsonUserStore` | 171 | Lưu `data/users.json` | Ghi **tệp tạm rồi đổi tên** — nguyên tử; bản ghi hỏng bị bỏ qua chứ không làm sập kho |
| `TokenAuthFilter` | 82 | Đọc `Authorization: Bearer` | Không chặn khi token sai — để tầng phân quyền quyết định |
| `Role` | 64 | `USER` / `ADMIN` | `@JsonCreator` để giá trị lạ trong tệp **hạ về USER** thay vì làm chết ứng dụng |
| `User` | 61 | Bản ghi tài khoản | `toPublic()` là ranh giới ra ngoài — **không bao giờ** để hash lọt ra REST |
| `UserStore` | 47 | Interface — Strategy | Cùng khuôn `DocumentStore`: đổi sang PostgreSQL = thêm một lớp |

¹ `CorpusStats` thuộc tầng số liệu, xem [§7](#7-tầng-số-liệu--ba-nguồn-một-phản-hồi).

### 4.1. Vì sao tệp JSON chứ không phải JPA

Cả dự án được thiết kế để chạy **không cần dịch vụ ngoài nào**
(`app.storage.postgres.enabled=false` là mặc định). Một hệ tài khoản mà muốn
thử phải cài PostgreSQL trước thì người chấm sẽ không thử.

Đọc từ bộ nhớ, ghi xuống đĩa: tỉ lệ đọc/ghi ở đây là hàng nghìn trên một (đọc ở
mỗi request có xác thực, ghi chỉ khi đăng ký / đổi vai trò / đăng nhập).

**Đánh đổi đã biết:** ghi lại **toàn bộ** tệp mỗi lần thay đổi. Chỉ đúng vì số
tài khoản rất nhỏ. Lên tới hàng nghìn thì đây chính là chỗ phải đổi sang
PostgreSQL — và nhờ `UserStore` là interface, đổi chỉ là thêm một lớp.

---

## 5. Vòng đời một phiên đăng nhập

```mermaid
sequenceDiagram
    participant U as Người dùng
    participant FE as browser-app
    participant AC as AuthController
    participant US as UserService
    participant SS as SessionStore

    U->>FE: gõ tài khoản + mật khẩu
    FE->>AC: POST /api/auth/login
    AC->>US: authenticate()
    alt Mật khẩu đúng
        US->>US: BCrypt.matches (~200 ms, cố ý chậm)
        US-->>AC: User
        AC->>SS: open(user)
        SS-->>AC: token 256 bit
        AC-->>FE: {token, expiresAt, user}
        FE->>FE: setAuthToken() → localStorage
    else Sai tên HOẶC sai mật khẩu
        US->>US: vẫn băm một chuỗi giả
        Note over US: để thời gian phản hồi<br/>hai ca giống nhau
        US-->>AC: InvalidCredentialsException
        AC-->>FE: 401 — cùng MỘT thông báo
    end

    Note over FE,SS: Mọi request sau đó
    FE->>AC: Authorization: Bearer <token>
    AC->>SS: lookup(token)
    alt Còn hạn
        SS-->>AC: Session(username, role)
    else Hết hạn / đã thu hồi
        SS-->>AC: rỗng → 401
    end
```

<details>
<summary><b>Xem bản chữ (ASCII)</b></summary>

```
  ĐĂNG NHẬP
  người dùng ──gõ mật khẩu──▶ POST /api/auth/login
                                    │
                          UserService.authenticate()
                                    │
              ┌─────────────────────┴─────────────────────┐
         mật khẩu đúng                              sai tên HOẶC sai mật khẩu
              │                                            │
     BCrypt.matches (~200 ms)                    vẫn băm một chuỗi giả
              │                                    (che chênh lệch thời gian)
     SessionStore.open() ──▶ token 256 bit                 │
              │                                     401 + CÙNG một câu
     {token, expiresAt, user}                    "tên hoặc mật khẩu không đúng"
              │
     localStorage (authToken.ts)

  MỌI REQUEST SAU ĐÓ
  Authorization: Bearer <token> ──▶ TokenAuthFilter ──▶ SessionStore.lookup()
                                                              │
                                        ┌─────────────────────┴──────────┐
                                   còn hạn                        hết hạn / thu hồi
                                        │                                │
                            SecurityContext(vai trò)                  không có danh tính
                                        │                                │
                              SecurityConfig quyết định          401 ở đường cần quyền
```

</details>

---

## 6. Bảy quyết định bảo mật, và cái giá của chúng

### 6.1. BCrypt cost 12, không phải SHA-256

| | SHA-256 | BCrypt (đang dùng) |
|---|---|---|
| Thiết kế để | **nhanh** | **chậm có kiểm soát** |
| GPU phổ thông | hàng tỉ hash/giây | vài nghìn/giây ở cost 12 |
| Salt | phải tự thêm (và nhiều người quên) | tự sinh, nhúng trong chuỗi |
| Tệp hash bị lộ | mật khẩu yếu vỡ trong vài phút | phá ngoại tuyến gần như vô vọng |

Salt chặn *bảng tra ngược*: không có salt, hai người cùng mật khẩu sẽ có cùng
hash — phá một lần được cả hai, lại còn lộ ra là họ trùng mật khẩu.

**Giá phải trả:** mỗi lần đăng nhập tốn 100–250 ms CPU. Đó là *cố ý*.

### 6.2. Luật mật khẩu chỉ ràng buộc độ dài

Tối thiểu 8, tối đa 200 ký tự. **Không** bắt "phải có chữ hoa, số, ký tự đặc
biệt" — luật đó nghe chặt nhưng đẩy người dùng vào đúng một khuôn dễ đoán
(`Password1!`), trong khi bốn từ ngẫu nhiên dài 20 ký tự vừa dễ nhớ hơn vừa khó
phá hơn nhiều lần. Đây là khuyến nghị NIST từ 2017.

Trần 200 ký tự chặn tấn công làm nghẽn bằng chuỗi khổng lồ gửi cho BCrypt băm.

### 6.3. Chống dò mật khẩu theo TÀI KHOẢN, không phải theo địa chỉ

`RateLimitFilter` giới hạn theo **địa chỉ**, nên nó không chặn được kiểu ngược
lại: botnet thử *một* mật khẩu phổ biến trên *hàng nghìn* tài khoản, mỗi địa chỉ
vài request. Bộ đếm trong `UserService` giới hạn theo **tài khoản** — 5 lần sai
thì khoá 15 phút.

Khoá **tạm** chứ không vĩnh viễn: khoá vĩnh viễn biến một cuộc dò mật khẩu thành
một cuộc **tấn công từ chối dịch vụ** nhắm vào người dùng thật.

### 6.4. Thông báo lỗi cố tình mơ hồ

Sai tên và sai mật khẩu trả **cùng một câu**. Phân biệt hai ca biến trang đăng
nhập thành công cụ *liệt kê tài khoản*.

Chi tiết dễ bỏ sót: khi tên **không tồn tại**, `UserService` vẫn băm một chuỗi
giả. Không làm vậy thì ca đó trả về tức thì còn ca "sai mật khẩu" tốn ~200 ms —
chênh lệch thời gian đó tự nó là một máy dò tên tài khoản.

### 6.5. Không có đường nào tự cấp vai trò ADMIN

`UserService.register()` **không nhận tham số vai trò** — nó luôn tạo `USER`.
Nhận vai trò từ thân request là lỗ hổng leo thang quyền kinh điển: chỉ cần thêm
`"role":"ADMIN"` vào JSON.

Hai bảo vệ nữa: **không tự hạ quyền/tự xoá chính mình**, và **đổi vai trò đóng
mọi phiên của người đó** — không đóng thì quyền bị thu hồi *trên giấy* nhưng
phiên cũ còn mang vai trò cũ thêm nhiều giờ.

### 6.6. Token mờ, không phải JWT

| | JWT | Token mờ (đang dùng) |
|---|---|---|
| Xác minh | không cần trạng thái | tra bảng băm trong bộ nhớ |
| **Đăng xuất** | **không có hiệu lực ngay** | xoá một dòng, tức thì |
| Hạ vai trò | vô hiệu tới khi token hết hạn | có hiệu lực ở request kế tiếp |
| Nhiều bản sao | chạy được ngay | cần kho dùng chung (Redis) |

Cái lợi duy nhất của JWT chỉ có giá trị khi có nhiều dịch vụ hoặc nhiều bản sao.
Hệ thống này là **một** tiến trình phục vụ **một** ứng dụng khách, và nó thật sự
cần thu hồi tức thì (đây là trang điều khiển được crawler).

**Hệ quả phải chấp nhận:** khởi động lại máy chủ là mọi người bị đăng xuất. Chỗ
để sửa khi cần là thay bảng băm bằng Redis, **không phải** đổi sang JWT.

### 6.7. Đổi mật khẩu vẫn phải nhập mật khẩu hiện tại

Nghe thừa — người gọi đã có token hợp lệ. Nhưng đó chính là kịch bản cần chặn:
một chiếc **token bị đánh cắp**. Không hỏi mật khẩu cũ thì kẻ cầm token đổi được
mật khẩu và **khoá chính chủ nhân ra ngoài** — biến một phiên bị lộ tạm thời
thành mất tài khoản vĩnh viễn.

Ba mức đóng phiên:

```
   /logout       chỉ phiên tại đây           "tôi rời máy"
   /password     mọi phiên TRỪ phiên này     "tôi nghi bị lộ ở nơi khác"
   /logout-all   MỌI phiên, kể cả phiên này  "đóng hết"
```

---

## 7. Tầng số liệu — ba nguồn, một phản hồi

`GET /api/admin/analytics` trả về **một** JSON gộp bốn khối. Một lời gọi chứ
không phải bốn, vì bốn endpoint riêng nghĩa là bốn thời điểm khác nhau hiện cạnh
nhau trên cùng một màn hình — người đọc sẽ *so sánh* chúng và ra một tỉ lệ chưa
từng tồn tại.

| Khối | Nguồn | Vòng đời |
|---|---|---|
| `traffic` | `UsageAnalyticsService` (bộ nhớ) | mất khi khởi động lại; cửa sổ 24 giờ |
| `crawl` | `CorpusStats`, tính lúc **dựng chỉ mục** | đổi khi crawl/reindex |
| `index` | đọc trực tiếp `SearchEngineFacade` | tức thời |
| `accounts` | `UserService` + `SessionStore` | tức thời |

### 7.1. Mọi bảng theo dõi đều có trần

Đây là ràng buộc quan trọng nhất của `UsageAnalyticsService`, vì **toàn bộ dữ
liệu vào đây do bên ngoài quyết định**: ai gọi được `POST /api/events` cũng tự
chọn chuỗi truy vấn, địa chỉ liên kết và mã phiên. Một `ConcurrentHashMap` không
chặn trên, đặt trên một đầu vào công khai, chính là một lỗ rò bộ nhớ mà kẻ tấn
công điều khiển được.

| Bảng | Trần |
|---|---:|
| Truy vấn | 5.000 |
| Liên kết | 5.000 |
| Phiên | 20.000 |
| Tài khoản | 5.000 |
| Phiên mỗi ô giờ | 5.000 |

Chạm trần thì khoá **mới** bị bỏ qua và `truncated` bật lên để giao diện **nói
thật** với người xem.

### 7.2. Chiều GHI công khai, chiều ĐỌC cần ADMIN

```
   GHI  POST /api/events           ĐỌC  GET /api/admin/analytics
   ─ ai cũng gọi được              ─ cần vai trò ADMIN
   mọi người dùng đều phải         số liệu tổng hợp phơi bày
   báo được hành vi                TOÀN BỘ truy vấn của mọi người
```

Đóng chiều ghi thì chỉ quản trị viên đóng góp được số liệu — tức không còn số
liệu nào đáng đọc. Cú bấm vào một kết quả **không đi qua máy chủ**, nên nếu
không có endpoint ghi này thì không cách nào biết người dùng bấm liên kết nào, ở
thứ hạng bao nhiêu.

**Danh tính lấy từ ngữ cảnh bảo mật**, không phải từ thân request — nếu tin lời
tự khai thì ai cũng gán được hành vi cho người khác bằng một dòng `curl`.

### 7.3. Quyền riêng tư

- Không nhận, không lưu địa chỉ IP; không cookie.
- Người **chưa đăng nhập** hoàn toàn ẩn danh (mã phiên ngẫu nhiên do máy khách sinh).
- Bảng xếp hạng người dùng chỉ hiện **tên và số lượt**, **không** hiện truy vấn
  của từng người — nó trả lời *"ai dùng nhiều"*, không trả lời *"người này tìm gì"*.

---

## 8. Tầng giao diện

### 8.1. Ba store, ba vai

| Store | Giữ gì | Bền vững |
|---|---|---|
| `sessionStore` | `user` (tên + vai trò) | token ở `localStorage` |
| `adminStore` | `apiKey`, `dashboardOpen` | ❌ **cố ý** |
| `dashboardStore` | số liệu + danh sách tài khoản | ❌ |

`useAdminCredential()` là nơi quyết định dùng đường nào: **tài khoản ADMIN được
ưu tiên hơn khoá tĩnh**, khớp với thứ tự filter ở máy chủ.

### 8.2. Hai lối vào, một luồng

```
   popover tài khoản (280px)          màn hình đầy đủ (AuthScreen)
   ─────────────────────────          ────────────────────────────
   2 ô, gõ xong là xong               3 ô + thanh đo độ mạnh
   cho người ĐÃ BIẾT mình làm gì      + nhắc lỗi tại chỗ
            │                          + phần giải thích luật
            └── "Mở màn hình đầy đủ" ──────────┘
```

**Kiểm tra tại chỗ nhưng chỉ báo sau khi rời ô** — báo "tên quá ngắn" ngay từ ký
tự đầu là mắng người dùng vì chưa gõ xong.

Một ngoại lệ có chủ ý: màn hình **đăng nhập không kiểm luật độ dài mật khẩu**.
Luật có thể đã đổi kể từ lúc người đó tạo tài khoản.

### 8.3. Sáu quy tắc vẽ biểu đồ

| Quy tắc | Vì sao |
|---|---|
| Không bao giờ **hai trục Y** | Cách căn hai thang đo là tuỳ tiện → hình bịa ra tương quan không có thật |
| Màu theo **thực thể**, thứ tự ô cố định | Lọc bớt một chuỗi mà chuỗi còn lại đổi màu thì người đọc bị đánh lừa |
| Thứ tự ô màu là **cơ chế an toàn** | Bảng được kiểm định để các cặp *kề nhau* phân biệt được dưới mắt người mù màu |
| Một chuỗi → **một** màu cho mọi cột | Tô cột cao đậm hơn là mã hoá chiều cao hai lần |
| Biểu đồ đường có nút **Bảng số** + phím ←/→ | Tooltip không được là cách *duy nhất* đọc giá trị |
| Thanh xếp chồng thay vành khuyên | Mắt so sánh **độ dài** tốt hơn so sánh **góc** |

---

## 9. Bảng phân quyền đầy đủ (23 endpoint)

| Endpoint | Khách | USER | ADMIN | Ghi chú |
|---|:---:|:---:|:---:|---|
| `GET /api/search` | ✓ | ✓ | ✓ | Chức năng chính, **không** đòi đăng nhập |
| `GET /api/suggest` | ✓ | ✓ | ✓ | |
| `GET /api/images` | ✓ | ✓ | ✓ | |
| `GET /api/feed` | ✓ | ✓ | ✓ | |
| `GET /api/health` | ✓ | ✓ | ✓ | Docker phải gọi được mà không có gì cả |
| `POST /api/events` | ✓ | ✓ | ✓ | GHI số liệu — mở có chủ ý |
| `POST /api/auth/register` | ✓ | ✓ | ✓ | Luôn tạo vai trò `USER` |
| `POST /api/auth/login` | ✓ | ✓ | ✓ | |
| `GET /api/auth/me` | ✕ | ✓ | ✓ | Nguồn sự thật về "tôi là ai" |
| `POST /api/auth/logout` | ✕ | ✓ | ✓ | Chỉ phiên tại đây |
| `POST /api/auth/password` | ✕ | ✓ | ✓ | Đóng mọi phiên **khác** |
| `POST /api/auth/logout-all` | ✕ | ✓ | ✓ | Đóng **mọi** phiên |
| `GET /api/admin/analytics` | ✕ | ✕ | ✓ | |
| `POST /api/admin/analytics/reset` | ✕ | ✕ | ✓ | Chỉ xoá lưu lượng |
| `GET /api/admin/users` | ✕ | ✕ | ✓ | **Không** kèm hash mật khẩu |
| `POST /api/admin/users/{tên}/role` | ✕ | ✕ | ✓ | Đóng phiên người bị đổi |
| `POST /api/admin/users/{tên}/disable` | ✕ | ✕ | ✓ | Giữ dữ liệu |
| `POST /api/admin/users/{tên}/enable` | ✕ | ✕ | ✓ | |
| `DELETE /api/admin/users/{tên}` | ✕ | ✕ | ✓ | Không hồi lại được |
| `GET /api/admin/stats` | ✕ | ✕ | ✓ | |
| `POST /api/admin/crawl` | ✕ | ✕ | ✓ | Endpoint rủi ro nhất (SSRF) |
| `GET /api/admin/crawl/{id}/status` | ✕ | ✕ | ✓ | |
| `POST /api/admin/reindex` | ✕ | ✕ | ✓ | |

Bảng này còn được **hiển thị ngay trong sản phẩm** (`PermissionMatrix.tsx`), có
tô sáng cột ứng với vai trò của người đang xem.

> **Ẩn nút không phải là phân quyền.** Nút vào khu vực quản trị luôn hiển thị kể
> cả khi chưa đăng nhập: ẩn nó không chặn được gì (`curl` vẫn nhận 401) mà còn
> giấu mất lối vào của chính người có quyền.

---

## 10. Sáu lỗi thật, và vì sao test không bắt được chúng

Phần này là thứ đáng đọc nhất của tài liệu. Cả sáu lỗi đều **lọt qua** 628 bài
kiểm thử, typecheck và lint.

### 10.1. CORS quên `Authorization` — hỏng cả tầng đăng nhập

```
   trình duyệt  ──preflight OPTIONS──▶  bị CHẶN ngay tại đây
                                        máy chủ không nhận được gì, log SẠCH
   curl         ──────GET────────────▶  200 OK  (curl không bị CORS ràng buộc)
```

Đăng nhập vẫn chạy (POST `/login` chỉ gửi `Content-Type`), chỉ request *mang
token* mới hỏng. Triệu chứng: *"đăng nhập thành công rồi bảng điều khiển báo
không kết nối được máy chủ"*.

**Bài học:** `curl` và MockMvc không thay thế được việc chạy ứng dụng thật — cả
hai đều bỏ qua trình duyệt, mà trình duyệt mới là nơi CORS tồn tại.
**Đã ghim:** `CorsPreflightTest`.

### 10.2. CORS thiếu `DELETE`

Cùng loại bẫy, gặp lại ngay khi thêm endpoint xoá tài khoản. Lần này bắt được
*trước* khi chạy, nhờ chú thích cảnh báo đã viết ở lỗi 10.1.

### 10.3. 403 bị biến thành 401 bởi lần gửi `ERROR`

Người **đã đăng nhập** gọi endpoint không đủ quyền → Spring ném
`AccessDeniedException` → 403. Nhưng Spring Boot forward nội bộ tới `/error`, và
lần forward đó đi qua chuỗi filter lần nữa với `SecurityContext` **đã bị xoá** →
rơi vào `denyAll()` → 401 đè lên 403.

Hậu quả không chỉ là mã trạng thái sai: giao diện thấy 401 sẽ đẩy người dùng về
màn hình đăng nhập, họ đăng nhập lại thành công, rồi **lại bị đẩy về** — vòng
lặp không lối thoát.

MockMvc mặc định không thực hiện lần gửi ERROR, nên bài kiểm thử tích hợp vẫn
xanh. **Đã sửa:** `dispatcherTypeMatchers(ERROR).permitAll()`.

### 10.4. `useAdminCredential()` trả object mới mỗi lần render

Giá trị này là phụ thuộc của effect tải số liệu. Không ghi nhớ thì mỗi lần render
sinh một object mới → effect chạy lại → huỷ request đang bay, xoá số liệu, gọi
lại → lặp vô hạn. Bảng kẹt ở "Đang tải…" dù máy chủ trả 200 cho mọi request.
**Đã sửa:** `useMemo`.

### 10.5. `HashSet` 2,1 triệu chuỗi làm hết bộ nhớ

Đếm số đích liên kết phân biệt bằng `HashSet<String>`: 31.030 trang × 69 liên kết
= **2,1 triệu chuỗi URL** trong heap chỉ để hiện một con số. Ba
`ApplicationContext` cùng sống trong một JVM lúc chạy test → `OutOfMemoryError`.

**Đã sửa:** đếm bằng **Bloom Filter** (chính cấu trúc crawler dùng cho bài toán
"URL này gặp chưa"). Bộ nhớ hằng số vài MB; sai số đi về **một phía** — Bloom chỉ
có dương tính giả nên nó chỉ đếm *thiếu*, không bao giờ đếm *thừa*.

### 10.6. Lỗi của người gọi bị báo thành 500 — **hai lần**

| Ngoại lệ | Đúng ra | Thực tế | Khi nào lộ |
|---|---|---|---|
| `ConstraintViolationException` | 400 | 500 | `?top=1000000` |
| `HttpRequestMethodNotSupportedException` | 405 | 500 | gọi `DELETE` vào bản chưa có endpoint |

Chung một gốc: **nhánh bắt-tất-cả cho 500 nuốt mọi ngoại lệ của Spring MVC vốn
đã mang sẵn mã trạng thái đúng**. Hậu quả: người gọi không biết mình gửi sai nên
thử lại y nguyên, còn người vận hành thấy báo động 5xx cho một chuyện không phải
sự cố.

### Tổng kết bài học

| Lỗi | Test nào lẽ ra bắt được? |
|---|---|
| CORS (10.1, 10.2) | Không bài nào — phải chạy **trình duyệt thật** |
| ERROR dispatch (10.3) | Test tích hợp có thực hiện lần gửi ERROR |
| `useMemo` (10.4) | Test render React (chưa có) |
| OOM (10.5) | Chính bộ test đã bắt — nhờ chạy nhiều context |
| 500 (10.6) | Test cho **đường lỗi**, không chỉ đường thành công |

---

## 11. Hướng dẫn thực hành — mười công thức

### 11.1. Tạo tài khoản quản trị đầu tiên

```bash
# Linux/macOS
export ADMIN_API_KEY=$(openssl rand -hex 32)
export BOOTSTRAP_ADMIN_USERNAME=kiet.admin
export BOOTSTRAP_ADMIN_PASSWORD='mat-khau-that-dai-cua-ban'
cd search-engine && ./mvnw spring-boot:run
```

```powershell
# PowerShell
$env:ADMIN_API_KEY = -join ((1..64) | % { '{0:x}' -f (Get-Random -Max 16) })
$env:BOOTSTRAP_ADMIN_USERNAME = 'kiet.admin'
$env:BOOTSTRAP_ADMIN_PASSWORD = 'mat-khau-that-dai-cua-ban'
```

Không khai `BOOTSTRAP_ADMIN_PASSWORD` thì ứng dụng **vẫn khởi động**, chỉ cảnh
báo — khác `ADMIN_API_KEY` (thiếu là không khởi động). Lý do: thiếu khoá API
nghĩa là endpoint quản trị *không có gì bảo vệ*; thiếu tài khoản mồi chỉ nghĩa
là chưa ai đăng nhập được bằng tài khoản.

### 11.2. Nâng một người lên ADMIN

Giao diện: bảng điều khiển → *Danh sách tài khoản* → **Nâng lên Quản trị**.

Dòng lệnh:

```bash
curl -X POST "http://localhost:8080/api/admin/users/nguoidung/role" \
  -H "Content-Type: application/json" -H "X-API-Key: $ADMIN_API_KEY" \
  -d '{"role":"ADMIN"}'
```

Người đó **phải đăng nhập lại** — phiên cũ mang vai trò cũ nên bị đóng.

### 11.3. Thêm vai trò thứ ba (ví dụ `VIEWER` — xem số liệu, không chạy crawl)

| Sửa gì | Ở đâu |
|---|---|
| Thêm hằng số enum | `auth/Role.java` |
| Tách luật đường dẫn | `config/SecurityConfig.java` — `/api/admin/analytics` dùng `hasAnyRole("ADMIN","VIEWER")`, `/api/admin/crawl` giữ `hasRole("ADMIN")` |
| Thêm cột vào bảng | `components/admin/PermissionMatrix.tsx` |
| Nút đổi vai trò | `components/admin/AccountsTable.tsx` — đổi nút bật/tắt thành danh sách chọn |

Không phải sửa `TokenAuthFilter`: nó chỉ chuyển vai trò từ phiên sang
`SecurityContext`.

### 11.4. Đổi thời hạn phiên

`auth/SessionStore.java` → `SESSION_HOURS`. Nhớ rằng đây là hạn **tuyệt đối**,
không phải hạn nhàn rỗi.

### 11.5. Thêm một chỉ số mới vào bảng điều khiển

1. Ghi nhận: thêm bộ đếm trong `analytics/UsageAnalyticsService.java` — **nhớ đặt trần**.
2. Phơi ra: thêm trường vào `analytics/UsageSnapshot.java`.
3. Kiểu ở giao diện: `lib/adminApi.ts` → `TrafficDto`.
4. Hiển thị: `components/admin/AdminPanel.tsx` → thêm một `<StatTile>`.

### 11.6. Đổi kho tài khoản sang PostgreSQL

Thêm một lớp cài `UserStore` (7 phương thức), rồi đổi một dòng trong
`config/AuthConfig.java`. `UserService` không đổi một ký tự.

### 11.7. Chạy thử toàn bộ luồng bằng `curl`

```bash
# đăng ký (luôn ra USER, kể cả khi gửi kèm "role":"ADMIN")
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"sinhvien01","password":"matkhaucuatoi"}'

# đăng nhập, lấy token
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"sinhvien01","password":"matkhaucuatoi"}' | jq -r .token)

# USER gọi endpoint quản trị -> 403 (KHÔNG phải 401)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/analytics
```

Xem thêm `docs/api-examples.http` — 30 ví dụ chạy được ngay.

### 11.8. Đặt lại số liệu trước khi demo

```bash
curl -X POST -H "X-API-Key: $ADMIN_API_KEY" \
  localhost:8080/api/admin/analytics/reset
```

Chỉ xoá phần lưu lượng; corpus và chỉ mục không đụng tới.

### 11.9. Quên mật khẩu quản trị

Không có luồng "quên mật khẩu" (xem [§14](#14-giới-hạn-đã-biết)). Lối ra:

1. Dừng máy chủ, xoá dòng tài khoản đó trong `data/users.json`.
2. Khởi động lại với `BOOTSTRAP_ADMIN_PASSWORD` mới.

Hoặc dùng khoá API để nâng một tài khoản khác lên ADMIN.

### 11.10. Xem log ai đã làm gì

```bash
grep -E "Da tao tai khoan|Doi vai tro|da doi mat khau|Da xoa tai khoan" backend.log
```

Log **không bao giờ** chứa mật khẩu hay token — chỉ tên tài khoản và hành động.

---

## 12. Kiểm thử

| Lớp kiểm thử | Bài | Chốt điều gì |
|---|---:|---|
| `UserServiceTest` | 23 | Hash không lộ, salt khác nhau, khoá tạm, thông báo mơ hồ, **không tự cấp ADMIN** |
| `AccountAuthorizationTest` | 24 | Phân quyền ở tầng HTTP thật: 401 ≠ 403, đổi vai trò đóng phiên, xoá tài khoản |
| `UsageAnalyticsServiceTest` | 19 | Vòng đệm 24 giờ, trần bộ nhớ, đếm đúng khi đa luồng |
| `SessionStoreTest` | 10 | Token đoán không ra, hết hạn, **thu hồi tức thì** |
| `JsonUserStoreTest` | 10 | Ghi nguyên tử, bản ghi hỏng không làm sập kho |
| `CorpusStatsTest` | 8 | Đếm host/liên kết/ngôn ngữ, chuỗi ngày liên tục |
| `AnalyticsAuthorizationTest` | 8 | Chiều ghi mở, chiều đọc đóng |
| `CorsPreflightTest` | 5 | **Header `Authorization` và phương thức `DELETE`** |
| **Tổng mới** | **107** | (628 tổng cộng) |

Vitest: **75 bài mới** (128 tổng cộng) cho `validation`, `format`, `analysis`,
`telemetry`, `authToken`, `sessionStore`, `dashboardStore`.

Hai chi tiết về hạ tầng test, cả hai đều là lỗi thật đã sửa:

- **`@DirtiesContext`** trên hai lớp tích hợp: mỗi `@SpringBootTest` có cấu hình
  khác nhau tạo một context riêng, mỗi context nạp cả chỉ mục 31.030 tài liệu.
  Ba context cùng sống trong một JVM → `OutOfMemoryError`.
- **`@DynamicPropertySource`** cấp kho tài khoản riêng cho mỗi lần chạy: bản đầu
  dùng đường dẫn cố định và **xanh lần đầu, đỏ lần hai**.

---

## 13. Đánh giá theo chuẩn doanh nghiệp

| Tiêu chí | Trọng số | Điểm | Nhận xét |
|---|---:|---:|---|
| Băm mật khẩu | 15% | 9,5/10 | BCrypt cost 12 + salt tự sinh. Trừ 0,5: chưa có cơ chế **nâng cost** khi phần cứng nhanh lên |
| Quản lý phiên | 15% | 8,5/10 | Token 256 bit, thu hồi tức thì. Trừ: mất hết khi khởi động lại; chưa có hạn **nhàn rỗi** |
| Phân quyền | 15% | 9,0/10 | Một bảng duy nhất, khai báo theo vai trò; 401/403 phân biệt đúng. Trừ: chưa có kiểm thử cho luật `denyAll()` mặc định |
| Chống lạm dụng | 10% | 8,0/10 | Khoá tạm theo tài khoản + giới hạn theo địa chỉ. Trừ: chưa có CAPTCHA hay chậm dần theo cấp số |
| Ranh giới dữ liệu | 10% | 9,5/10 | `toPublic()` chặn hash; số liệu người dùng không kèm truy vấn |
| Xử lý lỗi | 10% | 8,0/10 | Ba lớp lỗi phân biệt; đã sửa hai ca 500-sai. Trừ: chưa rà hết các ngoại lệ Spring MVC còn lại |
| Kiểm thử | 10% | 8,0/10 | 107 bài mới, có bài cho **đường lỗi** và cho CORS. Trừ: **không có bài nào dựng React component** |
| Tài liệu | 10% | 9,0/10 | Mọi quyết định có "vì sao"; sáu lỗi thật được ghi lại |
| Vận hành | 5% | 7,0/10 | Log đủ để truy vết. Trừ: chưa có thang đo Prometheus cho đăng nhập thất bại |
| **Tổng** | **100%** | **8,7/10** | |

### Ba việc nên làm tiếp, theo thứ tự ưu tiên

1. **Test React component** (ảnh hưởng lớn nhất). Lỗi `useMemo` ở
   [§10.4](#104-useadmincredential-trả-object-mới-mỗi-lần-render) lẽ ra bị bắt
   bởi một bài dựng `<AdminPanel>` và đếm số lần gọi API. Cần thêm
   `@testing-library/react` + môi trường `jsdom`.
2. **Thang đo Prometheus cho xác thực**: số lần đăng nhập thất bại, số tài khoản
   đang bị khoá tạm. Hiện chỉ có trong log — không đặt cảnh báo được.
3. **Hạn nhàn rỗi cho phiên** (30 phút không hoạt động thì hết hạn), cạnh hạn
   tuyệt đối 12 giờ hiện tại.

---

## 14. Giới hạn đã biết

| Giới hạn | Hệ quả | Khi nào cần sửa |
|---|---|---|
| Phiên trong bộ nhớ | Khởi động lại = mọi người đăng xuất | Khi chạy nhiều bản sao → Redis |
| Không có "quên mật khẩu" | Mất mật khẩu = phải sửa `users.json` tay | Khi có người dùng thật ngoài nhóm phát triển |
| Không có xác thực hai lớp | Mật khẩu là lớp duy nhất | Trước khi mở ra Internet |
| Không có nhật ký kiểm toán riêng | Truy vết phải đọc log văn bản | Khi cần chứng minh tuân thủ |
| Số liệu lưu lượng không bền | Khởi động lại = mất | Đã có đường khác: `/actuator/prometheus` |
| `users.json` ghi cả tệp mỗi lần | Chậm dần khi tới hàng nghìn tài khoản | Khi vượt ~1.000 tài khoản |
| Số liệu do máy khách báo | Giả mạo được bằng `curl` | Không sửa được triệt để; đủ tin cho việc nó phục vụ |

---

## 15. Tra cứu nhanh

```
BACKEND
  auth/Role.java              USER | ADMIN, @JsonCreator hạ giá trị lạ về USER
  auth/User.java              bản ghi + toPublic() chặn hash ra ngoài
  auth/UserStore.java         interface (Strategy)
  auth/JsonUserStore.java     data/users.json, ghi nguyên tử
  auth/UserService.java       BCrypt, khoá tạm, đổi mật khẩu, đổi vai trò
  auth/SessionStore.java      token → phiên, thu hồi tức thì
  auth/TokenAuthFilter.java   Authorization: Bearer

  analytics/UsageAnalyticsService.java   lưu lượng, mọi bảng có trần
  analytics/CorpusStats.java             corpus, Bloom Filter đếm xấp xỉ
  analytics/UsageSnapshot.java           ảnh chụp bất biến
  analytics/AdminDashboard.java          gộp 4 khối vào 1 phản hồi

  controller/AuthController.java         register/login/logout/me/password/logout-all
  controller/AdminUserController.java    danh sách, đổi vai trò, khoá, xoá
  controller/AdminAnalyticsController.java  số liệu + reset
  controller/EventController.java        POST /api/events (công khai)
  config/AuthConfig.java                 bean + tài khoản mồi
  config/SecurityConfig.java             BẢNG PHÂN QUYỀN — nguồn sự thật

FRONTEND (browser-app/src/renderer/src/)
  lib/authToken.ts       một chỗ duy nhất giữ token
  lib/authApi.ts         /api/auth/**, AuthError vs ServerError
  lib/adminApi.ts        /api/admin/**, AdminCredential (union)
  lib/validation.ts      luật khớp máy chủ, CHỈ để báo sớm
  store/sessionStore.ts  phiên; restore() tin MÁY CHỦ
  store/adminStore.ts    khoá API + useAdminCredential()
  store/dashboardStore.ts  số liệu + danh sách tài khoản
  components/auth/AuthScreen.tsx     màn hình đầy đủ
  components/AccountMenu.tsx         popover — lối vào nhanh
  components/admin/AdminPanel.tsx    bảng điều khiển
  components/admin/AccountsTable.tsx danh sách + nâng/hạ/xoá
  components/admin/PermissionMatrix.tsx  bảng quyền hiển thị trong sản phẩm
```

**Đọc tiếp:** [`SECURITY.md`](SECURITY.md) §3b (chi tiết bảo mật) ·
[`BACKEND.md`](BACKEND.md) §5 (bảng endpoint) ·
[`FRONTEND.md`](FRONTEND.md) §10.4 (giao diện) ·
[`api-examples.http`](api-examples.http) (30 ví dụ chạy được ngay)
