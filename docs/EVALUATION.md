# Đánh giá chất lượng tìm kiếm (EVALUATION)

> Tài liệu này được **sinh tự động** bởi `com.vnsearch.eval.EvaluationRunner`.
> Mọi con số đều tái lập được: chạy lại lệnh dưới đây sẽ ra đúng kết quả này.
> **Đừng sửa tay file này** — hãy sửa phần sinh báo cáo trong
> `eval/EvaluationRunner.java` rồi chạy lại.

```bash
cd search-engine
./mvnw.cmd exec:java -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \
     -Dexec.args="data/crawled-multi.json 200"
```

## Cách đọc tài liệu này

| Mục | Trả lời câu hỏi |
|---|---|
| 1. Phương pháp | Lấy đâu ra "đáp án đúng" khi không có người gán nhãn? |
| 2. Corpus | Thí nghiệm chạy trên dữ liệu gì, cấu hình nào? |
| 3. Kết quả | 11 cấu hình xếp hạng, cái nào tốt nhất? |
| 4. Cách đọc bảng | Vì sao 11 cấu hình đó, chứ không phải 11 cấu hình khác? |
| 5. Nhận xét | Kết luận rút ra được |
| 6. Hạn chế | Kết quả này KHÔNG chứng minh điều gì |
| 7. Thang đo | Một giả định ngầm của công thức kết hợp — và nó sai |

Nếu chỉ đọc được hai mục, hãy đọc **mục 4** và **mục 7**: mục 4 giải thích
thiết kế thí nghiệm, mục 7 chứa phát hiện quan trọng nhất.

## 1. Phương pháp

### 1.1. Vì sao dùng known-item search

Muốn đo chất lượng tìm kiếm thì phải biết **tài liệu nào liên quan tới truy
vấn nào** — tập nhãn này gọi là *qrels*. Vấn đề: qrels thường phải do người
gán tay, vừa tốn công vừa chủ quan. Gán nhãn 5.011 tài liệu cho 30 truy vấn
là 150.000 lượt đánh giá.

**Known-item search** là phương pháp kinh điển né được điều đó bằng cách lật
ngược bài toán. Thay vì hỏi "tài liệu nào liên quan tới truy vấn này" (cần
người trả lời), ta **chọn trước một tài liệu**, sinh truy vấn từ chính các từ
khoá đặc trưng nhất của nó, và tài liệu đó chính là đáp án đúng **duy nhất**.

Phương pháp này mô phỏng đúng một tình huống rất thật: người dùng nhớ mang
máng một bài báo đã đọc rồi gõ vài từ khoá để tìm lại.

Vì mỗi truy vấn có đúng một đáp án, độ đo phù hợp là **MRR** và
**Success@k** — không phải MAP hay nDCG (những độ đo đó cần nhiều tài liệu
liên quan ở nhiều mức độ).

### 1.2. Chọn từ khoá thế nào cho truy vấn có ý nghĩa

Đây là chỗ dễ làm sai nhất của cả phương pháp. Từ khoá của mỗi truy vấn
được chọn theo điểm TF-IDF cao nhất, nhưng **chỉ
lấy các term có document frequency trong khoảng [3, 10% số tài liệu]**. Lọc dưới để
loại term quá hiếm (nếu chỉ một tài liệu chứa term thì phép giao posting
list trả về đúng một kết quả, hệ thống nào cũng đạt MRR = 1,0 và bài đánh
giá mất hết ý nghĩa phân biệt); lọc trên để loại term quá phổ biến, gần
như không mang thông tin.

Thêm hai chi tiết trong cách sinh truy vấn:

- **Nhân đôi điểm cho term xuất hiện trong tiêu đề** — vì đó chính là thứ
  người dùng nhớ và gõ lại.
- **Loại truy vấn trùng** — nếu hai tài liệu sinh ra cùng một chuỗi truy
  vấn thì ground truth nhập nhằng, không biết đáp án nào mới đúng.

### 1.3. Hai độ đo được dùng

| Độ đo | Ý nghĩa | Công thức |
|---|---|---|
| **MRR** | Trung bình nghịch đảo thứ hạng của tài liệu đích. Đây là độ đo chính. | `MRR = (1/\|Q\|) · Σ 1/rank` |
| **Success@k** | Tỷ lệ truy vấn mà tài liệu đích lọt vào top k. | `Success@k = (số truy vấn có rank ≤ k) / \|Q\|` |

**Ví dụ tính tay cho MRR.** Giả sử chạy 4 truy vấn, tài liệu đích nằm ở
hạng 1, 2, 5 và không tìm thấy:

| Truy vấn | Hạng của đích | Reciprocal Rank |
|---|---|---|
| q1 | 1 | 1/1 = 1,000 |
| q2 | 2 | 1/2 = 0,500 |
| q3 | 5 | 1/5 = 0,200 |
| q4 | không tìm thấy | 0,000 |

