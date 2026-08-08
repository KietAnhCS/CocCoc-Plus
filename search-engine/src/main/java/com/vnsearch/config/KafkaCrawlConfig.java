package com.vnsearch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vnsearch.crawler.DnsResolver;
import com.vnsearch.crawler.LinkExtractor;
import com.vnsearch.crawler.UrlFilter;
import com.vnsearch.crawler.UrlSeenFilter;
import com.vnsearch.crawler.bus.CrawlEventBus;
import com.vnsearch.crawler.bus.KafkaCrawlEventBus;
import com.vnsearch.crawler.modular.CrawlAnalyticsService;
import com.vnsearch.crawler.modular.ImageDownloadService;
import com.vnsearch.crawler.modular.UrlExtractorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Nối dây toàn bộ phần Kafka — <b>chỉ khi</b> {@code app.crawler.bus=kafka}.
 *
 * <p>Cả lớp này nằm sau một {@link ConditionalOnProperty}. Nghĩa là ở cấu hình
 * mặc định, không một bean Kafka nào được tạo, không một kết nối nào được mở,
 * và ứng dụng khởi động y như trước khi thêm Kafka. Điều đó quan trọng hơn vẻ
 * ngoài của nó: một dự án mà "chạy thử" đòi hỏi dựng broker trước là một dự án
 * không ai chạy thử.
 *
 * <h2>Bản đồ topic</h2>
 *
 * <pre>
 *   CrawlerService
 *        │ publishPage
 *        ▼
 *   ┌─────────────────┐   3 consumer group đọc CÙNG một topic
 *   │ vnsearch.pages  │──┬──▶ vnsearch-url-extractor
 *   └─────────────────┘  ├──▶ vnsearch-image-download
 *                        └──▶ vnsearch-analytics
 *                                   │
 *        ┌──────────────────────────┼──────────────────┐
 *        ▼                          ▼                  ▼
 *   vnsearch.urls.discovered   vnsearch.outlinks   vnsearch.images
 *        │                          │                  │
 *        ▼                          ▼                  ▼
 *   URL Frontier              Content Storage      Analytics
 *   (vong lap crawl)          (cho PageRank)
 * </pre>
 *
 * <p><b>Ba consumer group trên một topic</b> chính là cơ chế phát tán
 * một-tới-nhiều mà lập luận chọn Kafka dựa vào (xem chú thích trong
 * {@code pom.xml}). Mỗi group giữ offset riêng, nên cả ba đều nhận trọn vẹn
 * luồng trang, và một service chậm không chặn hai service kia.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.crawler.bus", havingValue = "kafka")
