# football-service

Microservice viết bằng Go, phục vụ dữ liệu bóng đá cho bảng bên **Thể thao**
trong `browser-app`. Đây là bản port của `FootballTracker/backend`
(Node + Express + Prisma + MySQL) sang Go + Postgres.

```
browser-app ──HTTP──▶ football-service ──HTTP──▶ API-Football
 (bảng bên)             :8090   │                (100 lượt/ngày)
                                │
                                ▼
                        Postgres, schema `football`
                        api_cache · api_call_log
```

---

## Vì sao là một service riêng

Nó có một ràng buộc mà phần còn lại của hệ thống không có: **hạn mức 100 lượt
gọi ra ngoài mỗi ngày**. Ràng buộc đó quyết định gần như mọi thiết kế bên trong
— đệm bao lâu, hết hạn mức thì làm gì, khoá đệm chuẩn hoá ra sao. Nhét nó vào
backend Java, vốn đang tối ưu cho việc hoàn toàn khác (chỉ mục nghịch đảo, xếp
hạng), sẽ khiến cả hai bên khó hiểu hơn.

Nó cũng **không nằm trong profile mặc định**: không phải ai chạy repo này cũng
quan tâm tới bóng đá.

---

## Chạy

```bash
# Trong docker-compose, cùng cụm với backend chính
docker compose --profile football up -d --build

curl http://localhost:8090/api/v1/health
curl "http://localhost:8090/api/v1/fixtures?date=2026-08-13"
```

Không có `FOOTBALL_API_KEY` thì service **vẫn chạy**, bằng dữ liệu mẫu sinh
theo giờ hiện tại. Dữ liệu mẫu phủ **mọi** endpoint — lịch ngày, lịch mùa của
giải và của đội, danh sách đội, tìm và xem hồ sơ cầu thủ — chứ không riêng lịch
thi đấu, vì một tab trống ở chế độ mẫu trông y hệt một tab hỏng. Muốn dữ liệu
thật: lấy khoá miễn phí ở <https://www.api-football.com/> rồi đặt vào `.env` ở
thư mục gốc.

```bash
# Kiểm thử (máy không cần cài Go)
docker run --rm -v "$PWD:/src" -w /src golang:1.24-alpine sh -c "go vet ./... && go test ./..."
```

---

## API

Mọi endpoint đều **chỉ đọc**, không cần xác thực — chúng chỉ trả về dữ liệu
bóng đá công khai, và không mang danh tính người dùng nào.

| Endpoint | Mô tả |
|---|---|
| `GET /api/v1/health` | Còn sống không; kèm cờ `sampleOnly` |
| `GET /api/v1/status` | `used` / `budget` / `remaining` của hạn mức ngày |
| `GET /api/v1/leagues?country=&search=` | Danh sách giải đấu |
| `GET /api/v1/leagues/{id}/fixtures?season=` | Lịch **cả mùa** của một giải |
| `GET /api/v1/fixtures?date=&league=&season=` | Lịch thi đấu một ngày. `date` mặc định là hôm nay (UTC) |
| `GET /api/v1/teams?search=&league=&season=` | Tìm đội. Bắt buộc có `search` **hoặc** `league` |
| `GET /api/v1/teams/{id}/fixtures?season=&league=` | Lịch cả mùa của một đội |
| `GET /api/v1/players?search=` | Tìm cầu thủ. `search` tối thiểu 3 ký tự |
| `GET /api/v1/players/{id}?season=` | Hồ sơ + thống kê mùa |
| `GET /metrics` | Ba chỉ số Prometheus |

Mọi phản hồi dữ liệu dùng chung một lớp vỏ:

```json
{
  "data": [ ... ],
  "meta": { "cachedAt": "2026-08-13T15:27:16Z", "source": "cache", "stale": false }
}
```

`meta.source` là trường quan trọng nhất, và giao diện **phải** hiện nó ra:

| `source` | Nghĩa |
|---|---|
| `live` | Vừa hỏi nhà cung cấp xong |
| `cache` | Lấy từ đệm, còn hạn |
| `stale` | Đệm đã quá hạn nhưng không làm mới được (hết hạn mức, hoặc API lỗi) |
| `sample` | Lịch mẫu, vì chưa cấu hình khoá API |

