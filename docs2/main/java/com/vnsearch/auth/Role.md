# Role — hai vai trò, và vì sao chỉ hai

**File nguồn:** `search-engine/src/main/java/com/vnsearch/auth/Role.java`
**Gói:** `com.vnsearch.auth` · **Loại:** `enum` có hành vi (`authority()`, `parse()`)
**Dùng bởi:** `TokenAuthFilter`, `SecurityConfig`, `UserService`, `AdminUserController`, `JsonUserStore` (qua Jackson)
**Đọc kèm:** [`User.md`](./User.md) · [`UserService.md`](./UserService.md) · [`TokenAuthFilter.md`](./TokenAuthFilter.md)

---

## 📌 Hiểu trong 30 giây

Một enum 2 giá trị, 30 dòng code — nhưng nó là **điểm neo duy nhất** của toàn bộ
mô hình phân quyền. Hai hàm nhỏ trong đó chặn hai lớp lỗi hoàn toàn khác nhau:

- `authority()` chặn lỗi **gõ chuỗi** `"ROLE_ADMIN"` rải rác khắp nơi.
- `parse()` chặn lỗi **dữ liệu bẩn** từ tệp JSON làm ứng dụng không khởi động được.

```mermaid
%%{init:{'theme':'base','themeVariables':{'background':'#ffffff','primaryColor':'#ffffff','primaryTextColor':'#000000','primaryBorderColor':'#000000','lineColor':'#000000','textColor':'#000000','mainBkg':'#ffffff','nodeBorder':'#000000','clusterBkg':'#ffffff','clusterBorder':'#000000','edgeLabelBackground':'#ffffff','fontFamily':'ui-monospace, SFMono-Regular, Consolas, monospace'}}}%%
flowchart LR
    J["users.json<br/>\"role\": \"ADMIN\""] -->|"Jackson @JsonCreator"| P["Role.parse(raw)"]
    B["POST /api/admin/users/{ten}/role<br/>body: \"admin\""] -->|"Jackson"| P
    P -->|"lạ / null / gõ sai"| U["USER (an toàn)"]
    P -->|"khớp"| R["USER hoặc ADMIN"]

    R --> A["role.authority()<br/>= \"ROLE_ADMIN\""]
    A --> SC["SecurityContextHolder<br/>AuthorityUtils.createAuthorityList(...)"]
    SC --> HR["SecurityConfig<br/>.hasRole(\"ADMIN\")"]
```

```
   HAI HƯỚNG SAI — CHỌN HƯỚNG NÀO?

   ┌ hạ về USER khi gặp giá trị lạ (đang dùng) ─────────────────────┐
   │ mất quyền  → người thật báo ngay: "tôi không vào được"         │
   │ THỪA quyền → KHÔNG AI PHÁT HIỆN cho tới khi có sự cố           │
   │ ⇒ fail-safe: nghiêng về phía ÍT quyền hơn                      │
   └────────────────────────────────────────────────────────────────┘
   ┌ ném ngoại lệ ────────────────────────────────────────────────┐
   │ một ký tự thừa trong users.json → ứng dụng KHÔNG KHỞI ĐỘNG    │
   │ tự khoá mình ra ngoài vì một lỗi gõ                            │
   └───────────────────────────────────────────────────────────────┘
```

---

## 1. Vì sao không làm hệ quyền chi tiết (permission-based)

Javadoc dòng 6–13 trả lời thẳng câu hỏi mà mọi người review sẽ hỏi:

> Một hệ thống quyền "đúng bài" cho phép gán từng quyền lẻ (`CRAWL_START`,
> `ANALYTICS_READ`, `USER_MANAGE`) rồi gom thành nhóm. Nó đúng khi có **nhiều
> loại người vận hành khác nhau**. Ở đây thì không: mọi endpoint quản trị đều
> được dùng bởi cùng một người.

