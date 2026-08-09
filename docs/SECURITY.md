# Bảo mật — chống lại cái gì, bằng cách nào

> **Tài liệu này trả lời:** hệ thống này có những bề mặt tấn công nào, mỗi bề
> mặt được chặn ở đâu, và **những gì vẫn còn hở**.
>
> Mục cuối cùng là mục quan trọng nhất. Một tài liệu bảo mật chỉ liệt kê thứ
> mình làm tốt là một tài liệu marketing.

---

## Mục lục

1. [Bản đồ tư duy các bề mặt tấn công](#1-bản-đồ-tư-duy-các-bề-mặt-tấn-công)
2. [SSRF — bề mặt nguy hiểm nhất](#2-ssrf--bề-mặt-nguy-hiểm-nhất)
3. [Xác thực API quản trị](#3-xác-thực-api-quản-trị)
4. [Giới hạn tần suất](#4-giới-hạn-tần-suất)
5. [Phân quyền theo đường dẫn](#5-phân-quyền-theo-đường-dẫn)
6. [CORS](#6-cors)
7. [Rò rỉ thông tin qua thông báo lỗi](#7-rò-rỉ-thông-tin-qua-thông-báo-lỗi)
8. [XSS trong đoạn trích](#8-xss-trong-đoạn-trích)
9. [Bảo mật Electron](#9-bảo-mật-electron)
10. [Cứng hoá container và cụm](#10-cứng-hoá-container-và-cụm)
11. [Quản lý bí mật](#11-quản-lý-bí-mật)
12. [Chuỗi cung ứng](#12-chuỗi-cung-ứng)
13. [NHỮNG GÌ CÒN HỞ](#13-những-gì-còn-hở)

---

## 1. Bản đồ tư duy các bề mặt tấn công

```mermaid
mindmap
  root((Bề mặt<br/>tấn công))
    Crawler đi RA
      SSRF qua seed URL
      SSRF qua redirect
      SSRF qua liên kết moi được
      SSRF qua thẻ img
    HTTP đi VÀO
      API quản trị không xác thực
      Vét cạn khoá
      Làm cạn tài nguyên
      CORS quá rộng
      Rò rỉ qua thông báo lỗi
    Nội dung không tin được
      XSS trong đoạn trích
      HTML từ trang đã crawl
    Ứng dụng để bàn
      Electron sandbox
      scheme file:// data://
      Cầu nối IPC
    Hạ tầng
      Container chạy root
      Bí mật trong Git
      CVE thư viện
      CVE ảnh nền
```

```
   ┌─────────────── HAI HƯỚNG, HAI LOẠI PHÒNG THỦ ───────────────┐
   │                                                              │
   │   ĐI RA (crawler)              ĐI VÀO (HTTP)                │
   │   ─────────────────            ──────────────────           │
   │   Ta CHỦ ĐỘNG gửi request      Người khác gửi request tới   │
   │   tới địa chỉ do NGƯỜI KHÁC    ta                           │
   │   chỉ định                                                   │
   │                                                              │
   │   → SSRF: danh sách CHO PHÉP   → Xác thực + giới hạn tần suất│
   │     + kiểm tra SAU phân giải     + phân quyền + không rò rỉ  │
   │       DNS, ở TỪNG chặng                                      │
   └──────────────────────────────────────────────────────────────┘
```

---

## 2. SSRF — bề mặt nguy hiểm nhất

**SSRF (Server-Side Request Forgery)** là bề mặt lớn nhất của dự án này, vì bản
chất công việc của crawler là *gửi request tới địa chỉ do người khác chỉ định*.

### 2.1. Bốn đường vào, và cả bốn đều phải chặn

```
   ①  Seed URL từ POST /api/admin/crawl
          → SeedUrlValidator
   ②  Chuyển hướng HTTP 3xx từ một trang hợp lệ
          → HtmlDownloader, kiểm TỪNG chặng
   ③  Liên kết do LinkExtractor moi ra từ trang đã tải
          → HtmlDownloader, cùng phép kiểm
   ④  Thẻ <img src="..."> trong trang đã tải
          → ImageDownloadService KHÔNG tự mở kết nối (mặc định)
```

Đường ② là đường tinh vi nhất và là lỗ hổng thật đã được vá:

```
   seed: https://trang-cua-toi.com/     ✅ IP công cộng, qua kiểm tra
              │  HTTP 302
              ▼
        http://169.254.169.254/latest/meta-data/iam/
              ↑ Jsoup TỰ đi theo — nếu bật followRedirects thì
                KHÔNG ai kiểm chặng này
```

`169.254.169.254` là endpoint metadata của AWS/GCP/Azure. Đọc được nó là đọc
được **thông tin đăng nhập IAM của máy chủ**.

Cách vá: **tắt `followRedirects` của Jsoup và tự đi từng chặng**, để chạy đúng
một phép kiểm tra **trước mỗi lần mở kết nối**. Chặng thứ mười được soi kỹ như
chặng đầu. Trần 5 chặng chặn vòng lặp chuyển hướng.

### 2.2. Phép kiểm tra: danh sách CHO PHÉP + kiểm SAU phân giải DNS

```java
// SeedUrlValidator.isBlockedAddress — dùng lại bởi HtmlDownloader
address.isLoopbackAddress()      // 127.0.0.0/8, ::1
  || address.isLinkLocalAddress()  // 169.254.0.0/16 metadata, fe80::/10
  || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
  || address.isAnyLocalAddress()   // 0.0.0.0, ::
  || address.isMulticastAddress()
  || isUniqueLocalIpv6(address)    // fc00::/7   — không có sẵn phép kiểm
  || isCarrierGradeNat(address);   // 100.64.0.0/10 (RFC 6598)
```

**Bốn quyết định đáng giải thích:**

| Quyết định | Vì sao |
|---|---|
| Kiểm **sau** khi phân giải DNS, không kiểm chuỗi | `http://xyz.com` có thể trỏ tới `127.0.0.1`. So sánh chuỗi không thấy gì cả |
| Dùng `InetAddress.isXxx()` sẵn có | Chúng xử lý đúng cả IPv4 lẫn IPv6, kể cả dạng IPv4 trong vỏ IPv6 (`::ffff:127.0.0.1`) — biến thể mà phép so sánh chuỗi tự viết gần như chắc chắn bỏ sót |
| Thêm `fc00::/7` và `100.64.0.0/10` | JDK không coi hai dải này là nội bộ, nhưng trong một mạng đám mây chúng vẫn trỏ tới hạ tầng không công khai |
| Chỉ chấp nhận `http`/`https` | Chặn `file:`, `gopher:`, `jar:` — những scheme mà một redirect có thể trả về |

### 2.3. Mọi thông báo từ chối đều GIỐNG HỆT nhau

Đây là chi tiết dễ bỏ qua nhất, và nó quan trọng:

```java
private static final String REJECTED = "..."; // MỘT chuỗi duy nhất

// host không phân giải được  → REJECTED
// host trỏ vào mạng nội bộ   → REJECTED
// host trong danh sách chặn  → REJECTED
```

Nếu ba ca này trả về ba câu khác nhau, kẻ gọi phân biệt được *"host này không
tồn tại"* với *"host này tồn tại và ở trong mạng nội bộ"* — tức **một phép quét
mạng nội bộ, dùng đúng chính lớp chặn SSRF làm công cụ**.

### 2.4. Vì sao ảnh mặc định KHÔNG được tải

`app.crawler.images.download=false`. `ImageDownloadService` mặc định **chỉ ghi
siêu dữ liệu**, không mở kết nối nào. Ba lý do, và lý do thứ hai là bảo mật: một
thẻ `<img src="http://169.254.169.254/...">` trong trang đã crawl là một đường
SSRF nữa, và nó **không đi qua** `AdminController`.

### 2.5. Rủi ro còn lại: DNS rebinding

Phép kiểm tra phân giải tên miền qua `DnsResolver`, còn Jsoup **tự phân giải lần
nữa** lúc mở socket. Về lý thuyết bản ghi DNS có thể đổi giữa hai lần đó.

Cửa sổ này hẹp — `DnsResolver` có cache nên phần lớn lần tải dùng lại đúng địa
chỉ vừa kiểm tra — nhưng **đóng hẳn** thì phải ghim IP rồi tự đặt header `Host`,
việc này phá SNI của HTTPS.

> Ghi nhận là **rủi ro còn lại**, không phải chỗ bị bỏ sót. Đây là khác biệt
> giữa một phòng thủ được hiểu và một phòng thủ được sao chép.

---

## 3. Xác thực API quản trị

`/api/admin/**` điều khiển crawler và **có thể tải URL tuỳ ý** — nếu để công
khai, đó là một lỗ hổng SSRF đầy đủ chứ không chỉ là "API không được bảo vệ".

### 3.1. Vì sao API key chứ không phải tài khoản/mật khẩu hay OAuth

Hệ thống này **không có người dùng nào cả**. Các endpoint quản trị được gọi bởi
một công cụ vận hành — một dòng `curl`, một job định kỳ — chứ không phải bởi con
người ngồi trước màn hình đăng nhập. Dựng bộ máy quản lý người dùng đầy đủ ở đây
sẽ tạo ra bảng người dùng, luồng đăng ký, mã hoá mật khẩu... toàn bộ một hệ
thống con **không ai sử dụng**.

### 3.2. So sánh chuỗi thời gian hằng số

```java
MessageDigest.isEqual(provided, expectedKey)   // ĐÚNG
provided.equals(expectedKey)                    // SAI
```

`String.equals` thoát ra **ngay tại ký tự đầu tiên khác nhau**, nên thời gian
chạy rò rỉ *độ dài tiền tố đúng* của khoá đoán. Đo đủ chênh lệch này qua nhiều
request, kẻ tấn công đoán được từng ký tự một:

```
   không gian tìm kiếm:  62^32  →  62 × 32
```

`MessageDigest.isEqual` luôn duyệt hết độ dài.

### 3.3. Thiếu khoá thì ứng dụng KHÔNG khởi động

```java
throw new IllegalStateException("Thieu app.security.admin-api-key ...");
```

Phương án còn lại — sinh một khoá ngẫu nhiên rồi in ra log — nghe thân thiện hơn
nhưng tạo ra một hệ thống **có vẻ** đang chạy bình thường trong khi không ai
biết khoá là gì, và lần triển khai sau lại sinh khoá khác.

> **Hỏng to còn hơn hỏng âm thầm.** Một hệ thống "có bảo vệ" mà bảo vệ bằng khoá
> mặc định ai cũng biết thì **nguy hiểm hơn** hệ thống không bảo vệ, vì nó tạo
> cảm giác an toàn sai.

Có thêm kiểm tra độ dài tối thiểu 16 ký tự.

---

## 4. Giới hạn tần suất

Một truy vấn không được cache có thể phải chấm điểm hàng nghìn ứng viên. Không
có giới hạn nào thì **một vòng lặp `curl` đơn giản cũng đủ làm CPU đạt trần** —
không cần lỗi nào cả, chỉ cần gọi API đúng cách nhưng quá nhanh.

### 4.1. Token bucket, không phải đếm theo cửa sổ cố định

```
   Cửa sổ cố định:  |....120....||....120....|   ← 240 req quanh ranh giới
                              ^^ biên
   Token bucket:    gáo chứa tối đa N token, đổ lại N/60 token mỗi giây
                    → tốc độ trung bình bị chặn ở MỌI thời điểm
```

Đếm theo phút lịch có lỗi **biên cửa sổ**: 120 request lúc 10:00:59 và 120
request nữa lúc 10:01:00 đều hợp lệ — tức 240 request trong một giây.

### 4.2. Ba chi tiết đúng đắn

| Chi tiết | Vì sao |
|---|---|
| Đặt **trước** chuỗi filter Spring Security (`order = Integer.MIN_VALUE`) | Một trận request không hợp lệ phải bị chặn **trước** khi tốn chi phí phân giải xác thực |
| Đăng ký qua `FilterRegistrationBean`, không phải `@Component` | Nếu không, Spring Boot gắn nó vào chuỗi filter servlet **hai lần** |
| `X-Forwarded-For` chỉ đọc khi `trust-proxy=true` | Header này do **client** gửi. Tin nó vô điều kiện = đổi header mỗi request là mỗi request thành một "địa chỉ" mới với gáo đầy — bộ giới hạn bị **vô hiệu hoàn toàn** |

### 4.3. Trần bộ nhớ là bắt buộc

`MAX_TRACKED_CLIENTS = 100_000`. Không có trần thì chính bộ giới hạn tần suất
**trở thành một lỗ rò rỉ bộ nhớ**: mỗi địa chỉ giả mạo thêm một mục vào bảng, và
bảng không bao giờ được dọn. Chạm trần thì bảng bị xoá sạch — thô, nhưng chặn
trên bộ nhớ là điều bắt buộc, còn độ chính xác của hạn mức thì không.

### 4.4. Giới hạn đã biết

- **Theo từng tiến trình.** Chạy hai bản sao thì hạn mức thực tế nhân đôi. Dùng
  chung hạn mức đòi hỏi một kho đếm ngoài (Redis).
- **Định danh bằng địa chỉ IP**, mà IP giả mạo được và nhiều người dùng có thể
  dùng chung một NAT. Đây là phép chặn **thô**, không phải phép chống lạm dụng
  tinh vi.

---

## 5. Phân quyền theo đường dẫn

```
   CÔNG KHAI                        CẦN X-API-Key
   ─────────────────────────        ─────────────────────────
   GET  /api/search                 POST /api/admin/crawl
   GET  /api/suggest                POST /api/admin/reindex
   GET  /api/health                 GET  /api/admin/stats
   GET  /api/images                 GET  /api/admin/crawl/{id}/status
   GET  /api/feed
   GET  /actuator/health/**         GET  /actuator/**  (còn lại)
   GET  /actuator/prometheus
   ─────────────────────────────────────────────────────────
   MỌI THỨ KHÁC → denyAll()
```

**`.anyRequest().denyAll()`** là dòng quan trọng nhất: mặc định là **từ chối**.
Thêm một endpoint đọc dữ liệu mà quên khai báo thì nó trả 401 — ồn ào, dễ thấy,
sửa ngay. Đây đúng là lý do `/api/images` trả 401 ở lần chạy đầu tiên.

**Vì sao CSRF được tắt có chủ ý.** Đây là API không trạng thái, xác thực bằng
**header** chứ không bằng cookie. Tấn công CSRF dựa trên việc trình duyệt *tự
động* đính kèm thông tin xác thực; header `X-API-Key` thì không bao giờ được
đính kèm tự động, nên không có gì để giả mạo.

**Vì sao `/actuator/prometheus` công khai.** Bộ thu thập số liệu không gửi được
header tuỳ ý trong cấu hình mặc định, và endpoint này chỉ phơi bày số liệu tổng
hợp. Trong một triển khai thật, nó nên bị chặn ở **tầng mạng** — ranh giới mà
ứng dụng không tự đặt được.

### Actuator: không bao giờ dùng `*`

```properties
management.endpoints.web.exposure.include=health,metrics,prometheus
```

Nhóm mặc định còn chứa `/actuator/env` (phơi bày **mọi** biến môi trường, kể cả
`ADMIN_API_KEY` và mật khẩu CSDL) và `/actuator/heapdump` (tải về **toàn bộ bộ
nhớ tiến trình**). `show-details=never` cho health để không lộ chi tiết nội bộ.

---

## 6. CORS

```java
.allowedOriginPatterns(allowedOrigins, "file://*", "null")
.allowedMethods("GET", "POST", "OPTIONS")
.allowedHeaders("Accept", "Content-Type", "X-API-Key")
.allowCredentials(false)
.maxAge(3600);
```

**Vì sao goc `"null"` phải nằm trong danh sách.** Nhìn qua thì đây là mục đáng
ngờ nhất — goc `null` cũng được gửi bởi iframe sandbox và trang `data:`. Nhưng
bỏ nó đi thì **bản đóng gói của browser-app ngừng hoạt động**: renderer lúc đó
được nạp qua `file://`, và Chromium gửi `Origin: null` cho mọi request đi ra từ
một trang `file://`.

Ba điều làm đánh đổi đó chấp nhận được:

1. **`allowCredentials(false)`** — đây mới là thứ biến một cấu hình CORS rộng
   thành một lỗ hổng. Không có nó, trình duyệt **tự động** đính kèm cookie của
   nạn nhân. Ghi tường minh dù đó là mặc định, để một lần sửa sau này phải là
   quyết định có ý thức.
2. **Chỉ hai phương thức thật sự tồn tại.** Trước đây danh sách có cả `PUT` và
   `DELETE` trong khi toàn bộ API không có lấy một endpoint nào dùng chúng.
3. **Danh sách header cụ thể thay cho `"*"`.**

> **CORS không phải lớp bảo vệ của `/api/admin/**`** — lớp đó là
> `ApiKeyAuthFilter`. CORS chỉ quyết định **trình duyệt nào đọc được phản hồi**;
> một lệnh `curl` không bị CORS ràng buộc chút nào.

---

## 7. Rò rỉ thông tin qua thông báo lỗi

**Nguyên tắc: lỗi của NGƯỜI GỌI thì nói rõ, lỗi của HỆ THỐNG thì giấu.**

Trước đây mọi ngoại lệ đều được trả nguyên văn:

```java
return errorResponse(INTERNAL_SERVER_ERROR, "Loi he thong: " + e.getMessage());
```

`e.getMessage()` của một `SQLException` chứa **chuỗi kết nối và tên bảng**; của
một `IOException` chứa **đường dẫn tuyệt đối trên máy chủ**. Đó là bản đồ miễn
phí cho người đang dò hệ thống.

Nay:

| Loại lỗi | Trả về | Vì sao |
|---|---|---|
| `MissingServletRequestParameter`, `MethodArgumentNotValid`, `IllegalArgument` | **Nói rõ nguyên nhân** | Người gọi cần biết họ gửi sai chỗ nào; thông tin đó không tiết lộ gì về nội bộ |
| Mọi ngoại lệ khác | **Mã tham chiếu 8 ký tự** + câu chung | Chi tiết đầy đủ, kể cả stack trace, vào **log** kèm đúng mã đó |

Người vận hành tra log bằng mã; người ngoài không biết thêm gì. Người dùng báo
lỗi vẫn có thứ để đọc cho bộ phận hỗ trợ — điều mà một câu *"Đã có lỗi xảy ra"*
trần không làm được.

---

## 8. XSS trong đoạn trích

Đoạn trích được sinh từ **HTML của trang đã crawl** — nội dung hoàn toàn không
tin được — rồi được bôi sáng bằng thẻ `<mark>` và trả về cho giao diện render.

`SnippetBuilder.escapeHtml` thoát các ký tự đặc biệt **trước** khi chèn thẻ bôi
sáng. Thứ tự đó là toàn bộ vấn đề: thoát sau khi chèn thì chính thẻ `<mark>` của
mình cũng bị thoát; chèn mà không thoát thì `<script>` của trang đích chạy trong
ngữ cảnh của giao diện.

---

## 9. Bảo mật Electron

Ứng dụng để bàn có một bề mặt mà backend không có: **nó chạy mã của trang web
ngay trên máy người dùng**.

### 9.1. Hai loại khung, hai mức tin cậy

```
   chromeView (vỏ giao diện)         tab views (nội dung web ngoài)
   ├─ preload: CÓ                    ├─ preload: KHÔNG
   ├─ contextIsolation: true         ├─ contextIsolation: true
   ├─ nodeIntegration: false         ├─ nodeIntegration: false
   ├─ sandbox: TRUE                  └─ (không có cầu nối IPC)
   ├─ will-navigate: CHẶN
   └─ setWindowOpenHandler: deny
```

`chromeView` là khung **duy nhất** có preload, tức khung duy nhất chạm được tới
IPC. Nội dung web ngoài sống trong các tab, và các tab **không có preload**.

### 9.2. `sandbox: true`

Trước đây là `false` — mặc định nguy hiểm nhất trong tệp đó. Tắt sandbox nghĩa
là nếu có một lỗ hổng XSS trong giao diện, mã của kẻ tấn công chạy trong một
tiến trình có **toàn quyền Node**: đọc được tệp, mở được tiến trình con.

Không có gì phải đánh đổi: preload chỉ dùng `ipcRenderer` và `contextBridge`, cả
hai đều có sẵn trong preload đã sandbox. Đã kiểm cả `@electron-toolkit/preload`
— nó chỉ đụng tới `electron` và `process.platform`/`versions`/`env`, đều được
phép.

### 9.3. Danh sách CHO PHÉP scheme

Lỗ hổng đã vá — bản trước dùng đúng một dòng:

```ts
const target = /^[a-z]+:\/\//i.test(url) ? url : `https://${url}`
```

Nó chỉ hỏi *"có scheme không"*, không hỏi *"scheme NÀO"*. Hệ quả:

```
   file:///C:/Users/<tên>/.ssh/id_rsa    → mở tệp cục bộ trong tab
   file://<máy-chủ-mạng>/ổ-chia-sẻ/      → chạm vào SMB nội bộ
```

Và một trang web bất kỳ kích hoạt được đường đó: `setWindowOpenHandler` gọi
`createTab(url)` với URL do **trang đích** chỉ định, nên `window.open('file:///…')`
là đủ.

> Đây **cùng một loại lỗi với SSRF ở backend** — tin vào một chuỗi do bên ngoài
> cung cấp — và được vá bằng cùng một cách: **danh sách CHO PHÉP**, chứ không
> phải danh sách chặn. Danh sách chặn phải đoán trước mọi scheme nguy hiểm
> (`file:`, `data:`, `blob:`, `javascript:`, `chrome:`, `devtools:`, `ms-msdt:`…)
> và sẽ luôn thiếu cái tiếp theo.

`urlPolicy.ts` được tách thành module **thuần**, không import `electron`, vì hai
lý do: nó phải kiểm thử được (22 bài Vitest canh nó trong CI), và nó phải là
**nguồn sự thật duy nhất** — thanh địa chỉ ở renderer cũng chuẩn hoá URL, nhưng
renderer **không phải** ranh giới bảo mật; mọi thứ nó gửi qua IPC đều phải bị coi
là không đáng tin.

### 9.4. Ba lớp còn lại

| Biện pháp | Chặn gì |
|---|---|
| `will-navigate` trên vỏ giao diện | Vỏ điều hướng sang trang ngoài thì trang đó **thừa hưởng cầu nối IPC** |
| `setWindowOpenHandler` → `deny` + mở thành tab | `target="_blank"` thoát ra một cửa sổ ngoài mọi ràng buộc |
| `clampZoomFactor` | `setZoomFactor(0)` từ IPC làm nội dung biến mất, **không phục hồi được** bằng giao diện |

---

## 10. Cứng hoá container và cụm

| Lớp | Biện pháp |
|---|---|
| Ảnh Docker | `useradd vnsearch` — **không chạy bằng root** |
| Namespace | Pod Security **`restricted`** — Pod quên `securityContext` bị **từ chối tạo** |
| Container | `runAsNonRoot: true`, `runAsUser: 1000`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`, `allowPrivilegeEscalation: false`, `seccompProfile: RuntimeDefault` |
| Mạng | `NetworkPolicy` cho PostgreSQL **và** Kafka |
| Bí mật | Overlay prod **cố ý xoá** `secret.yaml` của lớp nền |

`readOnlyRootFilesystem: true` chặn mọi ghi lên đĩa, nhưng JVM cần ghi tệp tạm và
ứng dụng cần ghi chỉ mục dựng sẵn — nên cấp **đúng hai** thư mục ghi được
(`/tmp`, `/app/data`) thay vì mở toàn bộ hệ thống tệp.

Chi tiết mạng và một lỗi NetworkPolicy đã suýt xảy ra:
[`INFRASTRUCTURE.md` §10](INFRASTRUCTURE.md).

---

## 11. Quản lý bí mật

```
   .env             ← .gitignore chặn (dòng 16), KHÔNG BAO GIỜ commit
   .env.example     ← commit, chỉ chứa mô tả và lệnh sinh khoá
```

| Bí mật | Nguồn |
|---|---|
| `ADMIN_API_KEY` | Biến môi trường. Trống = **không khởi động** |
| `POSTGRES_PASSWORD` | Biến môi trường |
| `GRAFANA_PASSWORD` | Biến môi trường |
| Khoá ký ảnh | **Không có** — cosign chế độ keyless, danh tính từ OIDC |
| `KUBE_CONFIG` | GitHub Environment secret, **tách riêng** staging và production |

Bí mật gắn theo môi trường, nên `KUBE_CONFIG` của staging **không dùng được** cho
production kể cả khi ai đó sửa nhầm workflow.

Sinh khoá:

```bash
openssl rand -hex 32
# PowerShell:  -join ((1..64) | % { '{0:x}' -f (Get-Random -Max 16) })
```

Trong CI, khoá giả `test-only-key-0123456789abcdef` được đặt qua
`systemPropertyVariables` của surefire — đủ 16 ký tự để qua phép kiểm độ dài.

---

## 12. Chuỗi cung ứng

```mermaid
flowchart LR
    dep["Dependabot<br/>5 hệ sinh thái"] --> pr["Pull request<br/>tự động"]
    src["Mã nguồn"] --> sb["SpotBugs<br/>từng hàm"]
    src --> cq["CodeQL<br/>luồng dữ liệu"]
    img["Ảnh Docker"] --> tr["Trivy<br/>CVE hệ thống"]
    tr --> cd["CD: CRITICAL thì CHẶN"]
    cd --> sign["cosign keyless<br/>+ SBOM + provenance"]

    style cd fill:#e8590c,color:#fff
```

**Vì sao cần cả SpotBugs lẫn CodeQL** — chúng trả lời hai câu hỏi khác nhau:

| SpotBugs | CodeQL |
|---|---|
| Xét **từng hàm** một | Lần theo **luồng dữ liệu** xuyên nhiều lớp |
| *"Hàm này so chuỗi bằng `==`"* | *"Giá trị từ `request.getParameter()` đi qua 4 lớp rồi vào `Runtime.exec()`"* |
| Nhanh, chạy trong mỗi build | Chậm hơn, chạy riêng |

Câu hỏi thứ hai mới là câu hỏi của bảo mật: một lỗ hổng injection **gần như
không bao giờ nằm gọn trong một hàm**.

CodeQL còn chạy theo **lịch hằng tuần**, không chỉ khi có commit: cơ sở dữ liệu
quy tắc được cập nhật liên tục, nên mã không đổi vẫn có thể lộ ra lỗ hổng mới
phát hiện tuần sau.

**SBOM + provenance** trả lời câu hỏi đầu tiên khi một CVE được công bố: *"ta có
dùng thư viện đó không"* — trong vài giây thay vì vài giờ.

---

## 13. NHỮNG GÌ CÒN HỞ

Mục quan trọng nhất tài liệu này. Xếp theo mức rủi ro giảm dần.

| # | Còn hở | Mức | Ghi chú |
|---|---|:---:|---|
| 1 | **Chưa bật branch protection trên `main`** | 🔴 | Bảy cổng chặn CI **chỉ có nghĩa** khi không ai đẩy thẳng lên `main` được. Đây là thiết lập ở Settings, không nằm trong mã |
| 2 | **DNS rebinding** (§2.5) | 🟠 | Cửa sổ hẹp nhờ cache DNS; đóng hẳn thì phá SNI của HTTPS |
| 3 | **Giới hạn tần suất theo từng tiến trình** | 🟠 | Ba bản sao = hạn mức thực tế gấp ba. Cần Redis để dùng chung |
| 4 | **Định danh bằng IP** | 🟠 | Giả mạo được; nhiều người dùng chung NAT bị tính chung |
| 5 | **CORS chấp nhận goc `null`** | 🟡 | Bắt buộc cho bản đóng gói `file://`. Giảm nhẹ bằng `allowCredentials(false)` |
| 6 | **`/actuator/prometheus` công khai** | 🟡 | Chỉ số liệu tổng hợp. Nên chặn ở tầng mạng — ứng dụng không tự làm được |
| 7 | **Không có audit log cho thao tác quản trị** | 🟡 | Biết có người dùng sai khoá, nhưng **không** biết ai đã chạy crawl nào lúc nào |
| 8 | **Một API key duy nhất, không xoay vòng** | 🟡 | Không thu hồi được cho một bên gọi cụ thể. Nhiều khoá có tên là bước tiếp theo |
| 9 | **Chưa có Content-Security-Policy** cho renderer | 🟡 | `sandbox` + `contextIsolation` đã chặn phần lớn, nhưng CSP là lớp sâu hơn |
| 10 | **Kafka và PostgreSQL không bật TLS** trong cụm | 🟡 | Lưu lượng nội bộ cụm; `NetworkPolicy` giới hạn ai nói chuyện được |
| 11 | **Chưa quét bí mật vô tình commit** | 🟢 | `gitleaks` hoặc `trufflehog` trong CI là việc 15 phút |
| 12 | **Chưa có kiểm thử thâm nhập** | 🟢 | Mọi kết luận ở đây đến từ đọc mã, không từ tấn công thật |

### Ba việc đáng làm trước

1. **Bật branch protection** — 5 phút, và nó khoá lại giá trị của toàn bộ CI.
2. **Thêm `gitleaks` vào CI** — 15 phút, chặn loại lỗi tốn kém nhất.
3. **Audit log cho `/api/admin/**`** — ghi ai gọi gì lúc nào, kể cả khi thành
   công. Hiện chỉ log lần **từ chối**.

---

## Đọc tiếp

| Tài liệu | Nội dung |
|---|---|
| [`BACKEND.md`](BACKEND.md) | Ba filter bảo mật nằm ở đâu trong chuỗi |
| [`INFRASTRUCTURE.md`](INFRASTRUCTURE.md) | NetworkPolicy, Pod Security, bí mật trong cụm |
| [`DEVOPS.md`](DEVOPS.md) | CodeQL, Trivy, ký cosign trong quy trình nào |
| [`FRONTEND.md`](FRONTEND.md) | Cầu nối IPC và mô hình tin cậy của Electron |
