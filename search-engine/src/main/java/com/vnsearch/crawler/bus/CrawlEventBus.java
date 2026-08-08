package com.vnsearch.crawler.bus;

/**
 * Đường đi của mọi sự kiện giữa crawler và cụm <b>Modular Services</b> — ô
 * {@code Kafka} trong sơ đồ kiến trúc, nhìn từ phía mã nguồn.
 *
 * <p><b>Strategy pattern.</b> Có đúng hai bản cài:
 *
 * <table border="1">
 *   <caption>Hai bản cài và chỗ dùng</caption>
 *   <tr><th>Bản cài</th><th>Dùng khi</th><th>Đặc điểm</th></tr>
 *   <tr>
 *     <td>{@link InProcessCrawlEventBus}</td>
 *     <td>Mặc định: chạy dòng lệnh, chạy test, demo một máy</td>
 *     <td>Gọi thẳng handler. Không broker, không serialize, không mạng.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code KafkaCrawlEventBus}</td>
 *     <td>{@code app.crawler.bus=kafka}: nhiều tiến trình, nhiều máy</td>
 *     <td>Ghi vào topic. Service chạy ở tiến trình khác, co giãn riêng.</td>
 *   </tr>
 * </table>
 *
 * <h2>Vì sao phải có bản in-process, thay vì bắt mọi thứ đi qua Kafka</h2>
 *
 * <p>Ba lý do, và lý do thứ ba là lý do thật:
 *
 * <ol>
 *   <li><b>Test chạy được mà không cần broker.</b> Nếu bus chỉ có bản Kafka
 *       thì mỗi bài test đụng tới crawler đều phải dựng một broker — khoảng
 *       15 giây mỗi lần. Một bộ test chậm là một bộ test không được chạy.</li>
 *   <li><b>Đồ án vẫn chạy được trên một máy.</b> Người chấm gõ
 *       {@code run-crawl.bat} phải thấy nó chạy, không phải thấy một thông
 *       báo "không kết nối được tới localhost:9092".</li>
 *   <li><b>Nó ép kiến trúc phải đúng.</b> Muốn cùng một
 *       {@code UrlExtractorService} chạy được ở cả hai chế độ thì service
 *       không được phép biết gì về Kafka — không {@code ConsumerRecord},
 *       không {@code Acknowledgment}, không chú giải. Ràng buộc đó đẩy toàn
 *       bộ phần dính broker ra rìa, vào mấy lớp chuyển tiếp mỏng. Kết quả là
 *       phần lõi kiểm thử được bằng JUnit thuần.</li>
 * </ol>
 *
 * <p>Nói cách khác: bản in-process không phải là "bản rút gọn cho người
 * nghèo". Nó là công cụ giữ cho phần lõi sạch khỏi hạ tầng.
 *
 * <h2>Ngữ nghĩa giao hàng</h2>
 *
 * <p>Mọi phương thức ở đây <b>không ném ngoại lệ</b> khi việc gửi thất bại.
 * Một trang không đẩy được lên bus là một trang bị mất khỏi các service phái
 * sinh — đáng ghi log và đáng đếm, nhưng không đáng làm chết cả phiên crawl
 * đang chạy tốt. Bản cài phải tự ghi log và tự đếm; xem
 * {@link #getPublishFailureCount()}.
 *
 * <p><b>Ngược lại</b>, lỗi <i>xử lý</i> ở phía consumer thì được ném ra: ở
 * chế độ Kafka nó kích hoạt cơ chế thử lại rồi đẩy sang dead-letter topic, và
 * đó là hành vi đúng — thông điệp không mất, chỉ chuyển sang chỗ chờ người
 * xem.
 */
public interface CrawlEventBus {

    /**
     * Đẩy một trang đã sạch lên bus, cho <b>toàn bộ</b> Modular Services.
     *
     * <p>Đây là điểm phát tán một-tới-nhiều: một lời gọi, ba service nhận.
     * Thêm service thứ tư không cần sửa dòng nào ở đây.
     */
    void publishPage(PageEvent event);

    /**
     * Đẩy một URL đã qua lọc, để nạp vào {@code URL Frontier}.
     *
     * <p>Do {@code URL Extractor} gọi, khép lại vòng lặp của sơ đồ.
     */
    void publishDiscoveredUrl(DiscoveredUrl url);

    /**
     * Gửi ngược danh sách liên kết ra về cho {@code Content Storage}, để
     * PageRank có đồ thị đầy đủ. Xem {@link OutlinksExtracted} về việc vì sao
     * tập này khác tập {@link DiscoveredUrl}.
     */
    void publishOutlinks(OutlinksExtracted outlinks);

    /** Đẩy một ảnh tìm được — đầu ra của {@code Image Download Service}. */
    void publishImage(ImageFound image);

    /**
     * Số lần gửi thất bại kể từ lúc khởi tạo.
     *
     * <p>Có mặt trong interface chứ không chỉ ở bản Kafka: một con số luôn
     * đọc được, bất kể đang chạy chế độ nào, là thứ để dựng thang đo và cảnh
     * báo mà không phải kiểm tra kiểu thật của đối tượng.
     */
    long getPublishFailureCount();

    /**
     * Bus rỗng — nhận mọi thứ rồi vứt đi.
     *
     * <p>Dùng cho các bài test chỉ quan tâm tới phần crawl và không muốn dựng
     * handler nào. Tồn tại để không ai phải truyền {@code null} rồi rải
     * {@code if (bus != null)} khắp {@code CrawlerService} — một tham chiếu
     * {@code null} tuỳ chọn là nguồn lỗi kinh điển, còn đối tượng rỗng thì
     * không bao giờ ném NPE. (Null Object pattern.)
     */
    static CrawlEventBus noop() {
        return new CrawlEventBus() {
            @Override public void publishPage(PageEvent event) { /* vứt */ }
            @Override public void publishDiscoveredUrl(DiscoveredUrl url) { /* vứt */ }
            @Override public void publishOutlinks(OutlinksExtracted outlinks) { /* vứt */ }
            @Override public void publishImage(ImageFound image) { /* vứt */ }
            @Override public long getPublishFailureCount() { return 0L; }
        };
    }
}
