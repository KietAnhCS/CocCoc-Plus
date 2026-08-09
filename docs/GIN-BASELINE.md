# Đối chứng: chỉ mục đảo tự cài với chỉ mục GIN của PostgreSQL

> Sinh tự động bởi `com.vnsearch.storage.GinBaselineRunner`.
> **Đừng sửa tay file này** — hãy sửa phần sinh báo cáo trong
> `storage/GinBaselineRunner.java` rồi chạy lại.

## Vì sao đồ án cần một baseline bên ngoài

Mọi phát biểu kiểu *"chỉ mục tự cài chạy nhanh"* đều chỉ là **tự khẳng
định** — nhanh so với cái gì? Không có mốc so sánh thì con số 6 ms cũng
vô nghĩa như con số 600 ms.

PostgreSQL là mốc so sánh sòng phẳng và **khiêm tốn**, vì:

- Chỉ mục **GIN** của nó bản chất cũng là một **chỉ mục đảo** — cùng ý
  tưởng cốt lõi với thứ đồ án tự cài, nên so sánh là so cùng loại.
- Nó đã được tối ưu suốt **hàng chục năm** bởi một cộng đồng lớn.
- Nó **bất lợi** trong phép đo này (phải đi qua tầng mạng, phân tích SQL,
  đọc trang từ đĩa) mà vẫn là mốc đáng gờm — nên nếu chỉ mục tự cài thắng
  về tốc độ thì phải nói rõ phần lợi thế đó.

> **Nguyên tắc:** báo cáo cả phần mình **thua** mới là báo cáo đáng tin.

## 1. Thiết lập thí nghiệm

Chạy trên cùng **5011 tài liệu** và cùng **200 truy vấn known-item** (seed 42) — đúng bộ truy vấn
mà `docs/EVALUATION.md` dùng, sinh bởi cùng một `KnownItemQueryGenerator`.

### Hai bên được đo thế nào

| | Chỉ mục đảo tự cài | PostgreSQL GIN |
|---|---|---|
| Tách từ | `VietnameseTokenizer` — ghép từ bằng quy hoạch động, sinh bản không dấu, lọc 91 từ dừng | `to_tsvector('simple', …)` — cắt theo khoảng trắng |
| Lưu trữ | `LinkedHashMap<String, List<Posting>>` trong RAM | `tsvector` + chỉ mục GIN trên đĩa |
| Xếp hạng | TF-IDF cosine + PageRank + title bonus (0.6/0.3/0.1) | `ts_rank(tsv, plainto_tsquery(...))` |
| Truy cập | Gọi phương thức trực tiếp trong cùng tiến trình | JDBC qua TCP tới `localhost:5432` |

Vì sao dùng cấu hình `simple` chứ không phải `english`: bộ stemmer tiếng Anh
sẽ cắt gốc từ **sai hoàn toàn** trên tiếng Việt, nên `english` sẽ là một
baseline bị làm cho yếu đi một cách không công bằng.

### Làm nóng JVM — bắt buộc, không phải tuỳ chọn

Trước khi bấm giờ, **cả hai** phía được chạy 2 vòng đầy đủ qua toàn bộ bộ
truy vấn:

```java
for (int round = 0; round < 2; round++) {
    for (KnownItemQuery q : queries) {
        harness.search(q.queryText(), config, TOP_N);   // phía tự cài
        repo.searchWithGin(q.queryText(), TOP_N);       // phía GIN
    }
}
```

Lý do: JVM thực thi những lần gọi đầu bằng **trình thông dịch**, chỉ sau
vài nghìn lượt thì JIT mới biên dịch sang mã máy. Nếu đo ngay từ lần chạy
đầu, phía chạy **trước** gánh toàn bộ chi phí khởi động còn phía chạy
**sau** hưởng JVM đã nóng — chênh lệch đo được khi ấy phản ánh **thứ tự
chạy** chứ không phản ánh cài đặt.

Bản đầu tiên của phép đo này **không** có bước làm nóng, và cho kết quả
10,83 ms so với 1,42 ms. Sau khi thêm làm nóng cho cả hai phía, con số phía
tự cài giảm xuống còn khoảng 6,4 ms — tức **~40%** con số ban đầu chỉ là
chi phí khởi động JVM. Kết luận cuối cùng không đổi, nhưng mức chênh lệch
báo cáo sai lệch đáng kể nếu không sửa.

## 2. Kết quả

| Tiêu chí | Chỉ mục đảo tự cài | PostgreSQL GIN |
|---|---|---|
| MRR | 0.8758 | 0.8330 |
| Success@1 | 81.5% | 79.5% |
| Success@10 | 97.5% | 91.0% |
| Thời gian truy vấn trung bình | 1.62 ms | 1.24 ms |
| Kích thước chỉ mục | n/a | 15.9 MB |
| Thời gian dựng chỉ mục | 7.4 giây | (nền, tăng dần) |
| Số term phân biệt | 136768 | (nội bộ) |

