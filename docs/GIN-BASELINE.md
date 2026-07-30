# Đối chứng: chỉ mục đảo tự cài với chỉ mục GIN của PostgreSQL

> Sinh tự động bởi `com.vnsearch.storage.GinBaselineRunner`.

Chạy trên cùng **5011 tài liệu** và cùng **200 truy vấn known-item** (seed 42).

| Tiêu chí | Chỉ mục đảo tự cài | PostgreSQL GIN |
|---|---|---|
| MRR | 0.9196 | 0.8330 |
| Success@1 | 87.5% | 79.5% |
| Success@10 | 98.0% | 91.0% |
| Thời gian truy vấn trung bình | 6.43 ms | 1.18 ms |
| Kích thước chỉ mục | 9.1 MB (JSON) | 15.9 MB |
| Thời gian dựng chỉ mục | 7.4 giây | (nền, tăng dần) |
| Số term phân biệt | 136768 | (nội bộ) |

## Nhận xét

**Về chất lượng**, chỉ mục tự cài đạt MRR cao hơn (0.9196 so với 0.8330, hơn 10.4%). Nguyên nhân chính không nằm ở cấu trúc dữ liệu mà ở khâu XỬ LÝ NGÔN NGỮ: chỉ mục tự cài ghép từ ghép tiếng Việt bằng thuật toán Longest Matching, sinh thêm bản không dấu, và loại từ dừng tiếng Việt; trong khi cấu hình `simple` của PostgreSQL chỉ cắt theo khoảng trắng nên "máy tính" bị tách thành hai token rời rạc.

**Về tốc độ**, PostgreSQL GIN nhanh hơn (1.18 ms so với 6.43 ms) dù phải qua mạng và tầng SQL — một kết quả đáng chú ý cho thấy chỉ mục tự cài còn nhiều dư địa tối ưu.

**Điều so sánh này KHÔNG chứng minh:** rằng cài đặt tự viết tốt hơn PostgreSQL. GIN chạy đa người dùng, có giao dịch ACID, bền vững sau sự cố, và cập nhật tăng dần — chỉ mục tự cài trong đồ án này không có đặc tính nào trong số đó. So sánh chỉ nhằm cho thấy một cài đặt chuyên biệt cho tiếng Việt, chạy hoàn toàn trong bộ nhớ, đạt được gì trên đúng bài toán hẹp mà nó được thiết kế.
