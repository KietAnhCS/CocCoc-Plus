# SuggestController — 29 dòng, và ba con số ma không ai đặt tên

**File nguồn:** `search-engine/src/main/java/com/vnsearch/controller/SuggestController.java` (29 dòng — tệp ngắn nhất gói `controller`)
**Gói:** `com.vnsearch.controller` · **Loại:** `@RestController @RequestMapping("/api")`
**Vị trí trong luồng:** `GET /api/suggest?prefix={p}&limit={10}` — gợi ý từ khoá, dựa trên Trie
**Đọc kèm:** [`../service/SuggestionService.md`](../service/SuggestionService.md) · [`../datastructure/Trie.md`](../datastructure/Trie.md) · [`SearchController.md`](./SearchController.md) · [`../config/RateLimitFilter.md`](../config/RateLimitFilter.md)

---

## 📌 Hiểu trong 30 giây

```java
@GetMapping("/suggest")
public Map<String, List<String>> suggest(@RequestParam("prefix") String prefix,
                                          @RequestParam(value = "limit", defaultValue = "10") int limit) {
    int safeLimit = limit < 1 || limit > 50 ? 10 : limit;
    return Map.of("suggestions", facade.suggest(prefix, safeLimit));
}
```

Một dòng kẹp, một dòng uỷ nhiệm. Cả tệp chỉ có **một dòng Javadoc**.

```mermaid
%%{init:{'theme':'base','themeVariables':{'background':'#ffffff','primaryColor':'#ffffff','primaryTextColor':'#000000','primaryBorderColor':'#000000','lineColor':'#000000','textColor':'#000000','mainBkg':'#ffffff','nodeBorder':'#000000','clusterBkg':'#ffffff','clusterBorder':'#000000','edgeLabelBackground':'#ffffff','fontFamily':'ui-monospace, SFMono-Regular, Consolas, monospace'}}}%%
flowchart TD
    U["người dùng gõ từng ký tự<br/>trong ô tìm kiếm"] -->|"mỗi ký tự = 1 request"| R["GET /api/suggest?prefix=má"]
    R --> RL["RateLimitFilter<br/>120 req/phút CHUNG với /api/search"]
    RL --> C["SuggestController"]
    C --> K["kẹp limit: ngoài [1,50] ⇒ 10"]
    K --> F["SearchEngineFacade.suggest"]
    F --> T["SuggestionService → Trie"]
    T --> J["{ \"suggestions\": [...] }"]
```

```
   ⭐ MŨI TÊN ĐẦU TIÊN LÀ ĐIỀU QUAN TRỌNG NHẤT VỀ ENDPOINT NÀY,
     VÀ NÓ KHÔNG ĐƯỢC NHẮC Ở ĐÂU TRONG MÃ.

   Autocomplete được gọi MỖI KHI người dùng gõ một ký tự.
   Gõ "máy tính" = 8 request.

   ⇒ Nó là endpoint có TẦN SUẤT CAO NHẤT của cả hệ thống,
     cao hơn /api/search cả chục lần.
   ⇒ Và nó chia CHUNG hạn mức 120 req/phút với /api/search.
   ⇒ Xem mục 3.
```

---

## 1. Ba con số ma

```java
int safeLimit = limit < 1 || limit > 50 ? 10 : limit;
//                              ↑↑         ↑↑
//                        khong ten    khong ten
//     defaultValue = "10"  ← con so THU BA, o cho khac
```