| | RBAC 2 vai trò (đang dùng) | ABAC / permission-based |
|---|---|---|
| Số khái niệm phải hiểu | 1 (`Role`) | 3 (permission, role, mapping) |
| Cấu hình | Không có | Bảng/tệp cấu hình + UI quản lý |
| Đúng khi | Một nhóm người vận hành | Nhiều nhóm, quyền chồng lấn |
| Rủi ro | Không đủ tinh khi nghiệp vụ phình | Tầng cấu hình mà **không ai cấu hình khác đi** |

Đây là ví dụ tốt về **YAGNI có lập luận**: không phải "làm đơn giản cho nhanh"
mà là "đã cân nhắc, và điều kiện để cần cái phức tạp hơn chưa xảy ra". Javadoc
còn nói rõ *khi nào* thì mở rộng: khi thật sự có người cần vai trò `VIEWER` —
xem số liệu nhưng không được chạy crawl.

---

## 2. `authority()` — tiền tố `ROLE_` chỉ viết ở một chỗ

```java
public String authority() {
    return "ROLE_" + name();
}
```

Spring Security có một quy ước dễ gây bực bội: `hasRole("ADMIN")` **không** tìm
quyền tên `ADMIN`, nó tìm `ROLE_ADMIN`. Hai API họ hàng nhau xử lý tiền tố khác
nhau:

| API | Chuỗi truyền vào | Chuỗi thật sự đem so |
|---|---|---|
| `hasRole("ADMIN")` | `ADMIN` | `ROLE_ADMIN` (tự ghép) |
| `hasAuthority("ADMIN")` | `ADMIN` | `ADMIN` (không ghép) |
| `createAuthorityList(...)` | chuỗi nguyên văn | chuỗi nguyên văn |

```
   NẾU RẢI CHUỖI KHẮP NƠI

   TokenAuthFilter:  createAuthorityList("ADMIN")       ← quên tiền tố
   SecurityConfig:   .hasRole("ADMIN")  → cần ROLE_ADMIN
   ────────────────────────────────────────────────────────
   Kết quả: tài khoản TRÔNG NHƯ có quyền (JSON trả role=ADMIN)
            nhưng MỌI request quản trị đều 403.
            Không có exception, không có log — chỉ có một người
            ngồi tự hỏi vì sao tài khoản admin của mình không dùng được.
```

Gộp phép ghép vào một hàm khiến lỗi đó **không thể xảy ra**: nơi gọi duy nhất là
`TokenAuthFilter.java:65` — `value.role().authority()`.

---

## 3. `parse()` — phân giải không ném ngoại lệ

```java
@com.fasterxml.jackson.annotation.JsonCreator
public static Role parse(String raw) {
    if (raw == null) return USER;
    try {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
        return USER;
    }
}
```

### 3.1 Ba lớp phòng thủ trong bốn dòng

| Dòng | Chặn gì |
|---|---|
| `raw == null` | Trường `role` thiếu hẳn trong JSON cũ |
| `.trim()` | `" ADMIN "` do sửa tay trong tệp |
| `.toUpperCase(Locale.ROOT)` | `"admin"` từ body request; **`Locale.ROOT`** để không dính bẫy locale Thổ Nhĩ Kỳ (`i` → `İ`) |
| `catch` | `"SUPERADMIN"`, `"Quản trị"`, hoặc bất kỳ chuỗi nào khác |

### 3.2 `@JsonCreator` — chi tiết khiến toàn bộ đoạn trên có ý nghĩa

Javadoc dòng 42–45 nói rất rõ:

> Không có nó, Jackson **tự** phân giải enum và ném ngoại lệ khi gặp giá trị lạ
> — nghĩa là một ký tự thừa trong `users.json` sẽ làm ứng dụng không khởi động
> được, và toàn bộ đoạn Javadoc ở trên chỉ đúng **trên giấy**.

Đây là loại lỗi rất dễ mắc: viết một hàm `parse` an toàn, tin rằng mọi đường vào
đều đi qua nó, trong khi framework có đường đi riêng.

```
   ĐƯỜNG VÀO CỦA Role — cả hai đều qua parse() nhờ @JsonCreator

   ① JsonUserStore.load()      users.json  ──Jackson──►  Role.parse
   ② AdminUserController       body JSON   ──Jackson──►  Role.parse
   ③ Java gọi trực tiếp        Role.ADMIN                (không cần parse)
```