## 3. Nhận xét

**Về chất lượng**, chỉ mục tự cài đạt MRR cao hơn (0.8758 so với 0.8330, hơn 5.1%). Nguyên nhân chính không nằm ở cấu trúc dữ liệu mà ở khâu XỬ LÝ NGÔN NGỮ: chỉ mục tự cài ghép từ ghép tiếng Việt bằng quy hoạch động cực đại trọng số trên từ điển 49.793 mục, sinh thêm bản không dấu, và loại từ dừng tiếng Việt; trong khi cấu hình `simple` của PostgreSQL chỉ cắt theo khoảng trắng nên "máy tính" bị tách thành hai token rời rạc.

**Về tốc độ**, PostgreSQL GIN nhanh hơn (1.24 ms so với 1.62 ms) dù phải qua mạng và tầng SQL — một kết quả đáng chú ý cho thấy chỉ mục tự cài còn nhiều dư địa tối ưu.

## 4. Vì sao hai bên không tương đương về chức năng

Điều này phải nói rõ, vì nó quyết định cách diễn giải **từng** con số ở
mục 2:

| Chỉ mục tự cài CÓ | GIN (cấu hình `simple`) KHÔNG CÓ |
|---|---|
| Ghép từ ghép tiếng Việt (QHĐ cực đại trọng số) | Chỉ cắt theo khoảng trắng |
| Chỉ mục kép có dấu / không dấu | Không |
| Lọc từ dừng tiếng Việt | Không |
| Lưu vị trí token → tìm theo cụm từ | Có `phraseto_tsquery` nhưng không dùng ở đây |
| Kết hợp PageRank khi xếp hạng | Chỉ `ts_rank` theo nội dung |

| GIN CÓ | Chỉ mục tự cài KHÔNG CÓ |
|---|---|
| Đa người dùng, đồng thời | Một tiến trình |
| Giao dịch ACID | Không |
| Bền vững sau sự cố | Mất khi tắt tiến trình |
| Cập nhật tăng dần | Reindex toàn phần |
| Nén chỉ mục | Không |

**Cách diễn giải đúng:** chênh lệch về **chất lượng** phản ánh mức độ phù
hợp với **tiếng Việt** (tức là công của khâu xử lý ngôn ngữ, không phải của
cấu trúc dữ liệu). Chênh lệch về **tốc độ** mới là so sánh gần với việc
so cài đặt cấu trúc dữ liệu — nhưng vẫn không thuần khiết, vì GIN phải qua
mạng và SQL còn phía tự cài truy cập thẳng heap.

## 5. Điều so sánh này KHÔNG chứng minh

**Rằng cài đặt tự viết tốt hơn PostgreSQL.** GIN chạy đa người dùng, có
giao dịch ACID, bền vững sau sự cố, và cập nhật tăng dần — chỉ mục tự cài
trong đồ án này **không có đặc tính nào** trong số đó. So sánh chỉ nhằm cho
thấy một cài đặt chuyên biệt cho tiếng Việt, chạy hoàn toàn trong bộ nhớ,
đạt được gì trên đúng bài toán hẹp mà nó được thiết kế.

**Rằng chỉ mục đảo tự cài đã được tối ưu tốt.** Nếu GIN nhanh hơn dù phải
qua mạng và tầng SQL, thì đó là dấu hiệu phía tự cài còn **nhiều dư địa**:
nén posting list (delta encoding, variable-byte), tránh boxing `Integer`
trong phép giao, chuyển ma trận thưa sang CSR sau khi dựng xong.

**Rằng chất lượng tiếng Việt của hệ thống đã tốt.** MRR cao ở đây chỉ nói
hệ thống tìm lại được bài đã biết. Độ
chính xác tách từ **chưa được đo** — xem mục 6.1 của `docs/DSA-REPORT.md`.

## 6. Cách chạy lại

```bash
# 1. Dựng PostgreSQL (từ thư mục gốc của repo)
docker compose up -d

# 2. Nạp corpus vào CSDL (~28 giây cho 5.011 tài liệu)
cd search-engine
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.storage.PostgresImportRunner \
  -Dexec.args="data/crawled-multi.json"

# 3. Chạy phép đối chứng → ghi lại chính file này
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.storage.GinBaselineRunner -Dexec.args="200"
```

> **Về tính tái lập.** Các con số **chất lượng** (MRR, Success@k) tái lập
> chính xác vì bộ truy vấn dùng seed cố định 42. Các con số **thời gian**
> dao động vài phần trăm giữa các lần chạy và giữa các máy — đó là bản chất
> của phép đo thời gian, không phải lỗi.
