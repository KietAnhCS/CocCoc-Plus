package com.vnsearch.crawler.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test tích hợp với một broker Kafka <b>thật</b>, dựng bằng Testcontainers.
 *
 * <p><b>Không chạy trong {@code mvnw test} thường ngày</b> — xem
 * {@code excludedGroups} trong pom.xml. Chạy bằng:
 *
 * <pre>./mvnw verify -Pkafka-it</pre>
 *
 * <h2>Ba thứ chỉ một broker thật mới kiểm được</h2>
 *
 * <p>Bộ test in-process đã phủ toàn bộ logic của ba Modular Service, và nó
 * phủ tốt hơn nhiều vì chạy nhanh. Nhưng có ba thứ nó <b>về nguyên tắc</b>
 * không thể kiểm:
 *
 * <ol>
 *   <li><b>Thông điệp có serialize được không.</b> Ở chế độ in-process, đối
 *       tượng đi thẳng từ tay này sang tay kia — không ai kiểm tra Jackson có
 *       ghi nổi {@link Instant} hay không. Thiếu {@link JavaTimeModule} thì
 *       mọi thứ vẫn xanh trong bộ test cũ và hỏng ở thông điệp đầu tiên khi
 *       chạy thật.</li>
 *   <li><b>Phân hoạch theo host có thật sự ổn định không.</b> Đây là bất biến
 *       trung tâm của cả thiết kế phân tán (xem {@link DiscoveredUrl}); nó là
 *       tính chất của <i>Kafka</i>, không phải của mã ta viết, nên chỉ Kafka
 *       mới chứng minh được.</li>
 *   <li><b>Thông điệp lớn có qua được không.</b> Trần
 *       {@code max.request.size} phải khớp giữa producer và broker, và sự
 *       lệch đó chỉ lộ ra khi có một broker thật để từ chối.</li>
 * </ol>
 */
@Tag("kafka-it")
class KafkaCrawlBusIT {

    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private static KafkaContainer kafka;
    private static ObjectMapper mapper;

    /**
     * Bộ topic RIÊNG cho từng bài test.
     *
     * <p>Vì sao không dùng chung một bộ tên cố định: mọi consumer ở đây đặt
     * {@code auto.offset.reset=earliest} — bắt buộc, vì nó là hành vi thật của
     * hệ thống (một service mới phải đọc được từ đầu). Nhưng hệ quả là mỗi bài
     * test sẽ đọc luôn những thông điệp mà các bài <b>chạy trước</b> để lại,
     * rồi khẳng định sai về chúng.
     *
     * <p>Đã xảy ra thật: {@code pageEventSurvivesARoundTripThroughKafka} nhận
     * được khoá {@code a.vn} của một bài khác thay vì {@code vnexpress.net}
     * của chính nó.
     *
     * <p>Đổi sang {@code latest} thì lại hỏng theo kiểu khác — có một khoảng
     * đua giữa lúc consumer đăng ký và lúc producer gửi. Tách topic là cách
     * duy nhất vừa giữ đúng hành vi thật vừa cô lập được các bài test.
     */
    private String pagesTopic;
    private String urlsTopic;
    private String outlinksTopic;
    private String imagesTopic;