```
   BA CHỖ, BA CON SỐ, HAI TRONG BA TRÙNG NHAU

   ① @RequestParam(defaultValue = "10")   ← khi KHÔNG truyền
   ② limit > 50 ⇒ ...                     ← chặn trên
   ③ ... ? 10 : limit                     ← khi truyền SAI

   ⇒ ① và ③ phải luôn bằng nhau về mặt ngữ nghĩa
     ("giá trị mặc định"), nhưng chúng là hai chuỗi ký tự
     độc lập ở hai chỗ khác nhau.
   ⇒ Sửa một mà quên cái kia: không lỗi biên dịch,
     không lỗi lúc chạy, chỉ là hành vi lệch.

   Đối chiếu với SearchController.java ngay bên cạnh:
     private static final int MAX_PAGE = 1_000;
     private static final int MAX_SIZE = 100;
     private static final int DEFAULT_SIZE = 20;
   ⇒ Ba hằng số CÓ TÊN, có Javadoc.

   ⇒ Hai tệp cạnh nhau, cùng một khuôn việc,
     hai mức chăm sóc hoàn toàn khác nhau.
```

```
   VÀ MỘT ĐIỂM NHẤT QUÁN NGƯỢC ĐỜI

   SearchController.size:  ngoài khoảng ⇒ về MẶC ĐỊNH (20)
   SuggestController.limit: ngoài khoảng ⇒ về MẶC ĐỊNH (10)

   ⇒ Hai tệp NHẤT QUÁN với nhau ở điểm này.
   ⇒ Nhưng SearchController.page lại KẸP VỀ BIÊN.

   ⇒ Tức là quy tắc thật là: "page kẹp, mọi thứ khác về
     mặc định" — một quy tắc không ai phát biểu, và không
     ai giải thích được vì sao.
   ⇒ Xem SearchController.md mục 2.
```

---

## 2. `prefix` — không kiểm gì cả

```java
@RequestParam("prefix") String prefix
...
facade.suggest(prefix, safeLimit)
```

```
   BỐN TRẠNG THÁI CỦA `prefix`

   ① Thiếu hẳn
     ⇒ 400 "Thieu tham so bat buoc: prefix"
     ⇒ đúng, nhờ GlobalExceptionHandler

   ② Chuỗi rỗng (?prefix=)
     ⇒ đi thẳng xuống Trie
     ⇒ Trie.startsWith("") khớp MỌI từ trong từ vựng
     ⇒ nhưng limit chặn ở 50 ⇒ hậu quả có hạn

   ③ Một ký tự (?prefix=a)
     ⇒ duyệt cây con khổng lồ để lấy 50 kết quả
     ⇒ đây là ca ĐẮT NHẤT, và nó là ca THƯỜNG GẶP NHẤT
       (người dùng vừa gõ ký tự đầu tiên)

   ④ Chuỗi cực dài
     ⇒ Trie đi hết vài chục nút rồi không khớp gì
     ⇒ RẺ. Prefix càng dài càng rẻ.

   ⇒ Đảo ngược trực giác: với autocomplete, đầu vào NGẮN
     mới đắt, không phải đầu vào dài.
   ⇒ Và không có gì trong mã nói ra điều đó.
```

```
   ⚠️ VÌ SAO ĐIỀU NÀY ĐÁNG QUAN TÂM HƠN Ở /api/search

   /api/search: truy vấn dài ⇒ nhiều term ⇒ đắt
   /api/suggest: prefix ngắn ⇒ cây con lớn ⇒ đắt

   Người dùng THẬT luôn bắt đầu bằng prefix một ký tự.
   ⇒ Ca đắt nhất là ca xảy ra ở MỌI phiên gõ.
   ⇒ Không phải một vector tấn công — đó là hành vi bình thường.

   ⇒ Nên chi phí này phải được xử lý ở tầng Trie
     (giới hạn độ sâu duyệt, cắt sớm khi đủ limit).
   ⇒ Xem ../datastructure/Trie.md và ../service/SuggestionService.md.
   ⇒ Controller không phải chỗ sửa, nhưng nó cũng không
     ghi lại ràng buộc đó.
```

---

## 3. Hạn mức dùng chung với `/api/search`

[`SecurityConfig`](../config/SecurityConfig.md) đăng ký `RateLimitFilter` cho
`/api/*`, và gáo được khoá theo **IP**, không theo đường dẫn.