`MRR = (1,000 + 0,500 + 0,200 + 0,000) / 4 = 0,425`

**Cách đọc một giá trị MRR.** Vì hạng 1 cho 1,0 và hạng 2 cho 0,5, MRR chịu
ảnh hưởng rất mạnh từ việc đích có nằm ở **hạng 1** hay không. MRR ≈ 0,92
nghĩa là đại đa số truy vấn tìm ra đích ngay ở vị trí đầu — điều này khớp
với Success@1 trong bảng kết quả, và hai con số đó nên luôn được đọc cùng
nhau.

## 2. Corpus và cấu hình thí nghiệm

| Thông số | Giá trị |
|---|---|
| Số tài liệu | 5011 |
| Số term phân biệt | 136768 |
| Độ dài tài liệu trung bình | 1043.3 token |
| Thời gian dựng chỉ mục | 6.8 giây |
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
| TF-IDF thuần | 0.8537 | 78.0% | 95.0% | 96.5% | 3.90 |
| BM25 thuần | 0.8989 | 85.0% | 96.5% | 97.5% | 4.08 |
| TF-IDF + title | 0.9083 | 86.0% | 97.0% | 98.0% | 3.13 |
| TF-IDF + PageRank | 0.8625 | 79.0% | 95.5% | 96.5% | 3.06 |
| **TF-IDF + PR + title (đang dùng)** | **0.9229** | 88.0% | 97.5% | 98.0% | 3.14 |
| TF-IDF beta=0.05 | 0.9196 | 87.5% | 97.5% | 98.0% | 3.13 |
| TF-IDF beta=0.10 | 0.9196 | 87.5% | 97.5% | 98.0% | 3.14 |
| TF-IDF beta=0.20 | 0.9204 | 87.5% | 97.5% | 98.0% | 3.12 |
| TF-IDF beta=0.50 | 0.9229 | 88.0% | 97.5% | 98.0% | 3.13 |
| TF-IDF beta=0.80 | 0.9189 | 87.5% | 97.5% | 98.0% | 3.22 |
| BM25 + PR + title | 0.9089 | 86.0% | 97.0% | 97.5% | 3.21 |

## 4. Cách đọc bảng kết quả

11 cấu hình trên **không** được chọn tuỳ ý. Chúng được thiết kế theo kiểu
**ablation**: mỗi cấu hình chỉ khác cấu hình nền **đúng một** yếu tố, để
chênh lệch quan sát được **quy được về đúng yếu tố đó**.

| Nhóm | Cấu hình | Câu hỏi được trả lời |
|---|---|---|
| 1 | TF-IDF thuần, BM25 thuần (α=1, β=γ=0) | Mô hình tính điểm nào tốt hơn, khi tắt hết tín hiệu khác? |
| 2 | TF-IDF + title (0.9/0/0.1) · TF-IDF + PageRank (0.7/0.3/0) · cả hai (0.6/0.3/0.1) | Từng tín hiệu bổ sung đóng góp bao nhiêu? |
| 3 | Quét beta = 0.05 … 0.80 | Trọng số PageRank nào tối ưu? |
| 4 | BM25 + PR + title (0.6/0.3/0.1) | Ưu thế của BM25 có cộng hưởng với các tín hiệu khác? |

**Cách tách biệt đóng góp của một tín hiệu.** So hai hàng chỉ khác nhau ở
tín hiệu đó:

```
đóng góp của title bonus = MRR(TF-IDF + title)     − MRR(TF-IDF thuần)
đóng góp của PageRank    = MRR(TF-IDF + PageRank)  − MRR(TF-IDF thuần)
```

Hãy tự tính hai hiệu số này từ bảng ở mục 3. Bạn sẽ thấy đóng góp của
title bonus **lớn hơn nhiều lần** đóng góp của PageRank — đó là dấu hiệu
đầu tiên dẫn tới phát hiện ở mục 7.

> **Cảnh báo quan trọng khi đọc nhóm 3.** Phép quét beta bị ràng buộc
> `alpha = 0.9 − beta` (gamma giữ nguyên 0.1). Nghĩa là khi beta tăng thì
> alpha **giảm theo**, nên mỗi hàng thay đổi **hai** biến số cùng lúc, không
> phải một. Đây không phải ablation thuần khiết, và mục 7 giải thích vì sao
> điều đó làm mọi kết luận về beta trở nên vô nghĩa.

> **Về cột `ms/truy vấn`.** Con số này chỉ để tham khảo, **không** phải phép
> đo hiệu năng nghiêm túc: nó không có vòng làm nóng JVM riêng cho từng cấu
> hình, nên cấu hình chạy trước gánh phần lớn chi phí JIT. Muốn so tốc độ
> nghiêm túc thì xem `docs/GIN-BASELINE.md`, nơi có làm nóng đúng cách.

## 5. Nhận xét

