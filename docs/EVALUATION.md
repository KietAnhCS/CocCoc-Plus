# Đánh giá chất lượng tìm kiếm (EVALUATION)

> Tài liệu này được **sinh tự động** bởi `com.vnsearch.eval.EvaluationRunner`.
> Mọi con số đều tái lập được: chạy lại lệnh dưới đây sẽ ra đúng kết quả này.

```bash
cd search-engine
./mvnw.cmd exec:java -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \
     -Dexec.args="data/crawled-multi.json 200"
```

## 1. Phương pháp

Dùng **known-item search** — phương pháp đánh giá kinh điển khi không có
sẵn bộ nhãn liên quan do người gán. Thay vì hỏi "tài liệu nào liên quan
tới truy vấn này" (cần người trả lời), ta lật ngược: chọn trước một tài
liệu, sinh truy vấn từ chính các từ khoá đặc trưng nhất của nó, và tài
liệu đó chính là đáp án đúng duy nhất. Mô phỏng đúng tình huống người
dùng nhớ mang máng một bài báo rồi gõ vài từ khoá tìm lại.

Từ khoá của mỗi truy vấn được chọn theo điểm TF-IDF cao nhất, nhưng **chỉ
lấy các term có document frequency trong khoảng [3, 10% số tài liệu]**. Lọc dưới để
loại term quá hiếm (nếu chỉ một tài liệu chứa term thì phép giao posting
list trả về đúng một kết quả, hệ thống nào cũng đạt MRR = 1,0 và bài đánh
giá mất hết ý nghĩa phân biệt); lọc trên để loại term quá phổ biến, gần
như không mang thông tin.

### Các độ đo

| Độ đo | Ý nghĩa |
|---|---|
| **MRR** | Trung bình nghịch đảo thứ hạng của tài liệu đích. Đích ở hạng 1 được 1,0; hạng 2 được 0,5; hạng 10 được 0,1. Đây là độ đo chính. |
| **Success@k** | Tỷ lệ truy vấn mà tài liệu đích lọt vào top k. |

## 2. Corpus và cấu hình thí nghiệm

| Thông số | Giá trị |
|---|---|
| Số tài liệu | 5011 |
| Số term phân biệt | 136768 |
| Độ dài tài liệu trung bình | 1043.3 token |
| Thời gian dựng chỉ mục | 7.0 giây |
| Số vòng lặp PageRank tới hội tụ | 53 |
| Thời gian tính PageRank | 0.2 giây |
| Số truy vấn đánh giá | 200 |
| Số từ khoá mỗi truy vấn | 3 |
| Seed ngẫu nhiên | 42 |

### Ví dụ truy vấn được sinh

| Truy vấn | Tài liệu đích |
|---|---|
| `tọa đàm robot` | https://dantri.com.vn/toa-dam-truc-tuyen.htm |
| `cước token tab` | http://pay.tuoitre.vn/huong-dan-thanh-toan-cuoc-truyen-hinh |
| `柬埔寨国会主席昆索达莉圆满结束对越南的正式访问 共产主义 2026年07月29日星期三` | https://cn.nhandan.vn/article-post156813.html |
| `typhoon dolphin storm` | https://dtinews.dantri.com.vn/vietnam-today/typhoon-dolphin-may-rapidly-intensify-into-a-record-strength-storm-20260728141151125.htm |
| `thịnh nhấc âm nhạc` | https://noivuxahoi.dantri.com.vn/van-hoa-the-thao-giai-tri/noo-phuoc-thinh-tim-lai-niem-vui-tu-am-nhac-20260610173428288.htm |

## 3. Kết quả

| Cấu hình xếp hạng | MRR | Success@1 | Success@5 | Success@10 | ms/truy vấn |
|---|---|---|---|---|---|
| TF-IDF thuần | 0.8537 | 78.0% | 95.0% | 96.5% | 9.58 |
| BM25 thuần | 0.8989 | 85.0% | 96.5% | 97.5% | 9.69 |
| TF-IDF + title | 0.9050 | 85.5% | 97.0% | 98.0% | 8.98 |
| TF-IDF + PageRank | 0.8625 | 79.0% | 95.5% | 96.5% | 8.88 |
| **TF-IDF + PR + title (đang dùng)** | **0.9196** | 87.5% | 97.5% | 98.0% | 8.85 |
| TF-IDF beta=0.05 | 0.9163 | 87.0% | 97.5% | 98.0% | 8.83 |
| TF-IDF beta=0.10 | 0.9163 | 87.0% | 97.5% | 98.0% | 8.87 |
| TF-IDF beta=0.20 | 0.9171 | 87.0% | 97.5% | 98.0% | 8.79 |
| TF-IDF beta=0.50 | 0.9171 | 87.0% | 97.5% | 98.0% | 8.79 |
| TF-IDF beta=0.80 | 0.9131 | 86.5% | 97.5% | 98.0% | 8.75 |
| BM25 + PR + title | 0.9089 | 86.0% | 97.0% | 97.5% | 8.74 |