public class KafkaCrawlConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaCrawlConfig.class);

    /**
     * Trần kích thước một thông điệp: 4 MB.
     *
     * <p>Mặc định của Kafka là 1 MB, và {@link com.vnsearch.crawler.bus.PageEvent}
     * mang HTML thô — phần lớn trang nằm dưới 200 KB nhưng đuôi phân bố chạm
     * vài MB (trang lưu trữ, trang có bảng khổng lồ). Với trần 1 MB thì đúng
     * những trang giàu nội dung nhất bị rớt.
     *
     * <p>Giá trị này phải khớp với {@code message.max.bytes} ở phía broker,
     * nếu không producer gửi được mà broker từ chối — xem docker-compose.yml.
     */
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    @Value("${app.crawler.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${app.crawler.kafka.topic.pages}")
    private String pagesTopic;

    @Value("${app.crawler.kafka.topic.urls}")
    private String urlsTopic;

    @Value("${app.crawler.kafka.topic.outlinks}")
    private String outlinksTopic;

    @Value("${app.crawler.kafka.topic.images}")
    private String imagesTopic;

    @Value("${app.crawler.kafka.partitions:12}")
    private int partitions;

    @Value("${app.crawler.kafka.replication-factor:1}")
    private short replicationFactor;

    // --- Topic ---------------------------------------------------------

    /**
     * Bộ quản trị tạo các topic bên dưới — <b>bắt buộc phải khai ở đây</b>.
     *
     * <h3>Vì sao không để Spring Boot tự lo</h3>
     *
     * <p>{@code KafkaAutoConfiguration} có tạo sẵn một {@link KafkaAdmin},
     * nhưng nó đọc địa chỉ broker từ {@code spring.kafka.bootstrap-servers}.
     * Cấu hình của dự án này lại nằm ở
     * {@code app.crawler.kafka.bootstrap-servers} — mọi khoá đều dưới tiền tố
     * {@code app.} để một chỗ duy nhất mô tả hành vi ứng dụng.
     *
     * <p>Hệ quả nếu thiếu bean này: {@code KafkaAdmin} mặc định trỏ về
     * {@code localhost:9092}. Trong container thì {@code localhost} là chính
     * container đó, nơi không có broker nào. Nên <b>không topic nào được
     * tạo</b>.
     *
     * <p>Và đây là phần tệ nhất: {@code fatalIfBrokerNotAvailable} mặc định
     * {@code false}, nên ứng dụng <b>vẫn khởi động bình thường</b>. Triệu
     * chứng quan sát được là:
     *
     * <pre>
     *   docker ps          -> crawler-worker: healthy
     *   log                -> sáu consumer group đăng ký thành công
     *   log                -> UNKNOWN_TOPIC_OR_PARTITION, lặp mãi ở mức WARN
     *   kafka-topics --list -> chỉ có __consumer_offsets
     * </pre>
     *
     * <p>Tức là: mọi thứ trông như đang chạy, nhưng không một trang nào đi qua
     * được bus. Lỗi này đã xảy ra thật ở lần chạy Docker đầu tiên, và không
     * bài test nào bắt được — cả bộ test tích hợp cũng không, vì ở đó topic
     * được tạo tự động bởi cấu hình của Testcontainers.
     *
     * <h3>Vì sao {@code fatalIfBrokerNotAvailable(true)}</h3>
     *
     * <p>Đặt {@code app.crawler.bus=kafka} là một <b>tuyên bố có chủ đích</b>:
     * người vận hành nói rằng hệ thống này chạy phân tán. Nếu broker không có
     * thật thì ứng dụng phải <b>từ chối khởi động</b>, chứ không phải chạy
     * tiếp trong một trạng thái nửa vời mà mọi thang đo đều xanh.
     *
     * <p>Cùng triết lý mà {@code SecurityConfig} đã chọn khi thiếu
     * {@code ADMIN_API_KEY}, và mà {@code docker-compose.yml} áp ở tầng điều
     * phối: <i>hỏng to hơn hỏng âm thầm</i>.
     *
     * <p>An toàn trong thực tế vì cả compose lẫn Kubernetes đều buộc chờ
     * Kafka {@code healthy} trước khi khởi động tiến trình này.
     */
    @Bean
    public KafkaAdmin crawlKafkaAdmin() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(props);
        admin.setFatalIfBrokerNotAvailable(true);
        return admin;
    }

    /**
     * Topic trang — luồng chính, và cũng là topic tốn đĩa nhất.
     *
     * <p>{@code retention.ms} 7 ngày chứ không phải mặc định 7 ngày cho vui:
     * đó là cửa sổ để <b>chạy lại</b>. Sửa luật lọc URL rồi tua offset về đầu
     * là bóc lại toàn bộ liên kết của một tuần crawl, không phải tải lại trang
     * nào. Đây đúng là năng lực mà một hàng đợi công việc không có, và là một
     * nửa lý do chọn Kafka.
     *
     * <p>{@code compression.type=producer} giữ nguyên nén lz4 mà producer đã
     * làm, thay vì giải nén rồi nén lại ở broker — tiết kiệm CPU của broker.
     */
    @Bean
    public NewTopic pagesTopic() {
        return TopicBuilder.name(pagesTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .config("compression.type", "producer")
                .config("max.message.bytes", String.valueOf(MAX_MESSAGE_BYTES))
                .build();
    }

    @Bean
    public NewTopic urlsTopic() {
        return TopicBuilder.name(urlsTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic outlinksTopic() {
        return TopicBuilder.name(outlinksTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic imagesTopic() {
        return TopicBuilder.name(imagesTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    /**
     * Dead-letter topic: nơi thông điệp đi sau khi đã thử lại mà vẫn hỏng.
     *
     * <p>Vì sao phải có, thay vì cứ ghi log rồi bỏ qua: một thông điệp bị bỏ
     * qua là một trang biến mất không dấu vết. Đưa nó sang một topic riêng thì
     * nó vẫn còn nguyên, đếm được, xem được, và <b>phát lại được</b> sau khi
     * sửa lỗi. Cảnh báo {@code VnSearchDeadLetterGrowing} theo dõi chính topic
     * này.
     *
     * <p>Một phân hoạch là đủ: nếu topic này có lưu lượng đáng kể thì vấn đề
     * nằm ở chỗ khác chứ không phải ở thông lượng của nó.
     */
    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name(pagesTopic + ".DLT")
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    // --- Producer ------------------------------------------------------

    /**
     * Producer: bền, không sinh bản trùng, và nén mạnh.
     *
     * <table border="1">
     *   <caption>Vì sao từng tham số</caption>
     *   <tr><th>Tham số</th><th>Lý do</th></tr>
     *   <tr>
     *     <td>{@code acks=all}</td>
     *     <td>Chờ mọi bản sao trong ISR xác nhận. Với {@code acks=1}, một
     *         broker chết ngay sau khi nhận là mất thông điệp. Crawler tải một
     *         trang mất cả giây — mất nó rồi tải lại đắt hơn nhiều so với chờ
     *         thêm vài mili-giây.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code enable.idempotence=true}</td>
     *     <td>Producer tự thử lại khi mạng chớp, và nếu không có cờ này thì
     *         lần thử lại sinh ra một BẢN TRÙNG. Bản trùng ở topic trang nghĩa
     *         là một trang bị bóc liên kết hai lần và đếm hai lần.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code compression.type=lz4}</td>
     *     <td>HTML lặp rất nhiều nên nén được 6–8 lần. lz4 chứ không phải gzip:
     *         nhanh hơn nhiều với tỷ lệ nén chỉ kém chút, và producer nằm trên
     *         đường nóng của crawler.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code linger.ms=20}</td>
     *     <td>Chờ 20 ms để gom lô. Kafka nén theo LÔ, và nhiều trang cùng một
     *         site chia nhau rất nhiều chuỗi giống nhau — gom lô làm tỷ lệ nén
     *         tốt hơn hẳn nén từng thông điệp. 20 ms là độ trễ không đáng kể so
     *         với một lượt tải trang.</td>
     *   </tr>
     * </table>
     */
    /**
     * {@link ObjectMapper} dùng chung cho cả producer lẫn consumer.
     *
     * <p><b>Bắt buộc phải có {@link JavaTimeModule}.</b> {@code PageEvent} có
     * một trường {@link java.time.Instant}, và Jackson mặc định <i>không</i>
     * biết kiểu đó — nó sẽ ném {@code InvalidDefinitionException} ngay ở thông
     * điệp đầu tiên. Cấu hình sai ở đây thì lỗi chỉ lộ ra lúc chạy thật, nên
     * để hai bên dùng chung một bean là cách chắc chắn chúng không lệch nhau.
     *
     * <p>{@code WRITE_DATES_AS_TIMESTAMPS} tắt để thời điểm ghi ra dạng ISO-8601
     * — đọc được bằng mắt khi soi thông điệp trong kafka-ui, và cùng định dạng
     * với corpus JSON mà {@code ContentStorage} ghi.
     */
    @Bean
    public ObjectMapper crawlEventObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public ProducerFactory<String, Object> crawlProducerFactory(ObjectMapper crawlEventObjectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, MAX_MESSAGE_BYTES);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);

        // Không ghi thông tin kiểu vào header. Consumer đã biết mình chờ kiểu
        // gì — nó khai ngay trong chữ ký @KafkaListener. Ghi thêm chỉ buộc
        // chặt hai bên vào cùng tên gói Java, nên đổi tên gói là hỏng cả luồng
        // đang chạy, kể cả những thông điệp đã nằm sẵn trên topic.
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(crawlEventObjectMapper);
        valueSerializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    /**
     * Consumer đọc thông điệp dưới dạng <b>chuỗi</b>, việc dịch sang kiểu Java
     * do {@link StringJsonMessageConverter} lo.
     *
     * <p>Vì sao không dùng thẳng {@code JsonDeserializer}: nó cần biết trước
     * kiểu đích, mà ở đây bốn topic mang bốn kiểu khác nhau qua cùng một nhà
     * máy container. Bộ chuyển đổi thì lấy kiểu từ <b>chữ ký phương thức</b>
     * của từng {@code @KafkaListener}, nên mỗi listener tự khai kiểu của mình
     * và không có một cấu hình toàn cục nào phải giữ đồng bộ.
     *
     * <p>{@code auto.offset.reset=earliest}: một consumer group mới phải đọc
     * từ đầu topic. Với {@code latest} (mặc định của Kafka), khởi động một
     * service mới sẽ bỏ qua toàn bộ dữ liệu đã có — đúng thứ ta muốn tránh,
     * vì "thêm service mới rồi cho nó đọc lại lịch sử" là một trong hai lý do
     * chính chọn Kafka.
     *
     * <p>{@code max.poll.records=50}: mỗi thông điệp mang HTML thô, nên lô 500
     * bản ghi mặc định có thể là 40 MB trong bộ nhớ cùng lúc.
     */
    @Bean
    public ConsumerFactory<String, String> crawlConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, MAX_MESSAGE_BYTES);
        // Chốt offset THỦ CÔNG do container quản, sau khi listener chạy xong.
        // Với chốt tự động theo chu kỳ, một tiến trình chết giữa chừng sẽ chốt
        // mất những thông điệp chưa xử lý xong — mất trang một cách im lặng.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> crawlKafkaTemplate(
            ProducerFactory<String, Object> crawlProducerFactory) {
        return new KafkaTemplate<>(crawlProducerFactory);
    }

    @Bean
    public CrawlEventBus crawlEventBus(KafkaTemplate<String, Object> crawlKafkaTemplate) {
        log.info("Bus su kien crawl: KAFKA tai {} — topic {}, {}, {}, {}",
                bootstrapServers, pagesTopic, urlsTopic, outlinksTopic, imagesTopic);
        return new KafkaCrawlEventBus(crawlKafkaTemplate, pagesTopic, urlsTopic,
                outlinksTopic, imagesTopic);
    }

    // --- Consumer ------------------------------------------------------

    /**
     * Nhà máy container cho mọi {@code @KafkaListener}, kèm chính sách hỏng.
     *
     * <p><b>Thử lại có giãn cách rồi mới bỏ.</b> {@link ExponentialBackOff}
     * bắt đầu ở 1 giây, nhân đôi mỗi lần, tối đa 30 giây và tổng cộng 2 phút.
     * Vì sao giãn cách chứ không thử lại liên tục: phần lớn lỗi ở đây là lỗi
     * <i>tạm thời</i> (cơ sở dữ liệu đang khởi động lại, mạng chớp), và thử
     * lại ngay lập tức 10 lần chỉ làm nặng thêm đúng thứ đang ốm.
     *
     * <p>Hết số lần thì {@link DeadLetterPublishingRecoverer} đẩy thông điệp
     * sang {@code <topic>.DLT} — nó không mất, chỉ chuyển sang chỗ chờ người
     * xem.
     *
     * <p><b>{@code concurrency}</b> để mặc định 1 luồng mỗi container: số bản
     * sao tiến trình mới là thứ điều chỉnh thông lượng ở đây, và nó do
     * Kubernetes quyết (xem crawler-worker.yaml). Tăng luồng trong một tiến
     * trình làm mất tính "một phân hoạch một luồng" mà bộ lọc Bloom theo host
     * dựa vào.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> crawlListenerContainerFactory(
            ConsumerFactory<String, String> crawlConsumerFactory,
            KafkaTemplate<String, Object> crawlKafkaTemplate,
            ObjectMapper crawlEventObjectMapper) {

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(120_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(crawlKafkaTemplate), backOff);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(crawlConsumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        // Bộ chuyển đổi lấy kiểu đích từ chữ ký phương thức listener.
        factory.setRecordMessageConverter(new StringJsonMessageConverter(crawlEventObjectMapper));
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    // --- Modular Services chạy ở chế độ Kafka --------------------------

    /**
     * Bộ lọc URL của tiến trình worker.
     *
     * <p>Khác chế độ in-process ở một điểm bản chất: ở đó bộ lọc được cấp phát
     * <i>theo từng phiên crawl</i> từ {@code CrawlConfig}; ở đây worker sống
     * lâu hơn mọi phiên, nên luật lọc đến từ cấu hình triển khai.
     *
     * <p>{@code allowedDomains} rỗng nghĩa là không giới hạn theo domain —
     * phạm vi được kiểm soát bởi tập seed và bởi {@code maxDepth}.
     */
    @Bean
    public UrlFilter workerUrlFilter(
            @Value("${app.crawler.kafka.worker.max-depth:3}") int maxDepth,
            @Value("${app.crawler.kafka.worker.allowed-domains:}") String allowedDomains) {
        Set<String> domains = allowedDomains == null || allowedDomains.isBlank()
                ? Set.of()
                : Set.of(allowedDomains.split("\\s*,\\s*"));
        return new UrlFilter(domains, maxDepth, UrlFilter.NON_VI_EN_HOST_PREFIXES);
    }

    /**
     * Bộ lọc "đã gặp chưa" của worker.
     *
     * <p><b>Giới hạn phải nêu rõ:</b> đây là một bộ lọc Bloom trong bộ nhớ
     * tiến trình. Nó chỉ đúng vì topic được phân hoạch theo {@code host} và
     * mỗi phân hoạch chỉ có một consumer — nên worker này là nguồn sự thật
     * <i>đầy đủ</i> cho đúng những host mà nó được giao. Xem Javadoc của
     * {@code DiscoveredUrl}.
     *
     * <p>Hệ quả: khi worker khởi động lại, bộ lọc rỗng và một số URL sẽ được
     * xếp hàng lại. Không mất mát dữ liệu, chỉ tốn thêm ít băng thông. Đóng
     * hẳn khoảng này cần một bộ lọc lưu bền — công việc của bước sau, không
     * phải một lỗ hổng bị bỏ quên.
     */
    @Bean
    public UrlSeenFilter workerUrlSeenFilter(
            @Value("${app.crawler.kafka.worker.max-pages:100000}") int maxPages) {
        return UrlSeenFilter.forMaxPages(maxPages);
    }

    @Bean
    public UrlExtractorService urlExtractorService(UrlFilter workerUrlFilter,
                                                    UrlSeenFilter workerUrlSeenFilter,
                                                    CrawlEventBus crawlEventBus) {
        Supplier<UrlFilter> filter = () -> workerUrlFilter;
        Supplier<UrlSeenFilter> seen = () -> workerUrlSeenFilter;
        return new UrlExtractorService(new LinkExtractor(), filter, seen, crawlEventBus);
    }

    @Bean
    public ImageDownloadService imageDownloadService(
            CrawlEventBus crawlEventBus,
            @Value("${app.crawler.images.download:false}") boolean download,
            @Value("${app.crawler.images.max-per-page:50}") int maxPerPage,
            @Value("${app.crawler.images.max-bytes:5242880}") long maxBytes) {
        return new ImageDownloadService(crawlEventBus, new DnsResolver(), download,
                maxPerPage, maxBytes, ImageDownloadService.DEFAULT_TIMEOUT_MS);
    }

    /**
     * Analytics dùng {@link MeterRegistry} <b>thật</b> của ứng dụng — khác chế
     * độ in-process vốn dựng một registry đơn giản cho công cụ dòng lệnh.
     * Nhờ vậy số liệu crawl chảy thẳng ra {@code /actuator/prometheus}.
     */
    @Bean
    public CrawlAnalyticsService crawlAnalyticsService(MeterRegistry registry) {
        return new CrawlAnalyticsService(registry);
    }

    /**
     * Phơi số lần gửi hỏng của bus ra Prometheus.
     *
     * <p>Đây là thang đo mà cảnh báo {@code VnSearchCrawlBusFailing} dựa vào,
     * và nó đo đúng thứ khó thấy nhất: crawler <b>vẫn chạy bình thường</b>,
     * vẫn tải trang, vẫn lưu corpus — nhưng không có gì tới được các Modular
     * Service. Mọi thang đo khác đều xanh; chỉ con số này đỏ.
     *
     * <p>{@link MeterBinder} chứ không phải đăng ký trong constructor của bus:
     * đó là điểm mở rộng chính thức, Spring gọi nó sau khi registry đã sẵn
     * sàng nên không có chuyện thứ tự khởi tạo bean làm mất thang đo — cùng
     * lập luận đã ghi trong {@code MetricsConfig}.
     */
    @Bean
    public MeterBinder crawlBusMetrics(CrawlEventBus crawlEventBus) {
        return registry -> Gauge.builder("vnsearch.crawl.bus.publish.failures",
                        crawlEventBus, CrawlEventBus::getPublishFailureCount)
                .description("So lan khong gui duoc su kien len bus ke tu khi khoi dong")
                .register(registry);
    }
}