**BM25 với TF-IDF.** BM25 thuần đạt MRR 0.8989 so với 0.8537 của TF-IDF cosine thuần (chênh +5.3%). Kết quả phù hợp với kỳ vọng lý thuyết: cơ chế bão hoà tần suất của BM25 hạn chế được ảnh hưởng của việc lặp từ khoá, còn tham số `b` cho phép điều chỉnh mức phạt tài liệu dài mềm dẻo hơn phép chia cứng cho `sqrt(docLength)` của TF-IDF.

**Đóng góp của PageRank.** Cấu hình đang dùng (0.6/0.3/0.1) đạt MRR 0.9229, so với 0.8537 khi tắt hoàn toàn PageRank. PageRank có đóng góp dương.

**Bộ trọng số tốt nhất.** Trong toàn bộ 11 cấu hình thử nghiệm, tốt nhất là **TF-IDF + PR + title (đang dùng)** với MRR = 0.9229 và Success@1 = 88.0%. Cấu hình đang dùng đã là tốt nhất trong các phương án thử nghiệm.

## 6. Hạn chế của phương pháp

Phải nêu rõ để kết quả được diễn giải đúng. Một báo cáo không nêu hạn chế
thì không đáng tin, vì mọi phương pháp đo đều có hạn chế.

1. **Known-item search chỉ có đúng một tài liệu đúng cho mỗi truy vấn.**
   Nó đo tốt khả năng "tìm lại đúng bài đã biết", nhưng không đo được
   chất lượng của truy vấn khám phá kiểu "tin tức công nghệ" — loại truy
   vấn mà nhiều tài liệu cùng liên quan ở các mức khác nhau. Vì vậy nó
   **thiên vị chống lại PageRank**, vốn là tín hiệu về uy tín chung chứ
   không về mức khớp với một truy vấn cụ thể. Nói cách khác: PageRank có
   thể đang làm tốt việc của nó mà phương pháp đo này không nhìn thấy.
2. **Truy vấn được sinh máy móc từ chính tài liệu**, nên phân bố từ khoá
   không hoàn toàn giống truy vấn người thật gõ. Người thật gõ ngắn hơn,
   sai chính tả, dùng từ thông dụng thay vì từ đặc trưng nhất.
3. **Chỉ đo được xếp hạng, không đo được tách từ.** Nếu tokenizer ghép sai
   một từ ghép thì cả truy vấn lẫn tài liệu đều sai theo cùng một cách,
   nên phép đo vẫn cho kết quả tốt. Độ chính xác tách từ cần một tập văn
   bản đã tách thủ công làm chuẩn — hiện **chưa có**.
4. Để bổ khuyết ba điểm trên, cần thêm bộ truy vấn có **nhãn liên quan
   nhiều bậc do người gán** (xem `PoolBuilder` và `QrelsEvaluationRunner`),
   khi đó mới dùng được nDCG/MAP và mới đánh giá công bằng cho PageRank.


## 7. Phân tích thang đo của các thành phần điểm

> **Vì sao phải có mục này.** Mục 3 cho thấy bộ trọng số 0.6/0.3/0.1 đạt
> MRR cao nhất. Nhưng một bảng số liệu chỉ nói *cấu hình nào tốt hơn*, nó
> không nói *vì sao*. Trước khi rút ra bất kỳ kết luận nào về ý nghĩa của
> từng trọng số, phải kiểm tra một giả định ngầm mà công thức kết hợp
> tuyến tính dựa vào — và giả định đó hoá ra là SAI.

Điểm cuối cùng là `alpha*tfidf + beta*pageRank + gamma*titleBonus`. Công
thức này chỉ có ý nghĩa nếu ba đại lượng cùng thang đo. Đo trên 852 cặp
(truy vấn, kết quả top-10):

| Thành phần | Giá trị trung bình | Giá trị lớn nhất | Sau khi nhân trọng số |
|---|---|---|---|
| TF-IDF cosine | 0.177687 | 1.894824 | 0.106612 (alpha=0.6) |
| PageRank | 0.00035388 | 0.00769142 | 0.00010616 (beta=0.3) |
| Title bonus | trong khoảng [0, 1] | 1.0 | tối đa 0.1 (gamma=0.1) |

**Phát hiện:** phần đóng góp của TF-IDF lớn hơn phần đóng góp của PageRank
khoảng **1004 lần** sau khi đã nhân trọng số. Nguyên nhân: PageRank là một
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

**Bài học tổng quát:** khi kết hợp tuyến tính nhiều tín hiệu, **luôn kiểm tra
độ lớn thực tế** của từng thành phần trước khi diễn giải trọng số. Một trọng
số lớn không có nghĩa là ảnh hưởng lớn. Đây là loại lỗi mà bảng kết quả
không bao giờ tự tố giác: mọi con số MRR ở mục 3 đều đúng, chỉ có cách
*giải thích* chúng là sai nếu bỏ qua phép kiểm tra này.
