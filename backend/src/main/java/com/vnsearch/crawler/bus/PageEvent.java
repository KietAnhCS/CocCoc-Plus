package com.vnsearch.crawler.bus;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Một trang đã <b>tải xong, phân tích xong và vượt qua khối Duplicate
 * Detection</b> — đơn vị dữ liệu duy nhất mà crawler đẩy lên bus.
 *
 * <p>Đây là chỗ nối giữa hai nửa của sơ đồ kiến trúc:
 *
 * <pre>
 *   HTML Parser -> Duplicate Detection ──┬──> Content Storage   (crawler tự lưu)
 *                                        │
 *                                        └──> [ Kafka ] ──┬──> URL Extractor
 *                                                         ├──> Image Download
 *                                                         └──> Analytics Service
 * </pre>
 *
 * <h2>Vì sao mang theo cả HTML thô</h2>
 *
 * <p>Hai trong ba Modular Service cần <b>cây DOM</b>, không phải văn bản đã
 * bóc: {@code URL Extractor} tìm thẻ {@code <a href>}, {@code Image Download}
 * tìm thẻ {@code <img src>}. Ba phương án đã cân nhắc:
 *
 * <table border="1">
 *   <caption>Đưa gì vào thông điệp</caption>
 *   <tr><th>Phương án</th><th>Hệ quả</th></tr>
 *   <tr>
 *     <td>Chỉ gửi URL, service tự tải lại</td>
 *     <td><b>Bác bỏ.</b> Ba service = ba lần tải thêm cùng một trang. Vừa phá
 *         chính sách lịch sự mà {@code UrlFrontier} dựng ra để giữ, vừa có
 *         nguy cơ nội dung đã đổi giữa hai lần tải nên hai service nhìn thấy
 *         hai trang khác nhau.</td>
 *   </tr>
 *   <tr>
 *     <td>Crawler bóc sẵn link + ảnh rồi gửi danh sách</td>
 *     <td><b>Bác bỏ.</b> Khi đó Modular Service không còn là service — phần
 *         việc thật đã nằm lại trong crawler, và thêm một service thứ tư cần
 *         dữ liệu khác lại phải sửa crawler. Đúng thứ kiến trúc này sinh ra
 *         để tránh.</td>
 *   </tr>
 *   <tr>
 *     <td><b>Gửi HTML thô</b> (đang dùng)</td>
 *     <td>Thông điệp lớn — trung bình 80&nbsp;KB, đuôi phân bố tới vài trăm
 *         KB. Bù lại: tải đúng một lần, service nào cũng thấy cùng một bản,
 *         và thêm service mới không phải chạm vào crawler.</td>
 *   </tr>
 * </table>
 *
 * <p><b>Cái giá được trả bằng nén.</b> Producer bật {@code compression.type=lz4}
 * (xem {@code KafkaConfig}). HTML là văn bản lặp rất nhiều nên tỷ lệ nén thực
 * đo được vào khoảng 6–8 lần: 80&nbsp;KB xuống còn khoảng 11&nbsp;KB trên
 * đường truyền và trên đĩa. Nén ở tầng producer chứ không phải tầng ứng dụng
 * vì Kafka nén theo <i>lô</i>, và nhiều trang cùng một site chia nhau rất
 * nhiều chuỗi giống nhau — nén cả lô ăn đứt nén từng thông điệp.
 *
 * <p><b>Trần kích thước.</b> {@code max.request.size} được nâng lên 4&nbsp;MB.
 * Trang vượt trần sẽ khiến producer ném lỗi; {@code KafkaCrawlEventBus} bắt
 * và bỏ trang đó kèm một dòng cảnh báo, thay vì để một trang dị thường làm
 * chết cả phiên crawl.
 *
 * <h2>Vì sao là record chứ không phải lớp thường</h2>
 *
 * <p>Thông điệp đi qua mạng phải <b>bất biến</b>: nó được nhiều consumer đọc
 * song song, và một service sửa đổi thông điệp sẽ làm hỏng service khác theo
 * cách không lần ra được. {@code record} ép tính bất biến ngay ở mức ngôn ngữ,
 * và sinh sẵn {@code equals}/{@code hashCode} cho test so sánh.
 *
 * @param url           URL đã chuẩn hoá của trang
 * @param host          host tách sẵn — cũng chính là <b>khoá phân hoạch</b> Kafka
 * @param depth         độ sâu BFS tại thời điểm trang được tải
 * @param title         tiêu đề do {@code ContentParser} bóc
 * @param bodyText      văn bản thân bài, để Analytics đo mà không phải phân tích lại DOM
 * @param language      mã ngôn ngữ do {@code LanguageFilter} kết luận
 * @param html          HTML thô — nguồn dữ liệu của URL Extractor và Image Download
 * @param contentHash   vân tay SHA-256 mà {@code ContentSeenFilter} đã tính
 * @param crawledAt     thời điểm tải xong
 * @param jobId         phiên crawl đã sinh ra trang này — xem phần dưới
 */