Với đường ②, một vai trò gõ sai bị hiểu thành `USER` — vẫn là hướng an toàn
(không ai vô tình được nâng quyền), **và endpoint trả về vai trò thật sự đã đặt**
nên người gọi nhìn thấy ngay mình gõ sai.

---

## 4. Hướng dẫn về code

### 4.1 Thêm vai trò thứ ba — checklist đầy đủ

Giả sử cần `VIEWER` (đọc số liệu, không được chạy crawl):

1. **Thêm hằng số enum** — đặt sau `USER`, trước `ADMIN` nếu muốn thứ tự tự
   nhiên theo mức quyền.
2. **Sửa `SecurityConfig`**: các endpoint `/api/admin/**` hiện dùng
   `hasRole("ADMIN")` — phải tách thành `hasAnyRole("ADMIN","VIEWER")` cho
   endpoint đọc và giữ `hasRole("ADMIN")` cho endpoint ghi.
3. **Kiểm tra `parse()`** không cần sửa — nó tự nhận giá trị mới.
4. **Kiểm tra giao diện** `AdminUserController` + frontend: dropdown chọn vai trò
   phải hiện đủ ba lựa chọn.
5. **Thêm test** vào `AccountAuthorizationTest`: `VIEWER` gọi endpoint ghi phải
   nhận 403.

> ⚠️ **Bước 2 là bước hay quên nhất.** Thêm hằng số enum không tự sinh ra luật
> phân quyền; nếu chỉ làm bước 1, `VIEWER` sẽ có đúng quyền của `USER` — không
> sai về bảo mật, nhưng tính năng không hoạt động và không có lỗi nào báo.

### 4.2 Không nên làm

| Việc | Vì sao không |
|---|---|
| Thêm `ordinal()` vào logic so sánh quyền | Thứ tự khai báo trở thành hợp đồng ngầm; chèn một vai trò vào giữa sẽ đổi nghĩa toàn hệ thống |
| Lưu `role.ordinal()` xuống JSON | Đổi thứ tự = đổi quyền của tài khoản đã lưu |
| Ném ngoại lệ trong `parse` | Xem mục 3.2 |
| Bỏ `Locale.ROOT` | `"admin"` không phân giải được trên máy đặt locale `tr` |

### 4.3 Đọc trạng thái phân quyền lúc chạy

```powershell
# Xem vai trò thật sự gắn với token hiện tại
curl -s -H "Authorization: Bearer <token>" http://localhost:8080/api/auth/me | jq .role
```

---

## 5. Kiểm thử liên quan

| Test | Bảo vệ điều gì |
|---|---|
| `test/java/com/vnsearch/auth/AccountAuthorizationTest.java` | `USER` không vào được `/api/admin/**`; `ADMIN` vào được |
| `test/java/com/vnsearch/auth/JsonUserStoreTest.java` | Vai trò lạ trong tệp không làm gãy nạp dữ liệu |
| `test/java/com/vnsearch/analytics/AnalyticsAuthorizationTest.java` | Endpoint số liệu tôn trọng vai trò |

```powershell
cd search-engine
.\mvnw.cmd -q -Dtest='AccountAuthorizationTest,JsonUserStoreTest' test
```

Một test đáng thêm nếu chưa có:

```java
@Test
void giaTriLaHaVeUser() {
    assertEquals(Role.USER, Role.parse("SUPERADMIN"));
    assertEquals(Role.USER, Role.parse(null));
    assertEquals(Role.ADMIN, Role.parse("  admin  "));
}
```

---

## 6. Liên kết

- Nơi gắn quyền vào `SecurityContext`: [`TokenAuthFilter.md`](./TokenAuthFilter.md)
- Nơi khai báo luật truy cập: `docs2/main/java/com/vnsearch/config/SecurityConfig.md`
- Bản ghi tài khoản: [`User.md`](./User.md)
- Nghiệp vụ tài khoản: [`UserService.md`](./UserService.md)
- Tổng quan: `docs/SECURITY.md`, `docs/ACCOUNTS-AND-DASHBOARD.md`
