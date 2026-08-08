package com.vnsearch.config;

import com.vnsearch.crawler.bus.DiscoveredUrl;
import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.bus.OutlinksExtracted;
import com.vnsearch.crawler.bus.PageEvent;
import com.vnsearch.crawler.modular.CrawlAnalyticsService;
import com.vnsearch.crawler.modular.ImageDownloadService;
import com.vnsearch.crawler.modular.UrlExtractorService;
import com.vnsearch.service.CrawlJobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Lớp chuyển tiếp giữa Kafka và ba <b>Modular Service</b>.
 *
 * <p><b>Cố ý mỏng đến mức nhàm chán.</b> Mỗi phương thức ở đây làm đúng một
 * việc: nhận một thông điệp đã được dịch sang kiểu Java rồi gọi service. Không
 * có logic nghiệp vụ nào.
 *
 * <p>Đó là toàn bộ chiến lược: nhốt mọi thứ dính tới Kafka vào <i>một</i> lớp
 * không cần test, để ba service kia không biết Kafka tồn tại và test được
 * bằng JUnit thuần. Xem Javadoc của {@code CrawlEventBus} về lý do.
 *
 * <pre>
 *   vnsearch.pages ──┬──▶ onPageForUrlExtractor   (group: url-extractor)
 *                    ├──▶ onPageForImages         (group: image-download)
 *                    └──▶ onPageForAnalytics      (group: analytics)
 *
 *   vnsearch.urls.discovered ──▶ onDiscoveredUrl  (group: frontier-feeder)
 *   vnsearch.outlinks        ──▶ onOutlinks       (group: frontier-feeder)
 *   vnsearch.images          ──▶ onImage          (group: analytics)
 * </pre>
 *
 * <p><b>Ba listener trên cùng một topic, ba {@code groupId} khác nhau</b> —
 * đây là chỗ cơ chế phát tán một-tới-nhiều hiện ra thành mã. Cùng một trang
 * được cả ba nhận, mỗi group giữ offset riêng. Nếu ba listener này dùng chung
 * một {@code groupId} thì mỗi trang chỉ đến <i>một</i> trong ba service, chọn
 * ngẫu nhiên — một lỗi cấu hình một dòng, hậu quả là hai phần ba công việc âm
 * thầm không được làm.
 *
 * <p><b>Ngoại lệ được để bay ra.</b> Không có {@code try/catch} nào ở đây, có
 * chủ đích: {@code DefaultErrorHandler} bắt, thử lại có giãn cách, rồi đẩy
 * sang dead-letter topic. Bắt và nuốt ở đây sẽ vô hiệu hoá toàn bộ cơ chế đó
 * và biến mọi lỗi thành một dòng log.
 */
@Component
@ConditionalOnProperty(name = "app.crawler.bus", havingValue = "kafka")
public class CrawlKafkaListeners {

    private static final Logger log = LoggerFactory.getLogger(CrawlKafkaListeners.class);

    private final UrlExtractorService urlExtractor;
    private final ImageDownloadService imageDownload;
    private final CrawlAnalyticsService analytics;
    private final CrawlJobManager jobManager;

    public CrawlKafkaListeners(UrlExtractorService urlExtractor,
                                ImageDownloadService imageDownload,
                                CrawlAnalyticsService analytics,
                                CrawlJobManager jobManager) {
        this.urlExtractor = urlExtractor;
        this.imageDownload = imageDownload;
        this.analytics = analytics;
        this.jobManager = jobManager;
        log.info("Cac listener Kafka da san sang: 3 Modular Service + 1 bo nap frontier");
    }

    // --- vnsearch.pages: ba consumer group đọc cùng một luồng -----------

    @KafkaListener(
            topics = "${app.crawler.kafka.topic.pages}",
            groupId = "${app.crawler.kafka.group.url-extractor}",
            containerFactory = "crawlListenerContainerFactory")
    public void onPageForUrlExtractor(PageEvent event) {
        urlExtractor.onPage(event);
    }

    @KafkaListener(
            topics = "${app.crawler.kafka.topic.pages}",
            groupId = "${app.crawler.kafka.group.image-download}",
            containerFactory = "crawlListenerContainerFactory")
    public void onPageForImages(PageEvent event) {
        imageDownload.onPage(event);
    }

    @KafkaListener(
            topics = "${app.crawler.kafka.topic.pages}",
            groupId = "${app.crawler.kafka.group.analytics}",
            containerFactory = "crawlListenerContainerFactory")
    public void onPageForAnalytics(PageEvent event) {
        analytics.onPage(event);
    }

    // --- Vòng lặp khép về URL Frontier ---------------------------------

    /**
     * Nạp một URL đã qua lọc trở lại {@code URL Frontier} — mũi tên đóng vòng
     * lặp trong sơ đồ kiến trúc.
     *
     * <p>Chuyển tiếp qua {@link CrawlJobManager} chứ không giữ tham chiếu
     * thẳng tới một {@code CrawlerService}: crawler được cấp phát <b>theo từng
     * job</b> và job thì đến rồi đi, còn listener này sống suốt vòng đời ứng
     * dụng. Giữ tham chiếu thẳng nghĩa là giữ mãi crawler của job đầu tiên —
     * kèm toàn bộ corpus của nó trong bộ nhớ, đúng rò rỉ mà
     * {@code CrawlJobManager.releaseCrawler()} đã phải sửa một lần.
     */
    @KafkaListener(
            topics = "${app.crawler.kafka.topic.urls}",
            groupId = "${app.crawler.kafka.group.frontier}",
            containerFactory = "crawlListenerContainerFactory")
    public void onDiscoveredUrl(DiscoveredUrl url) {
        jobManager.feedDiscoveredUrl(url);
    }

    @KafkaListener(
            topics = "${app.crawler.kafka.topic.outlinks}",
            groupId = "${app.crawler.kafka.group.frontier}",
            containerFactory = "crawlListenerContainerFactory")
    public void onOutlinks(OutlinksExtracted outlinks) {
        jobManager.feedOutlinks(outlinks);
    }

    // --- Đầu ra của Image Download về Analytics ------------------------

    @KafkaListener(
            topics = "${app.crawler.kafka.topic.images}",
            groupId = "${app.crawler.kafka.group.analytics}",
            containerFactory = "crawlListenerContainerFactory")
    public void onImage(ImageFound image) {
        analytics.onImage(image);
    }
}