/*
 * VÌ SAO CẦN jobId.
 *
 * Ở chế độ in-process, "phiên crawl nào" là câu hỏi vô nghĩa: chỉ có một
 * CrawlerService và nó đang gọi chính nó. Ở chế độ Kafka thì khác hẳn — URL
 * do URL Extractor bóc ra đi vòng qua broker rồi mới quay lại, và lúc quay lại
 * thì có thể đang có HAI phiên crawl chạy song song (CrawlJobManager cho phép
 * MAX_CONCURRENT_JOBS = 2). Không có trường này thì bên nhận phải đoán, và
 * đoán sai nghĩa là URL của phiên A rơi vào frontier của phiên B — tức phiên B
 * đi crawl những domain mà người dùng chỉ định cho phiên A.
 *
 * Đây là loại lỗi chỉ xuất hiện khi chạy thật, dưới tải, với nhiều job — đúng
 * loại khó truy nhất. Một trường chuỗi là cái giá rẻ để loại bỏ nó.
 */
public record PageEvent(
        String url,
        String host,
        int depth,
        String title,
        String bodyText,
        String language,
        String html,
        String contentHash,
        Instant crawledAt,
        String jobId) {

    /**
     * Kiểm tra bất biến ngay tại chỗ tạo.
     *
     * <p>Một thông điệp thiếu {@code url} hoặc {@code host} là thông điệp
     * không định tuyến được: Kafka phân hoạch theo {@code host}, và mọi
     * service đều dùng {@code url} làm gốc để phân giải liên kết tương đối.
     * Để nó lọt lên bus thì lỗi chỉ lộ ra ở phía consumer — xa chỗ sinh lỗi,
     * và thường là lúc 3 giờ sáng.
     */
    public PageEvent {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("PageEvent.url không được rỗng");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("PageEvent.host không được rỗng, url=" + url);
        }
        if (depth < 0) {
            throw new IllegalArgumentException("PageEvent.depth phải >= 0, nhận được: " + depth);
        }
    }

    /**
     * Kích thước HTML tính bằng byte UTF-8 — số liệu cho Analytics Service và
     * cho việc kiểm tra trần kích thước thông điệp.
     *
     * <p>{@code @JsonIgnore}: đây là giá trị <b>dẫn xuất</b> từ {@code html}.
     * Ghi nó vào thông điệp thì vừa thừa vừa tạo ra một trường có thể lệch với
     * nguồn của nó. Xem {@link ImageFound#isDownloaded()} về ca mà việc quên
     * chú giải này làm chết cả một luồng.
     */
    @JsonIgnore
    public int htmlSizeBytes() {
        return html == null ? 0 : html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Bản rút gọn <b>không kèm HTML</b>, dùng khi ghi log hoặc khi một
     * consumer chỉ cần siêu dữ liệu.
     *
     * <p>Có hàm này để không ai phải viết {@code log.info("{}", event)} rồi
     * vô tình đổ 80 KB HTML vào tệp log — một lỗi rẻ tiền nhưng đủ để làm đầy
     * ổ đĩa của máy chạy log trong một phiên crawl.
     */
    public PageEvent withoutHtml() {
        return new PageEvent(url, host, depth, title, bodyText, language, null,
                contentHash, crawledAt, jobId);
    }

    @Override
    public String toString() {
        return "PageEvent{url='" + url + "', host='" + host + "', depth=" + depth
                + ", htmlBytes=" + htmlSizeBytes() + ", lang='" + language + "'}";
    }
}