## 4. Nhận xét

**BM25 với TF-IDF.** BM25 thuần đạt MRR 0.8989 so với 0.8537 của TF-IDF cosine thuần (chênh +5.3%). Kết quả phù hợp với kỳ vọng lý thuyết: cơ chế bão hoà tần suất của BM25 hạn chế được ảnh hưởng của việc lặp từ khoá, còn tham số `b` cho phép điều chỉnh mức phạt tài liệu dài mềm dẻo hơn phép chia cứng cho `sqrt(docLength)` của TF-IDF.

**Đóng góp của PageRank.** Cấu hình đang dùng (0.6/0.3/0.1) đạt MRR 0.9196, so với 0.8537 khi tắt hoàn toàn PageRank. PageRank có đóng góp dương.

**Bộ trọng số tốt nhất.** Trong toàn bộ 11 cấu hình thử nghiệm, tốt nhất là **TF-IDF + PR + title (đang dùng)** với MRR = 0.9196 và Success@1 = 87.5%. Cấu hình đang dùng đã là tốt nhất trong các phương án thử nghiệm.

## 5. Hạn chế của phương pháp

Phải nêu rõ để kết quả được diễn giải đúng:

1. **Known-item search chỉ có đúng một tài liệu đúng cho mỗi truy vấn.**
   Nó đo tốt khả năng "tìm lại đúng bài đã biết", nhưng không đo được
   chất lượng của truy vấn khám phá kiểu "tin tức công nghệ" — loại truy
   vấn mà nhiều tài liệu cùng liên quan ở các mức khác nhau. Vì vậy nó
   **thiên vị chống lại PageRank**, vốn là tín hiệu về uy tín chung chứ
   không về mức khớp với một truy vấn cụ thể.
2. **Truy vấn được sinh máy móc từ chính tài liệu**, nên phân bố từ khoá
   không hoàn toàn giống truy vấn người thật gõ.
3. Để bổ khuyết cả hai điểm trên, cần thêm bộ truy vấn có **nhãn liên quan
   nhiều bậc do người gán** (xem `PoolBuilder`), khi đó mới dùng được
   nDCG/MAP và mới đánh giá công bằng cho PageRank.

## 6. Phân tích thang đo của các thành phần điểm

Điểm cuối cùng là `alpha*tfidf + beta*pageRank + gamma*titleBonus`. Công
thức này chỉ có ý nghĩa nếu ba đại lượng cùng thang đo. Đo trên 852 cặp
(truy vấn, kết quả top-10):

| Thành phần | Giá trị trung bình | Giá trị lớn nhất | Sau khi nhân trọng số |
|---|---|---|---|
| TF-IDF cosine | 0.177473 | 1.894824 | 0.106484 (alpha=0.6) |
| PageRank | 0.00035580 | 0.00769142 | 0.00010674 (beta=0.3) |
| Title bonus | trong khoảng [0, 1] | 1.0 | tối đa 0.1 (gamma=0.1) |

**Phát hiện:** phần đóng góp của TF-IDF lớn hơn phần đóng góp của PageRank
khoảng **998 lần** sau khi đã nhân trọng số. Nguyên nhân: PageRank là một
phân phối xác suất có tổng bằng 1 trên 5011 tài liệu, nên giá trị điển hình
chỉ quanh 1/N ≈ 0.000200, trong khi TF-IDF cosine nằm trong khoảng [0,1] với
giá trị điển hình lớn hơn hàng nghìn lần.

**Hệ quả quan trọng đối với việc diễn giải kết quả:** con số `beta = 0.3`
KHÔNG có nghĩa là "PageRank đóng góp 30% vào điểm cuối". Trên thực tế
PageRank gần như không ảnh hưởng tới thứ hạng ở mọi giá trị beta thử
nghiệm. Vì vậy chênh lệch quan sát được trong phép quét beta ở mục 3 thực
chất phản ánh việc **alpha bị thay đổi theo** (do ràng buộc
`alpha = 0.9 − beta`), tức là tỷ lệ giữa TF-IDF và title bonus, chứ không
phải ảnh hưởng của PageRank.

**Đề xuất khắc phục:** chuẩn hoá PageRank về cùng thang đo trước khi kết
hợp — ví dụ chia cho giá trị PageRank lớn nhất trong corpus, hoặc dùng
min-max normalisation trên tập ứng viên của từng truy vấn. Khi đó trọng số
mới thực sự mang ý nghĩa tỷ lệ đóng góp và mới quét tham số có ý nghĩa được.
