package com.vnsearch.crawler.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bản cài {@link CrawlEventBus} chạy trên <b>Kafka</b> — ô {@code Kafka} của
 * sơ đồ kiến trúc, ở dạng thật.
 *
 * <p>Khác biệt duy nhất so với {@link InProcessCrawlEventBus}, nhìn từ phía
 * {@code CrawlerService}: không có. Cùng bốn phương thức, cùng ngữ nghĩa
 * "không ném ngoại lệ khi gửi hỏng". Toàn bộ phần Kafka nằm gọn trong lớp
 * này và trong các lớp listener.
 *
 * <h2>Khoá phân hoạch: luôn là {@code host}</h2>
 *
 * <p>Cả bốn topic đều dùng {@code host} làm khoá. Đây không phải chi tiết tuỳ
 * tiện mà là <b>bất biến trung tâm</b> của thiết kế phân tán này:
 *
 * <ul>
 *   <li>Kafka bảo đảm thông điệp cùng khoá luôn vào cùng một phân hoạch;</li>
 *   <li>một phân hoạch chỉ được giao cho <b>một</b> consumer trong một group;</li>
 *   <li>nên mọi trang và mọi URL của một host luôn về <b>đúng một</b> tiến trình.</li>
 * </ul>
 *
 * <p>Từ đó suy ra hai tính chất mà nếu không có thì phải xây cả một hệ thống
 * điều phối phân tán mới có được: bộ lọc Bloom chống trùng theo host là
 * <i>đầy đủ</i>, và chính sách lịch sự 1 giây/host là <i>chính xác</i>. Xem
 * Javadoc của {@link DiscoveredUrl} để biết ba phương án đã cân nhắc.
 *
 * <p><b>Hệ quả phải nhớ:</b> đổi số phân hoạch của một topic đang chạy sẽ phá
 * bất biến này — hàm băm của Kafka là {@code murmur2(key) % numPartitions},
 * nên đổi mẫu số là đổi chỗ của mọi host. Đó là lý do
 * {@code app.crawler.kafka.partitions} đặt sẵn 12 thay vì 3.
 *
 * <h2>Gửi bất đồng bộ, nhưng có đếm lỗi</h2>
 *
 * <p>{@code KafkaTemplate.send} trả về ngay và ghi vào bộ đệm của producer;
 * lỗi thật (broker chết, thông điệp vượt trần kích thước) chỉ lộ ra ở callback.
 * Chờ kết quả bằng {@code .get()} sẽ biến mỗi trang thành một vòng chờ mạng và
 * giết thông lượng crawler.
 *
 * <p>Nên ở đây: gửi rồi gắn callback đếm lỗi. Một trang không lên được bus là
 * một trang mất khỏi các service phái sinh — đáng cảnh báo, không đáng làm
 * chết phiên crawl. Cảnh báo được dựng trên
 * {@code vnsearch_crawl_bus_publish_failures_total}.
 */
public class KafkaCrawlEventBus implements CrawlEventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaCrawlEventBus.class);

    private final KafkaTemplate<String, Object> template;
    private final String pagesTopic;
    private final String urlsTopic;
    private final String outlinksTopic;
    private final String imagesTopic;

    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong pagesPublished = new AtomicLong();
    private final AtomicLong urlsPublished = new AtomicLong();

    public KafkaCrawlEventBus(KafkaTemplate<String, Object> template, String pagesTopic,
                               String urlsTopic, String outlinksTopic, String imagesTopic) {
        if (template == null) {
            throw new IllegalArgumentException("KafkaCrawlEventBus cần một KafkaTemplate");
        }
        this.template = template;
        this.pagesTopic = require(pagesTopic, "pagesTopic");
        this.urlsTopic = require(urlsTopic, "urlsTopic");
        this.outlinksTopic = require(outlinksTopic, "outlinksTopic");
        this.imagesTopic = require(imagesTopic, "imagesTopic");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Thiếu tên topic: " + name);
        }
        return value;
    }

    @Override
    public void publishPage(PageEvent event) {
        if (event == null) {
            return;
        }
        pagesPublished.incrementAndGet();
        send(pagesTopic, event.host(), event, event.url());
    }

    @Override
    public void publishDiscoveredUrl(DiscoveredUrl url) {
        if (url == null) {
            return;
        }
        urlsPublished.incrementAndGet();
        send(urlsTopic, url.host(), url, url.url());
    }

    @Override
    public void publishOutlinks(OutlinksExtracted outlinks) {
        if (outlinks == null) {
            return;
        }
        send(outlinksTopic, outlinks.host(), outlinks, outlinks.sourceUrl());
    }

    @Override
    public void publishImage(ImageFound image) {
        if (image == null) {
            return;
        }
        send(imagesTopic, image.host(), image, image.imageUrl());
    }

    /**
     * Gửi một thông điệp, đếm lỗi ở callback.
     *
     * <p>{@code try/catch} bọc cả lời gọi {@code send} vì nó <i>vẫn</i> ném
     * đồng bộ trong vài ca: thông điệp vượt {@code max.request.size} (một
     * trang HTML dị thường), hoặc bộ đệm producer đầy và hết thời gian chờ.
     * Chỉ dựa vào callback là bỏ sót đúng những ca đó.
     *
     * @param subject thứ để ghi vào log — URL, không phải cả thông điệp
     */
    private void send(String topic, String key, Object payload, String subject) {
        try {
            template.send(topic, key, payload).whenComplete((result, error) -> {
                if (error != null) {
                    publishFailures.incrementAndGet();
                    log.warn("Không gửi được lên topic {} (khoá {}), đối tượng {}: {}",
                            topic, key, subject, error.toString());
                }
            });
        } catch (Exception e) {
            publishFailures.incrementAndGet();
            log.warn("Không gửi được lên topic {} (khoá {}), đối tượng {}: {}",
                    topic, key, subject, e.toString());
        }
    }

    @Override
    public long getPublishFailureCount() {
        return publishFailures.get();
    }

    public long getPagesPublishedCount() {
        return pagesPublished.get();
    }

    public long getUrlsPublishedCount() {
        return urlsPublished.get();
    }
}