Hai giá trị cuối là lý do trường này tồn tại. Một bảng tỉ số không nói rõ mình
đang hiển thị dữ liệu bịa hoặc dữ liệu cũ là một bảng tỉ số nói dối.

---

## Lấy khoá API

Hai đường, chọn một. Cùng một dữ liệu, cùng một hình dạng phản hồi — khác đúng
**cách xưng danh**, và service tự suy ra kiểu xưng danh từ `FOOTBALL_API_BASE_URL`
nên không phải khai báo thêm gì.

| | api-sports.io | RapidAPI |
|---|---|---|
| Đăng ký | <https://www.api-football.com/> | <https://rapidapi.com/api-sports/api/api-football> → **Subscribe to Test** → gói **Basic $0** |
| Header | `x-apisports-key` | `x-rapidapi-key` + `x-rapidapi-host` |
| `FOOTBALL_API_BASE_URL` | để mặc định | `https://api-football-v1.p.rapidapi.com/v3` |

Dán khoá vào `FOOTBALL_API_KEY` trong `.env` ở thư mục gốc rồi:

```bash
docker compose --profile football up -d football-service
curl http://localhost:8090/api/v1/status     # sampleOnly phải là false
```

**Bẫy của đường RapidAPI:** khoá RapidAPI là khoá **chung cho cả tài khoản**,
nhưng từng API phải đăng ký riêng. Chưa bấm Subscribe thì mọi lượt gọi trả về
`403 You are not subscribed to this API` — trong khi khoá hoàn toàn đúng, và
thông báo ấy đẩy người đọc đi kiểm tra sai chỗ.

---

## Hạn mức, và phép chia quyết định mọi thứ

Gói miễn phí của API-Football cho **100 lượt/ngày**. Service giữ lại 5 lượt đệm
(`FOOTBALL_DAILY_BUDGET=95`), vì hạn mức đếm ở phía họ còn ta đếm ở phía mình
và hai bộ đếm không bao giờ khớp tuyệt đối.

Từ đó ra thời gian sống của lịch thi đấu trong ngày:

```
24 giờ ÷ 95 lượt = 15,2 phút  →  TTL = 15 phút
```

Đặt ngắn hơn thì một bảng bên mở suốt ngày tự đốt hết hạn mức trước giờ chiều,
rồi mọi người dùng khác chỉ còn dữ liệu cũ.

**Nói thẳng: gói miễn phí không đủ cho tỉ số trực tiếp thật sự.** Cách trung
thực nhất là làm mới mỗi 15 phút và ghi rõ `cachedAt`, để giao diện nói được
"cập nhật lúc 20:15" thay vì giả vờ là thời gian thực.

Các mức TTL khác:

| Loại dữ liệu | TTL | Vì sao |
|---|---|---|
| Lịch thi đấu hôm nay | 15 phút | Tỉ số đổi liên tục |
| Lịch của ngày đã qua | 7 ngày | Kết quả đã cố định |
| Lịch mùa đang diễn ra | 1 ngày | Của giải hoặc của đội |
| Lịch mùa đã khép lại | 7 ngày | Không đổi nữa |
| Danh sách giải, hồ sơ đội | 7 ngày | Gần như bất động |

---

## Khác gì bản Node gốc

| | `FootballTracker/backend` | `football-service` |
|---|---|---|
| Ngôn ngữ | TypeScript + Express | Go + `net/http` |
| CSDL | MySQL riêng, qua Prisma | Postgres **dùng chung**, schema `football`, SQL thuần |
| Tài khoản | Bảng `users` + argon2 + JWT riêng | Không có — repo đã có hệ tài khoản của nó |
| Hết hạn mức | Ném lỗi → HTTP 500 | Trả đệm quá hạn, hoặc dữ liệu mẫu |
| Thiếu khoá API | Ném lỗi ở mọi request | Lịch mẫu sinh theo giờ hiện tại |
| Phụ thuộc | 9 gói runtime | 1 (`pgx`) |
| Ảnh Docker | — | distroless, non-root, ~12 MB |