```
   PHÉP TÍNH CHO MỘT PHIÊN GÕ BÌNH THƯỜNG

   Người dùng gõ "máy tính giá rẻ"  = 16 ký tự
   ⇒ 16 request /api/suggest (nếu không có debounce)
   ⇒ + 1 request /api/search

   Hạn mức: 120 request/phút cho MỘT IP

   ⇒ Một người dùng gõ 7 truy vấn trong một phút
     là chạm trần.

   ⇒ Với NAT của một trường học (hàng nghìn người
     chung một IP công cộng) — kịch bản đã được nêu ở
     ../config/RateLimitFilter.md mục 7 — thì trần này
     bị chạm gần như tức thì.
```

```
   ⭐ VÀ ĐÂY LÀ CHỖ HAI QUYẾT ĐỊNH ĐÚNG GẶP NHAU
     THÀNH MỘT KẾT QUẢ SAI.

   RateLimitFilter: "120 req/phút là hợp lý cho một máy
     tìm kiếm" — đúng, nếu nghĩ về /api/search.

   SuggestController: "autocomplete gọi mỗi ký tự" — đúng,
     đó là cách autocomplete hoạt động.

   ⇒ Không tệp nào sai. Nhưng không tệp nào biết tệp kia.
   ⇒ Kết quả: người dùng bình thường bị 429 khi đang gõ,
     và triệu chứng là "ô gợi ý thỉnh thoảng không hiện" —
     một lỗi trông như trục trặc mạng.

   ⇒ Xem đề xuất 2.
```

```
   PHẦN GIẢM NHẸ NẰM Ở GIAO DIỆN, KHÔNG Ở ĐÂY

   browser-app gần như chắc chắn có debounce (chờ ~200 ms
   sau lần gõ cuối mới gọi).

   ⇒ Với debounce, "máy tính giá rẻ" chỉ sinh 3-4 request
     thay vì 16.
   ⇒ Trần 120 req/phút trở nên thoải mái.

   ⇒ NHƯNG: đó lại đúng là "một lớp KHÁC giữ hộ bất biến"
     — khuôn lỗi mà SearchController.md mục 1 đã lên án
     bằng cả một khối Javadoc.
   ⇒ Và lớp giữ hộ ở đây thậm chí không phải mã Java:
     nó là mã TypeScript ở một dự án con khác.
```

---

## 4. Kiểu trả về `Map<String, List<String>>`

```java
return Map.of("suggestions", facade.suggest(prefix, safeLimit));
```

```
   VÌ SAO BỌC TRONG MỘT ĐỐI TƯỢNG THAY VÌ TRẢ MẢNG THẲNG

   Trả thẳng:  ["máy tính", "máy giặt", ...]
   Bọc lại:    { "suggestions": ["máy tính", ...] }

   Lý do bọc là một thực hành chuẩn:
     ⇒ thêm trường sau này (thời gian xử lý, prefix đã
       chuẩn hoá, cờ "còn nữa") KHÔNG phá vỡ client cũ
     ⇒ trả mảng JSON ở gốc từng là một vector tấn công
       (JSON hijacking) với các trình duyệt cũ

   ⇒ Quyết định đúng, và nó KHÔNG được giải thích.
```

```
   ⚠️ NHƯNG Map.of KHÔNG PHẢI CÁCH ĐÚNG ĐỂ BỌC

   Cùng vấn đề với ../config/GlobalExceptionHandler.md mục 7:
     - không có lớp nào mô tả hợp đồng
     - gõ sai "sugestions" vẫn biên dịch
     - OpenAPI sinh ra kiểu vô nghĩa
       (additionalProperties: array of string)
     - client TypeScript không sinh được kiểu chính xác

   Một record một dòng giải quyết cả bốn:
     public record SuggestResponse(List<String> suggestions) {}

   ⇒ Dự án ĐÃ có ../model/SearchResponse.md cho endpoint
     bên cạnh. Việc endpoint này dùng Map là một sự
     thiếu nhất quán trong cùng một gói.
```

