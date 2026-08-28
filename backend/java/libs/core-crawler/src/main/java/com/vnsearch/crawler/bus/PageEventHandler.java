package com.vnsearch.crawler.bus;

/**
 * Một <b>Modular Service</b> — thứ tiêu thụ luồng trang đã sạch.
 *
 * <p>Ba lớp cài interface này, đúng ba ô trong cụm {@code Modular Services}
 * của sơ đồ kiến trúc:
 *
 * <pre>
 *                    ┌──────────────────────────┐
 *   [ Kafka ] ──────▶│ CrawlAnalyticsService    │  đo
 *      │             ├──────────────────────────┤
 *      ├────────────▶│ ImageDownloadService     │  ảnh
 *      │             ├──────────────────────────┤
 *      └────────────▶│ UrlExtractorService      │  liên kết ──▶ Frontier
 *                    └──────────────────────────┘
 * </pre>
 *
 * <p><b>Không có gì của Kafka trong chữ ký này</b> — không
 * {@code ConsumerRecord}, không {@code Acknowledgment}, không chú giải. Đó là
 * chủ đích: nhờ vậy cùng một đối tượng service chạy được ở cả chế độ
 * in-process lẫn chế độ Kafka, và test gọi thẳng {@link #onPage} như một
 * phương thức Java bình thường.
 *
 * <p><b>Ném ngoại lệ là hợp lệ.</b> Ở chế độ in-process,
 * {@link InProcessCrawlEventBus} bắt và cô lập để một service hỏng không kéo
 * theo hai service kia. Ở chế độ Kafka, ngoại lệ kích hoạt chuỗi thử lại rồi
 * dead-letter topic. Cả hai chế độ đều coi ngoại lệ là "thông điệp này hỏng",
 * không phải "hệ thống hỏng".
 *
 * <p><b>Bắt buộc thread-safe.</b> Ở chế độ in-process, phương thức này được
 * gọi từ nhiều worker thread của crawler cùng lúc; ở chế độ Kafka, từ nhiều
 * luồng consumer. Bản cài phải tự lo đồng bộ.
 */
@FunctionalInterface
public interface PageEventHandler {

    /**
     * Xử lý một trang đã tải, đã phân tích, đã qua lọc ngôn ngữ và đã vượt
     * khối {@code Duplicate Detection}.
     */
    void onPage(PageEvent event);

    /**
     * Tên hiển thị trong log và trong thang đo.
     *
     * <p>Mặc định là tên lớp. Có thể ghi đè khi một service được tạo bằng
     * lambda — lúc đó tên lớp là một chuỗi sinh tự động dạng
     * {@code Xxx$$Lambda$14/0x...}, vô dụng trong log.
     */
    default String handlerName() {
        return getClass().getSimpleName();
    }
}