Bốn thay đổi đáng nói nhất:

1. **Không dựng hệ tài khoản thứ hai.** Bản gốc mang theo `users`,
   `refresh_tokens`, argon2 và JWT. Repo này đã có `/api/auth/*` với token 12
   giờ và bảng phân quyền trong `SecurityConfig`; thêm một hệ nữa là hai bảng
   người dùng và hai chỗ để rò rỉ. Danh sách đội yêu thích vì vậy nằm ở
   `localStorage` của trình duyệt — xem `store/footballStore.ts`.

2. **Hết hạn mức không còn là lỗi 500.** Một bảng tỉ số hiện "cập nhật lúc
   19:40" hữu ích hơn hẳn một bảng trống báo lỗi máy chủ, nhất là khi dữ liệu
   cũ vẫn nằm sẵn trong CSDL.

3. **Ghi sổ lượt gọi ngay khi có mã trạng thái**, kể cả 4xx/5xx. Hạn mức bên họ
   tính theo request nhận được, không theo request thành công; bản gốc ghi sổ
   *trước* khi gọi, nên một lỗi mạng (chưa tới nơi) vẫn bị trừ oan.

4. **Đọc trường `errors` trong thân phản hồi.** API-Football trả HTTP 200 kèm
   lỗi nằm trong body. Bản gốc chỉ xét `response.ok`, nên khoá sai hoặc hết hạn
   mức hiện ra thành "hôm nay không có trận nào" — một câu trả lời hợp lệ, chỉ
   có điều sai.

---

## Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `FOOTBALL_PORT` | `8090` | Tránh 8080 của backend Java |
| `FOOTBALL_DB_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_NAME` | — / `5432` / `vnsearch` / — / `vnsearch` | Service tự ghép chuỗi kết nối |
| `FOOTBALL_DATABASE_URL` | — | Đặt thẳng cả URL; thắng các biến rời ở trên |
| `FOOTBALL_DB_SSLMODE` | `disable` | |
| `FOOTBALL_API_KEY` | *(rỗng)* | Rỗng ⇒ chạy bằng dữ liệu mẫu |
| `FOOTBALL_API_BASE_URL` | `https://v3.football.api-sports.io` | Đổi sang `https://api-football-v1.p.rapidapi.com/v3` nếu lấy khoá qua RapidAPI |
| `FOOTBALL_DAILY_BUDGET` | `95` | |
| `FOOTBALL_CORS_ORIGIN` | `*` | Mọi endpoint chỉ đọc, không có phiên để mượn |

**Truyền từng phần thay vì một URL ghép sẵn** — đây là một lỗi đã gặp thật ở
lần chạy đầu. Mật khẩu sinh ngẫu nhiên rất hay chứa `@`, `:`, `/`, `#`, `?`;
một dấu `@` cắt URL sai chỗ và Postgres trả về `password authentication
failed`, tức là trỏ người đọc đi tìm sai hướng hoàn toàn — mật khẩu **đúng**,
chỉ có chuỗi kết nối là hỏng. Xem `buildDatabaseURL` trong `internal/config`.

---

## Bố cục

```
cmd/server/          Khởi động, tắt êm, chế độ --health-check
internal/config/     Đọc biến môi trường, dựng chuỗi kết nối
internal/football/   Kiểu dữ liệu TRẢ RA ngoài (tách khỏi hình dạng của nhà cung cấp)
internal/apifootball/ Client HTTP + bộ chuẩn hoá
internal/service/    Chính sách: đệm, hạn mức, thứ tự lùi dữ liệu
internal/store/      Postgres: api_cache, api_call_log, tự migrate
internal/sample/     Lịch thi đấu mẫu sinh theo giờ hiện tại
```

Ba tầng, một chiều phụ thuộc: `httpapi → service → {apifootball, store, sample}`.
`apifootball` chỉ biết gọi HTTP, `store` chỉ biết đọc ghi bảng — mọi quyết định
nằm ở `service`.