---

## 5. So sánh với `SearchController` — cùng khuôn, khác mức chăm sóc

| | `SearchController` | `SuggestController` |
|---|---|---|
| Số dòng | 51 | 29 |
| Hằng số có tên | 3 (kèm Javadoc) | **0** |
| Javadoc | 14 dòng cho `MAX_PAGE` | 1 dòng cho cả lớp |
| Kiểu trả về | `SearchResponse` (record) | `Map<String, List<String>>` |
| Kẹp tham số | có, hai kiểu | có, một kiểu |
| Test | không | không |

```
   ⭐ BẢNG NÀY KHÔNG NÓI RẰNG SuggestController LÀ MÃ XẤU.

   29 dòng, không lỗi nào, làm đúng việc của nó.

   Nó nói một điều khác: MỨC CHĂM SÓC TRONG MỘT DỰ ÁN
   KHÔNG ĐỒNG ĐỀU, và sự không đồng đều đó bám theo
   MỨC ĐỘ CHÚ Ý chứ không theo MỨC ĐỘ RỦI RO.

   /api/search  được chú ý (chức năng chính)  ⇒ chăm sóc kỹ
   /api/suggest ít được chú ý (tính năng phụ) ⇒ chăm sóc ít

   Nhưng theo tần suất gọi thì /api/suggest gấp cả chục lần.
   ⇒ Rủi ro nằm ở tệp được chăm sóc ít hơn.
```

---

## 6. Hướng dẫn thực hành

### 6.1 Gọi endpoint

```bash
curl -s 'http://localhost:8080/api/suggest?prefix=má&limit=10' | jq
# { "suggestions": ["máy tính", "máy giặt", "má phanh", ...] }

# Cong khai, khong can xac thuc
# Bi RateLimitFilter gioi han CHUNG voi /api/search
```

### 6.2 Hành vi ở biên

```
   ?prefix=má              → limit=10  (mac dinh)
   ?prefix=má&limit=0      → limit=10  (ve MAC DINH)
   ?prefix=má&limit=-5     → limit=10
   ?prefix=má&limit=100    → limit=10  (KHONG phai 50)
   ?prefix=má&limit=50     → limit=50  (bien tren, hop le)
   ?prefix=má&limit=abc    → 400 (Spring khong ep duoc kieu)
   (khong co prefix)       → 400 "Thieu tham so bat buoc: prefix"
   ?prefix=                → chuoi rong, Trie khop MOI tu
```

### 6.3 Cạm bẫy

```
   ① limit=100 cho ra 10, KHÔNG phải 50.
     Cùng cạm bẫy với size ở SearchController.

   ② Prefix NGẮN đắt hơn prefix DÀI — ngược trực giác,
     và không ghi ở đâu.

   ③ Endpoint này chia CHUNG hạn mức 120 req/phút với
     /api/search, dù nó được gọi mỗi ký tự người dùng gõ.

   ④ Không có debounce ở phía máy chủ. Toàn bộ phần giảm
     nhẹ nằm ở mã TypeScript của browser-app.

   ⑤ Ba con số 10/50/10 không có tên. Sửa một mà quên
     cái kia không gây lỗi nào.

   ⑥ Kiểu trả về Map ⇒ không có hợp đồng, gõ sai tên
     trường vẫn biên dịch.

   ⑦ Endpoint CÔNG KHAI. Từ vựng trong Trie đến từ corpus
     đã crawl, nên nó phơi ra một phần nội dung chỉ mục —
     vô hại với corpus báo chí công khai, nhưng là điều
     phải cân nhắc nếu corpus đổi.
```

---

## 7. Độ phức tạp & chi phí

| Thao tác | Chi phí |
|---|---|
| Kẹp `limit` | $O(1)$ |
| `facade.suggest` | $O(P + K)$ với $P$ = độ dài prefix, $K$ = số nút cây con phải duyệt |