    @BeforeEach
    void freshTopics() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        pagesTopic = "it-" + uniq + ".pages";
        urlsTopic = "it-" + uniq + ".urls";
        outlinksTopic = "it-" + uniq + ".outlinks";
        imagesTopic = "it-" + uniq + ".images";
    }

    @BeforeAll
    static void startBroker() {
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                // Phải khớp MAX_MESSAGE_BYTES của producer, nếu không bài test
                // về thông điệp lớn sẽ hỏng vì một lý do khác với lý do nó kiểm.
                .withEnv("KAFKA_MESSAGE_MAX_BYTES", String.valueOf(MAX_MESSAGE_BYTES))
                .withEnv("KAFKA_REPLICA_FETCH_MAX_BYTES", String.valueOf(MAX_MESSAGE_BYTES))
                .withEnv("KAFKA_NUM_PARTITIONS", "12")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        kafka.start();

        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @AfterAll
    static void stopBroker() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    private KafkaCrawlEventBus newBus() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, MAX_MESSAGE_BYTES);

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(mapper);
        valueSerializer.setAddTypeInfo(false);

        ProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
        return new KafkaCrawlEventBus(new KafkaTemplate<>(factory),
                pagesTopic, urlsTopic, outlinksTopic, imagesTopic);
    }

    private static KafkaConsumer<String, String> newConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, MAX_MESSAGE_BYTES);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static PageEvent page(String url, String host, String html) {
        return new PageEvent(url, host, 1, "Tiêu đề", "Thân bài tiếng Việt có dấu",
                "vi", html, "hash", Instant.parse("2026-08-08T10:15:30Z"), "job-it");
    }

    /**
     * Vòng đời đầy đủ của một thông điệp: ghi, nén, truyền, đọc lại, dựng lại
     * thành đối tượng Java — và mọi trường phải còn nguyên.
     */
    @Test
    void pageEventSurvivesARoundTripThroughKafka() throws Exception {
        try (KafkaConsumer<String, String> consumer = newConsumer(pagesTopic)) {
            KafkaCrawlEventBus bus = newBus();
            PageEvent sent = page("https://vnexpress.net/bai-1", "vnexpress.net",
                    "<html><body>Xin chào Việt Nam</body></html>");
            bus.publishPage(sent);

            ConsumerRecord<String, String> record = pollOne(consumer);
            assertNotNull(record, "Phải nhận được thông điệp trong 30 giây");

            // Khoá phân hoạch PHẢI là host — cả thiết kế phân tán dựa vào đây.
            assertEquals("vnexpress.net", record.key());

            PageEvent received = mapper.readValue(record.value(), PageEvent.class);
            assertEquals(sent.url(), received.url());
            assertEquals(sent.host(), received.host());
            assertEquals(sent.depth(), received.depth());
            assertEquals(sent.title(), received.title());
            assertEquals(sent.language(), received.language());
            assertEquals(sent.html(), received.html());
            assertEquals(sent.jobId(), received.jobId());

            // Thứ mà bộ test in-process KHÔNG THỂ bắt: thiếu JavaTimeModule thì
            // dòng này ném ngay, và ở môi trường thật nó ném ở thông điệp đầu.
            assertEquals(sent.crawledAt(), received.crawledAt());

            assertEquals(0, bus.getPublishFailureCount());
        }
    }

    /** Tiếng Việt có dấu phải qua được nguyên vẹn — UTF-8 từ đầu tới cuối. */
    @Test
    void vietnameseDiacriticsSurviveSerialization() throws Exception {
        try (KafkaConsumer<String, String> consumer = newConsumer(pagesTopic)) {
            String noiDung = "Đội tuyển Việt Nam thắng 2-0 ở trận đấu tối qua";
            newBus().publishPage(new PageEvent(
                    "https://a.vn/bai", "a.vn", 0, noiDung, noiDung, "vi",
                    "<p>" + noiDung + "</p>", "h", Instant.EPOCH, "job"));

            PageEvent received = mapper.readValue(pollOne(consumer).value(), PageEvent.class);
            assertEquals(noiDung, received.title());
            assertEquals(noiDung, received.bodyText());
        }
    }

    /**
     * BẤT BIẾN TRUNG TÂM: mọi thông điệp cùng host phải vào cùng một phân
     * hoạch.
     *
     * <p>Đây là thứ khiến bộ lọc Bloom chống trùng theo host là <i>đầy đủ</i>
     * và chính sách lịch sự 1 giây/host là <i>chính xác</i> khi chạy nhiều
     * tiến trình — mà không cần một bộ điều phối phân tán nào. Xem Javadoc của
     * {@link DiscoveredUrl}.
     *
     * <p>Nó là tính chất của Kafka chứ không phải của mã ta viết, nên chỉ một
     * broker thật mới chứng minh được.
     */
    @Test
    void allUrlsOfOneHostLandOnTheSamePartition() {
        try (KafkaConsumer<String, String> consumer = newConsumer(urlsTopic)) {
            KafkaCrawlEventBus bus = newBus();
            for (int i = 0; i < 30; i++) {
                bus.publishDiscoveredUrl(new DiscoveredUrl(
                        "https://vnexpress.net/bai-" + i, "vnexpress.net", 1,
                        "https://vnexpress.net", "job-it"));
            }

            Integer phanHoach = null;
            int nhanDuoc = 0;
            long hetHan = System.currentTimeMillis() + 30_000;
            while (nhanDuoc < 30 && System.currentTimeMillis() < hetHan) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (phanHoach == null) {
                        phanHoach = r.partition();
                    }
                    assertEquals(phanHoach, r.partition(),
                            "MỌI URL của cùng một host phải vào cùng một phân hoạch");
                    nhanDuoc++;
                }
            }
            assertEquals(30, nhanDuoc);
        }
    }

    /** Hai host khác nhau thì được phép — và nên — nằm trên hai phân hoạch. */
    @Test
    void differentHostsSpreadAcrossPartitions() {
        try (KafkaConsumer<String, String> consumer = newConsumer(urlsTopic)) {
            KafkaCrawlEventBus bus = newBus();
            List<String> hosts = List.of("a.vn", "b.vn", "c.vn", "d.vn", "e.vn",
                    "f.vn", "g.vn", "h.vn");
            for (String host : hosts) {
                bus.publishDiscoveredUrl(new DiscoveredUrl(
                        "https://" + host + "/x", host, 1, "https://" + host, "job"));
            }

            java.util.Set<Integer> phanHoach = new java.util.HashSet<>();
            int nhanDuoc = 0;
            long hetHan = System.currentTimeMillis() + 30_000;
            while (nhanDuoc < hosts.size() && System.currentTimeMillis() < hetHan) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    phanHoach.add(r.partition());
                    nhanDuoc++;
                }
            }
            assertEquals(hosts.size(), nhanDuoc);
            assertTrue(phanHoach.size() > 1,
                    "8 host phải trải ra nhiều hơn một phân hoạch, nhận được: " + phanHoach);
        }
    }

    /**
     * Một trang lớn bất thường (2 MB HTML) phải qua được.
     *
     * <p>Mặc định của Kafka là 1 MB. Nếu {@code max.request.size} và
     * {@code message.max.bytes} không được nâng lên cùng nhau thì đúng những
     * trang giàu nội dung nhất bị rớt — và rớt im lặng ở phía producer.
     */
    @Test
    void largePageWithinTheRaisedLimitIsAccepted() throws Exception {
        try (KafkaConsumer<String, String> consumer = newConsumer(pagesTopic)) {
            String htmlLon = "<html><body>" + "nội dung ".repeat(120_000) + "</body></html>";
            assertTrue(htmlLon.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_000_000,
                    "HTML thử nghiệm phải vượt trần mặc định 1 MB của Kafka");

            KafkaCrawlEventBus bus = newBus();
            bus.publishPage(page("https://a.vn/trang-lon", "a.vn", htmlLon));

            ConsumerRecord<String, String> record = pollOne(consumer);
            assertNotNull(record, "Trang lớn phải qua được sau khi đã nâng trần");
            PageEvent received = mapper.readValue(record.value(), PageEvent.class);
            assertEquals(htmlLon.length(), received.html().length());
            assertEquals(0, bus.getPublishFailureCount());
        }
    }

    /**
     * Gửi hỏng KHÔNG được ném ngược về phía crawler — nó chỉ được đếm.
     *
     * <p>Một trang không lên được bus là một trang mất khỏi các service phái
     * sinh: đáng cảnh báo, không đáng làm chết một phiên crawl đang chạy tốt.
     * Đây là hợp đồng mà {@code CrawlEventBus} tuyên bố, và bài test này kiểm
     * nó bằng một thông điệp cố tình vượt trần.
     */
    @Test
    void oversizedMessageIsCountedNotThrown() {
        KafkaCrawlEventBus bus = newBus();
        String quaLon = "x".repeat(MAX_MESSAGE_BYTES + 1_000_000);

        // Không được ném — đây chính là điều đang kiểm.
        bus.publishPage(page("https://a.vn/qua-lon", "a.vn", quaLon));

        long hetHan = System.currentTimeMillis() + 20_000;
        while (bus.getPublishFailureCount() == 0 && System.currentTimeMillis() < hetHan) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(bus.getPublishFailureCount() > 0,
                "Thông điệp vượt trần phải được ĐẾM là lỗi gửi");
    }

    /** Bốn kênh đi đúng bốn topic, không lẫn sang nhau. */
    @Test
    void eachChannelGoesToItsOwnTopic() throws Exception {
        try (KafkaConsumer<String, String> outlinksConsumer = newConsumer(outlinksTopic);
             KafkaConsumer<String, String> imagesConsumer = newConsumer(imagesTopic)) {

            KafkaCrawlEventBus bus = newBus();
            bus.publishOutlinks(new OutlinksExtracted("https://a.vn/bai", "a.vn",
                    List.of("https://a.vn/1", "https://a.vn/2"), "job-it"));
            bus.publishImage(ImageFound.metadataOnly("https://a.vn/bai", "a.vn",
                    "https://a.vn/anh.jpg", "mô tả", 800, 600));

            OutlinksExtracted outlinks =
                    mapper.readValue(pollOne(outlinksConsumer).value(), OutlinksExtracted.class);
            assertEquals(2, outlinks.size());
            assertEquals("job-it", outlinks.jobId());

            ImageFound image =
                    mapper.readValue(pollOne(imagesConsumer).value(), ImageFound.class);
            assertEquals("https://a.vn/anh.jpg", image.imageUrl());
            assertEquals("mô tả", image.altText());
            assertFalse(image.isDownloaded());
        }
    }

    /** Chờ đúng một bản ghi, tối đa 30 giây. */
    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
        long hetHan = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < hetHan) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                return record;
            }
        }
        return null;
    }
}