```
   PHÂN TÍCH — VÌ SAO K MỚI LÀ THỨ QUAN TRỌNG

   Trie.startsWith(prefix):
     ① đi P nút để tới nút gốc của cây con   → O(P), rẻ
     ② duyệt cây con thu thập từ             → O(K)

   Với prefix = "máy tính giá rẻ" (16 ký tự):
     P = 16, K ≈ vài chục
   ⇒ tổng ~50 thao tác

   Với prefix = "m" (1 ký tự):
     P = 1, K = TOÀN BỘ số từ bắt đầu bằng "m"
   ⇒ với từ vựng ~400.000 term, có thể vài chục nghìn nút

   ⇒ Chênh lệch ba bậc độ lớn giữa hai ca, và ca ĐẮT
     là ca người dùng LUÔN đi qua (ký tự đầu tiên).

   ⚠️ limit=50 KHÔNG tự động chặn K: nếu cách duyệt là
     "thu thập hết rồi sắp xếp rồi cắt", thì K vẫn là
     toàn bộ cây con.
   ⇒ Chỉ khi Trie CẮT SỚM ngay khi đủ 50 kết quả thì
     chi phí mới thật sự bị chặn.
   ⇒ Xem ../datastructure/Trie.md — đó là chỗ quyết định,
     không phải ở đây.
```

---

## 8. Kiểm thử liên quan

| Tệp test | Kiểm gì |
|---|---|
| [`TrieTest`](../../../../../test/java/com/vnsearch/datastructure/TrieTest.md) | Cấu trúc Trie bên dưới |
| [`SearchEngineFacadeApiTest`](../../../../../test/java/com/vnsearch/service/SearchEngineFacadeApiTest.md) | API facade, có thể chạm `suggest` |

```
   ⚠️ KHÔNG CÓ TEST NÀO CHO CHÍNH CONTROLLER.
```

```
   NHỮNG TÍNH CHẤT KHÔNG ĐƯỢC CANH GIỮ

   ✗ limit ngoài [1, 50] về 10
   ✗ limit = 50 được chấp nhận (biên trên)
   ✗ Thiếu prefix → 400 kèm tên tham số
   ✗ Khoá JSON là "suggestions" — đây là HỢP ĐỒNG với
     browser-app, và với Map<String,...> thì một lỗi gõ
     KHÔNG bị trình biên dịch bắt
   ✗ Endpoint truy cập được không cần xác thực

   ⇒ Tính chất thứ tư là tính chất duy nhất mà việc mất nó
     làm HỎNG NGAY giao diện — và cũng là tính chất duy nhất
     mà kiểu dữ liệu hiện tại không bảo vệ được chút nào.
```

---

## 9. Liên kết

- Nơi logic gợi ý thật sự nằm: [`../service/SuggestionService.md`](../service/SuggestionService.md)
- Cấu trúc dữ liệu quyết định chi phí, và là nơi phải cắt sớm: [`../datastructure/Trie.md`](../datastructure/Trie.md)
- Lớp điều phối được uỷ nhiệm: [`../service/SearchEngineFacade.md`](../service/SearchEngineFacade.md)
- Endpoint anh em, cùng khuôn nhưng chăm sóc kỹ hơn: [`SearchController.md`](./SearchController.md)
- Hạn mức tần suất đang bị chia chung: [`../config/RateLimitFilter.md`](../config/RateLimitFilter.md) mục 7
- Luật cho phép endpoint này công khai: [`../config/SecurityConfig.md`](../config/SecurityConfig.md)
- Nơi thiếu `prefix` biến thành 400: [`../config/GlobalExceptionHandler.md`](../config/GlobalExceptionHandler.md)
- Cùng vấn đề "kiểu trả về không được định kiểu": [`../config/GlobalExceptionHandler.md`](../config/GlobalExceptionHandler.md) mục 7
- Ví dụ về kiểu trả về đúng chuẩn trong cùng dự án: [`../model/SearchResponse.md`](../model/SearchResponse.md)
