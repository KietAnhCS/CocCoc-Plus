package com.vnsearch.crawler;

import com.vnsearch.crawler.bus.CrawlEventBus;
import com.vnsearch.crawler.bus.DiscoveredUrl;
import com.vnsearch.crawler.bus.InProcessCrawlEventBus;
import com.vnsearch.crawler.bus.OutlinksExtracted;
import com.vnsearch.crawler.bus.PageEvent;
import com.vnsearch.crawler.frontier.CrawlTask;
import com.vnsearch.crawler.frontier.UrlFrontier;
import com.vnsearch.crawler.modular.CrawlAnalyticsService;
import com.vnsearch.crawler.modular.ImageDownloadService;
import com.vnsearch.crawler.modular.ImageStore;
import com.vnsearch.crawler.modular.UrlExtractorService;
import com.vnsearch.model.WebDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bộ điều phối một phiên crawl — lớp này <b>không tự làm gì</b>, nó chỉ nối
 * các khối lại theo đúng sơ đồ kiến trúc crawler:
 *
 * <pre>
 *   seed URLs
 *       |
 *       v
 *   URL Frontier -> HTML Downloader -> Content Parser -> Language Filter -> Content Seen? -> (Yes) vứt
 *       ^                 |                             (không vi/en) vứt       |
 *       |                 v                                                     | (No)
 *       |           DNS Resolver                                                v
 *       |                                                          Content Storage
 *       |                                                                |
 *       |                                                                v
 *       |                                                          Link Extractor
 *       |                                                                |
 *       |                                                                v
 *       |                                                            URL Filter
 *       |                                                                |
 *       |                                                                v
 *       +----------------------------- (No) --------------------- URL Seen?  <--->  URL Storage
 *                                                                       |
 *                                                                    (Yes) vứt
 * </pre>
 *
 * <p><b>Mỗi khối là một lớp.</b> Trước đây bốn khối — tải trang, lọc URL,
 * hỏi đã gặp chưa, lưu kết quả — nằm lẫn trong vòng lặp worker của chính lớp
 * này, còn khối {@code Content Seen?} thì không tồn tại. Bảng ánh xạ:
 *
 * <table border="1">
 *   <caption>Khối trong sơ đồ và lớp cài đặt</caption>
 *   <tr><th>Khối</th><th>Lớp</th></tr>
 *   <tr><td>URL Frontier</td><td>{@link UrlFrontier}</td></tr>
 *   <tr><td>DNS Resolver</td><td>{@link DnsResolver}</td></tr>
 *   <tr><td>HTML Downloader</td><td>{@link HtmlDownloader}</td></tr>
 *   <tr><td>Content Parser</td><td>{@link ContentParser}</td></tr>
 *   <tr><td>Language Filter</td><td>{@link LanguageFilter}</td></tr>
 *   <tr><td>Content Seen?</td><td>{@link ContentSeenFilter}</td></tr>
 *   <tr><td>Content Storage</td><td>{@link ContentStorage}</td></tr>
 *   <tr><td>Link Extractor</td><td>{@link LinkExtractor}</td></tr>
 *   <tr><td>URL Filter</td><td>{@link UrlFilter} (dùng {@link RobotsTxtParser})</td></tr>
 *   <tr><td>URL Seen?</td><td>{@link UrlSeenFilter} (dùng {@code BloomFilter})</td></tr>
 *   <tr><td>URL Storage</td><td>{@link UrlStorage}</td></tr>
 * </table>
 *
 * <p><b>Thứ tự các khối không tuỳ tiện.</b> {@code Content Seen?} đứng trước
 * {@code Link Extractor} nên trang trùng nội dung bị vứt mà không phải bóc
 * liên kết. {@code URL Filter} đứng trước {@code URL Seen?} nên các luật lọc
 * rẻ (độ sâu, domain, đuôi tệp) chạy trước phép tra bộ lọc Bloom.
 * {@code Language Filter} đứng ngay sau {@code Content Parser} và trước
 * {@code Content Seen?} vì hai lẽ: nó chỉ cần văn bản đã bóc (không cần vân
 * tay), và trang ngoại ngữ bị vứt tại đó thì <b>không bóc liên kết</b> — nếu
 * vẫn bóc, crawler sẽ tiếp tục đi sâu vào vùng ngoại ngữ để rồi vứt tiếp.
 *
 * <p><b>Chống trùng ở hai mức khác nhau.</b> {@link UrlSeenFilter} chặn tải
 * lại cùng một <i>địa chỉ</i>; {@link ContentSeenFilter} chặn lưu lại cùng
 * một <i>nội dung</i> đến từ hai địa chỉ khác nhau. Thiếu mức thứ hai thì
 * các bản sao cùng lọt vào chỉ mục và cùng hiện trong một trang kết quả.
 *
 * <p>Duyệt theo BFS (thứ tự do {@link UrlFrontier} quyết định), chia việc
 * cho nhiều thread trong một {@link ExecutorService} có số thread cố định.
 *
 * <p><b>Observer:</b> tiến độ được phát qua {@link CrawlListener} thay vì in
 * thẳng trong vòng lặp worker. <b>Builder:</b> cấu hình là {@link CrawlConfig}
 * bất biến, kiểm tra tính hợp lệ tập trung trong {@code build()}.
 */
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    /** Điểm ưu tiên khởi điểm của seed — luôn cao hơn liên kết bóc được. */
    private static final int SEED_BACKLINK_SCORE = 10;

    // --- Các khối không phụ thuộc cấu hình phiên crawl ---
    private final UrlFrontier frontier = new UrlFrontier();
    private final DnsResolver dnsResolver = new DnsResolver();
    private final HtmlDownloader htmlDownloader = new HtmlDownloader(dnsResolver);
    private final ContentParser contentParser = new ContentParser();
    private final LanguageFilter languageFilter = new LanguageFilter();
    private final ContentSeenFilter contentSeenFilter = new ContentSeenFilter();
    private final ContentStorage contentStorage = new ContentStorage();

    /**
     * Bus nối crawler với cụm <b>Modular Services</b> — ô {@code Kafka} trong
     * sơ đồ kiến trúc.
     *
     * <p>Ở đây kết thúc trách nhiệm của lớp này: crawler tải trang, phân tích,
     * lọc ngôn ngữ, khử trùng, lưu — rồi <b>đẩy lên bus và quên đi</b>. Việc
     * bóc liên kết, tải ảnh và thống kê là của ba service phía sau.
     */
    private final CrawlEventBus bus;

    /**
     * {@code true} khi lớp này tự dựng bus in-process và do đó phải tự đăng ký
     * ba Modular Service.
     *
     * <p>Khi bus được tiêm từ ngoài vào (chế độ Kafka), các service đã chạy ở
     * <i>tiến trình khác</i>; đăng ký thêm bản cục bộ ở đây sẽ khiến mỗi trang
     * bị xử lý hai lần và mỗi URL bị xếp hàng hai lượt.
     */
    private final boolean ownsBus;

    /** Số OutlinksExtracted tới cho một URL không có trong Content Storage. */
    private final AtomicLong orphanOutlinks = new AtomicLong();

    /**
     * Danh tính phiên crawl này, đi kèm mọi sự kiện phát lên bus.
     *
     * <p>Mặc định là một UUID để chạy dòng lệnh cũng có danh tính hợp lệ;
     * {@code CrawlJobManager} ghi đè bằng id của job để các sự kiện quay về từ
     * Kafka tìm đúng đường. Xem Javadoc của {@code PageEvent}.
     */
    private volatile String jobId = java.util.UUID.randomUUID().toString();

    /**
     * Kho ảnh dùng chung, hoặc {@code null} khi chạy từ dòng lệnh.
     *
     * <p>Không phải {@code final} theo kiểu bắt buộc phải có: công cụ dòng
     * lệnh ({@code MultiDomainCrawlRunner}) không có ngữ cảnh Spring và cũng
     * không cần phục vụ {@code /api/images}. Ở đó ảnh vẫn được bóc và đếm,
     * chỉ là không giữ lại.
     */
    private final ImageStore imageStore;

    /** Chỉ khác null ở chế độ in-process — giữ để lấy số liệu cho báo cáo. */
    private volatile UrlExtractorService urlExtractorService;
    private volatile ImageDownloadService imageDownloadService;
    private volatile CrawlAnalyticsService analyticsService;

    // --- Các khối phải cấp phát lại theo từng phiên (cần allowedDomains, maxPages) ---
    private volatile UrlFilter urlFilter = new UrlFilter(Set.of(), Integer.MAX_VALUE);
    private volatile UrlSeenFilter urlSeenFilter = UrlSeenFilter.forMaxPages(1);

    /**
     * Vừa là số trang đã lưu, vừa là nguồn cấp {@code docId}.
     *
     * <p>Trước đây đây là hai {@code AtomicInteger} riêng. Tách ra thì
     * {@code docId} được cấp <i>trước</i> khi lưu, nên mỗi lần lưu thất bại
     * lại đốt một id và dãy docId thủng lỗ. Dùng chung một bộ đếm, cấp id
     * <i>sau</i> khi lưu thành công, thì docId luôn đặc và bằng đúng
     * {@code 0..n-1}.
     */
    private final AtomicInteger pagesCrawled = new AtomicInteger(0);

    /**
     * Số tài liệu nạp lại từ phiên trước — <b>mốc bắt đầu của docId phiên này</b>.
     *
     * <p>Cần một biến riêng vì {@link #pagesCrawled} còn là điều kiện dừng
     * ({@code < maxPages}): cộng luôn corpus cũ vào đó thì một phiên nối tiếp
     * với corpus 5.000 trang và {@code maxPages=5000} sẽ dừng ngay khi chưa
     * tải trang nào. Thiếu mốc này thì phiên nối tiếp cấp lại docId {@code 0,
     * 1, 2...} vốn đã thuộc về tài liệu cũ, và tệp ghi ra chứa docId trùng.
     */
    private volatile int restoredDocCount = 0;

    /** Số worker đang THỰC SỰ xử lý một trang — dùng để biết khi nào thật sự hết việc. */
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    /**
     * Danh sách listener. {@code CopyOnWriteArrayList} vì nó được ĐỌC từ nhiều
     * worker thread nhưng hiếm khi ghi (chỉ lúc đăng ký) — đúng ca sử dụng mà
     * cấu trúc này được thiết kế cho.
     */
    private final List<CrawlListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Chế độ MỘT TIẾN TRÌNH — mặc định.
     *
     * <p>Tự dựng {@link InProcessCrawlEventBus} và tự đăng ký ba Modular
     * Service vào nó. Hành vi quan sát được từ bên ngoài giống hệt bản mã
     * trước khi tách service: gọi {@code crawl()} rồi nhận về danh sách tài
     * liệu đầy đủ, kèm outlinks. Nhờ vậy {@code run-crawl.bat},
     * {@code MultiDomainCrawlRunner} và toàn bộ bài test hiện có không phải
     * sửa một dòng nào.
     */
    public CrawlerService() {
        this.bus = new InProcessCrawlEventBus();
        this.ownsBus = true;
        this.imageStore = null;
    }

    /**
     * Chế độ NHIỀU TIẾN TRÌNH — truyền vào một bus Kafka.
     *
     * <p>Lớp này chỉ còn <b>phát</b> sự kiện; ba Modular Service chạy ở tiến
     * trình khác và co giãn độc lập. Vòng lặp crawl được khép lại từ bên ngoài
     * bằng cách gọi {@link #acceptDiscoveredUrl} và {@link #acceptOutlinks} từ
     * các listener Kafka.
     *
     * @param bus bus đã cấu hình sẵn; {@code null} nghĩa là quay về chế độ mặc định
     */
    public CrawlerService(CrawlEventBus bus) {
        this(bus, null);
    }

    /**
     * @param imageStore kho ảnh dùng chung; {@code null} thì ảnh chỉ được đếm
     *                   rồi bỏ, không giữ lại để phục vụ {@code /api/images}
     */
    public CrawlerService(CrawlEventBus bus, ImageStore imageStore) {
        if (bus == null) {
            this.bus = new InProcessCrawlEventBus();
            this.ownsBus = true;
        } else {
            this.bus = bus;
            this.ownsBus = false;
        }
        this.imageStore = imageStore;
    }

    /**
     * Đăng ký ba Modular Service vào bus in-process, cho một phiên crawl.
     *
     * <p>Phải gọi <b>sau</b> khi {@link #urlFilter} và {@link #urlSeenFilter}
     * đã được cấp phát cho phiên này — {@link UrlExtractorService} nhận chúng
     * qua {@link java.util.function.Supplier} nên nó luôn thấy bộ lọc hiện
     * hành, nhưng bản thân việc đăng ký thì phải xảy ra một lần cho mỗi
     * {@code CrawlerService}, không phải mỗi phiên.
     *
     * <p>Vì sao {@link SimpleMeterRegistry} chứ không phải registry của Spring:
     * lớp này được dùng cả từ dòng lệnh ({@code MultiDomainCrawlRunner}), nơi
     * không có ngữ cảnh Spring nào. Registry đơn giản vẫn cộng dồn đủ số liệu
     * cho báo cáo cuối phiên; khi chạy trong ứng dụng web thì bản Kafka dùng
     * registry thật và số liệu chảy ra Prometheus.
     */
    private void wireInProcessServices() {
        if (!ownsBus || urlExtractorService != null) {
            return; // đã nối rồi, hoặc bus do bên ngoài quản
        }
        InProcessCrawlEventBus localBus = (InProcessCrawlEventBus) bus;

        UrlExtractorService extractor = new UrlExtractorService(
                new LinkExtractor(), () -> urlFilter, () -> urlSeenFilter, bus);
        ImageDownloadService images = new ImageDownloadService(bus);
        CrawlAnalyticsService analytics = new CrawlAnalyticsService(new SimpleMeterRegistry());

        localBus.subscribePages(extractor)
                .subscribePages(images)
                .subscribePages(analytics)
                // Khép vòng lặp của sơ đồ: URL Extractor -> ... -> URL Frontier
                .subscribeDiscoveredUrls(this::acceptDiscoveredUrl)
                // Chiều ngược của mũi tên hai chiều: outlinks về Content Storage
                .subscribeOutlinks(this::acceptOutlinks)
                // Hai service gặp nhau qua bus, không gọi thẳng nhau
                .subscribeImages(analytics::onImage);

        // Kho ảnh là bên nhận THỨ HAI của cùng một kênh — Analytics đếm, kho
        // thì giữ bản ghi. Tách làm hai bên đăng ký chứ không nhét cả hai việc
        // vào Analytics: đếm và lưu là hai trách nhiệm, và tắt một cái không
        // được làm hỏng cái kia.
        if (imageStore != null) {
            localBus.subscribeImages(imageStore::add);
        }

        this.urlExtractorService = extractor;
        this.imageDownloadService = images;
        this.analyticsService = analytics;
    }

    /**
     * Nạp một URL đã qua {@code URL Filter} và {@code URL Seen Detector} vào
     * frontier — mũi tên đóng vòng lặp trong sơ đồ.
     *
     * <p>Công khai vì ở chế độ Kafka, lời gọi này đến từ một
     * {@code @KafkaListener} nằm ngoài lớp này. Ở chế độ in-process nó được
     * nối qua {@code subscribeDiscoveredUrls}.
     *
     * <p><b>Không lọc lại ở đây.</b> Hai phép lọc đã chạy tại
     * {@link UrlExtractorService}; chạy lại lần nữa thì {@code markSeenIfNew}
     * sẽ trả về {@code false} cho <i>chính URL vừa được ghi nhận</i> và không
     * URL nào vào được frontier — crawler dừng ngay sau các seed.
     */
    public boolean acceptDiscoveredUrl(DiscoveredUrl discovered) {
        if (discovered == null) {
            return false;
        }
        return frontier.addUrl(discovered.url(), discovered.depth(), 1);
    }

    /** Ghi danh sách liên kết ra vào tài liệu tương ứng — dữ liệu cho PageRank. */
    public void acceptOutlinks(OutlinksExtracted outlinks) {
        if (outlinks == null) {
            return;
        }
        if (!contentStorage.applyOutlinks(outlinks.sourceUrl(), outlinks.outlinks())) {
            // Bình thường ở chế độ Kafka: sự kiện của một phiên trước, hoặc của
            // một trang bị loại vì trùng nội dung. Đếm để nếu con số này lớn
            // bất thường thì có cái mà nhìn.
            orphanOutlinks.incrementAndGet();
        }
    }

    /** Đăng ký một bộ quan sát phiên crawl (Observer pattern). */
    public CrawlerService addListener(CrawlListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /** Chạy một phiên crawl BFS đầy đủ, trả về danh sách WebDocument đã crawl được. */
    public List<WebDocument> crawl(List<String> seedUrls, CrawlConfig config) {
        return crawl(seedUrls, config, List.of());
    }

    /**
     * Chạy một phiên crawl NỐI TIẾP corpus đã có: giữ nguyên tài liệu cũ, không
     * tải lại chúng, và đi tiếp từ những liên kết mà chúng trỏ tới.
     *
     * <p><b>Vì sao nối tiếp qua corpus chứ không qua {@link UrlStorage}.</b>
     * {@code UrlStorage} ghi mọi URL <b>được xếp hàng</b> — bao gồm hàng chục
     * nghìn URL còn nằm trong frontier lúc phiên crawl dừng, những URL chưa hề
     * được tải. Nạp lại tệp đó sẽ đánh dấu tất cả là "đã gặp", và
     * {@link #enqueue} loại thẳng chúng, nên chúng KHÔNG BAO GIỜ được crawl
     * nữa. Nói cách khác, dùng URL Storage để tiếp tục phiên crawl sẽ khoá
     * vĩnh viễn phần lớn không gian tìm kiếm còn lại.
     *
     * <p>Corpus không có khiếm khuyết đó: mỗi tài liệu trong đó là một trang
     * THẬT SỰ đã tải. Đánh dấu đúng những URL này là đã gặp thì chặn đúng cái
     * cần chặn. Còn frontier thì không cần lưu bền chút nào — nó tái tạo được
     * từ {@code outlinks} của chính các tài liệu cũ, thứ đã nằm sẵn trong tệp.
     *
     * @param previousDocuments corpus từ phiên trước; rỗng nghĩa là crawl mới
     */
    public List<WebDocument> crawl(List<String> seedUrls, CrawlConfig config,
                                    List<WebDocument> previousDocuments) {
        long start = System.currentTimeMillis();

        // Biến cục bộ, không phải trường: kho URL chỉ sống trong đúng một phiên
        // crawl, và ai cần tới nó về sau đều lấy được qua urlSeenFilter.
        UrlStorage urlStorage = config.urlStoragePath() == null
                ? UrlStorage.disabled()
                : UrlStorage.file(Path.of(config.urlStoragePath()));
        urlFilter = new UrlFilter(config.allowedDomains(), config.maxDepth(),
                config.excludedHostPrefixes());
        urlSeenFilter = UrlSeenFilter.forMaxPages(config.maxPages(), urlStorage);

        // SAU khi hai bộ lọc đã có: UrlExtractorService đọc chúng qua Supplier,
        // nhưng đăng ký ở đây thì thứ tự luôn đúng kể cả khi bản cài đổi sang
        // giữ tham chiếu trực tiếp.
        wireInProcessServices();

        try {
            long replayed = urlSeenFilter.replayFromStorage();
            if (replayed > 0) {
                log.info("Đã nạp lại {} URL từ {} — những trang này sẽ không tải lại.",
                        replayed, config.urlStoragePath());
            }

            restore(previousDocuments);
            seed(seedUrls);
            runWorkers(config);
        } finally {
            // Đóng trong finally: thiếu nó thì phần đuôi trong bộ đệm không
            // bao giờ được ghi xuống đĩa khi phiên crawl kết thúc bất thường.
            urlStorage.close();
        }

        long elapsed = System.currentTimeMillis() - start;
        notifyFinished(pagesCrawled.get(), elapsed);
        return contentStorage.all();
    }

    /**
     * Đưa corpus của phiên trước trở lại đúng ba khối cần biết về nó, rồi tái
     * tạo frontier từ các liên kết của nó.
     *
     * <p>Ba khối, ba lý do khác nhau — thiếu bất kỳ khối nào cũng hỏng theo một
     * kiểu riêng:
     * <ul>
     *   <li>{@link ContentStorage}: giữ lại nội dung cũ, để tệp ghi ra ở cuối
     *       phiên là corpus TỔNG chứ không phải chỉ phần mới. Thiếu bước này
     *       thì phiên "nối tiếp" thực chất ghi đè và xoá sạch phiên trước.</li>
     *   <li>{@link UrlSeenFilter}: chặn tải lại những trang đã có.</li>
     *   <li>{@link ContentSeenFilter}: giữ vân tay nội dung cũ, nếu không thì
     *       một trang cũ xuất hiện lại dưới URL khác sẽ được lưu thành bản sao
     *       thứ hai — đúng thứ khối này sinh ra để ngăn.</li>
     * </ul>
     *
     * <p><b>Độ sâu đặt lại về 1 cho mọi liên kết cũ.</b> {@code WebDocument}
     * không lưu độ sâu BFS, và cũng không nên lưu: độ sâu là thuộc tính của
     * ĐƯỜNG ĐI trong một phiên crawl cụ thể, không phải của trang. Hệ quả cần
     * biết: mỗi lần chạy nối tiếp, {@code maxDepth} được tính lại từ corpus cũ
     * xem như tầng nền, nên corpus lan rộng thêm {@code maxDepth} tầng nữa sau
     * mỗi phiên. Đó là hành vi mong muốn khi mục đích là mở rộng corpus dần.
     */
    private void restore(List<WebDocument> previousDocuments) {
        if (previousDocuments == null || previousDocuments.isEmpty()) {
            return;
        }
        int restored = 0;
        for (WebDocument doc : previousDocuments) {
            if (doc == null || doc.getUrl() == null || doc.getUrl().isBlank()) {
                continue;
            }
            if (!contentStorage.save(doc)) {
                continue; // hai bản ghi cùng URL trong tệp cũ: giữ bản đầu
            }
            // Đánh số lại corpus cũ thành 0..restored-1 thay vì tin vào docId
            // trong tệp: tệp có thể do một phiên chạy bằng bản mã cũ ghi ra và
            // chứa docId trùng. Đánh lại ở đây thì dãy docId của corpus TỔNG
            // (cũ + mới) luôn đặc và duy nhất, bất kể tệp vào ra sao.
            doc.setDocId(restored++);
            urlSeenFilter.markSeenIfNew(doc.getUrl());
            contentSeenFilter.seenBefore(doc.getBodyText());
        }
        restoredDocCount = restored;

        // Xếp hàng SAU khi đã đánh dấu toàn bộ URL cũ là đã gặp. Làm xen kẽ thì
        // một liên kết trỏ tới trang cũ chưa kịp đánh dấu sẽ lọt vào frontier và
        // bị tải lại — đúng thứ việc nối tiếp phải tránh.
        int queued = 0;
        for (WebDocument doc : previousDocuments) {
            if (doc == null || doc.getOutlinks() == null) {
                continue;
            }
            for (String outlink : doc.getOutlinks()) {
                if (enqueue(outlink, 1)) {
                    queued++;
                }
            }
        }
        log.info("Nối tiếp corpus cũ: giữ {} tài liệu, dựng lại frontier với {} URL chờ.",
                restored, queued);
    }

    /**
     * Nạp seed vào frontier.
     *
     * <p>Seed đi qua {@link UrlFilter} nhưng <b>bỏ qua</b> kết quả của
     * {@link UrlSeenFilter}: khi tiếp tục một phiên crawl cũ, seed chắc chắn
     * đã nằm trong kho URL đã gặp, và nếu tôn trọng kết quả đó thì frontier
     * rỗng ngay từ đầu và phiên crawl kết thúc mà không làm gì.
     */
    private void seed(List<String> seedUrls) {
        for (String seed : seedUrls) {
            String url = UrlCanonicalizer.canonicalize(seed);
            if (!urlFilter.accept(url, 0)) {
                log.warn("Seed bị URL Filter loại, bỏ qua: {}", seed);
                continue;
            }
            urlSeenFilter.markSeenIfNew(url);
            frontier.addUrl(url, 0, SEED_BACKLINK_SCORE);
        }
    }

    private void runWorkers(CrawlConfig config) {
        ExecutorService pool = Executors.newFixedThreadPool(config.threadCount());
        CountDownLatch latch = new CountDownLatch(config.threadCount());
        for (int i = 0; i < config.threadCount(); i++) {
            pool.submit(() -> {
                try {
                    workerLoop(config);
                } catch (Exception e) {
                    log.error("Worker dừng bất thường", e);
                } finally {
                    latch.countDown(); // trong finally: thiếu nó thì await() chờ đủ 60 phút vô ích
                }
            });
        }

        try {
            if (!latch.await(config.maxDurationMinutes(), TimeUnit.MINUTES)) {
                log.warn("Hết trần thời gian {} phút, dừng crawl với {} trang.",
                        config.maxDurationMinutes(), pagesCrawled.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
    }

    /**
     * Vòng lặp của một worker thread — chính là vòng lặp trong sơ đồ.
     *
     * <p><b>Điều kiện dừng.</b> Frontier rỗng KHÔNG đồng nghĩa với hết việc —
     * một worker khác có thể đang tải một trang và sắp thêm hàng trăm liên
     * kết mới. Nếu thoát ngay khi thấy frontier rỗng, các worker sẽ chết dần
     * trong những khoảng trống tạm thời và phiên crawl dừng sớm hơn nhiều so
     * với maxPages.
     *
     * <p>Điều kiện dừng đúng là {@code F = 0 AND A = 0}. Nhưng hai phép đọc
     * đó KHÔNG nguyên tử với nhau, nên tồn tại một cửa sổ đua: worker A thấy
     * frontier rỗng đúng lúc worker B đã lấy task nhưng CHƯA kịp
     * {@code incrementAndGet}. Yêu cầu điều kiện dừng đúng
     * {@code IDLE_CONFIRMATIONS} lần liên tiếp, cách nhau 200ms, đưa xác suất
     * nhầm xuống khoảng {@code (vài us / 200000 us)^3 ~= 10^-15}.
     *
     * <p>Đây là một HEURISTIC, không phải thuật toán đúng đắn có chứng minh —
     * bài toán "phát hiện kết thúc phân tán" có lời giải chính xác
     * (Dijkstra-Scholten, Safra) nhưng phức tạp hơn nhiều.
     */
    private void workerLoop(CrawlConfig config) {
        final int idleConfirmations = 3;
        int idleChecks = 0;

        while (pagesCrawled.get() < config.maxPages()) {
            CrawlTask task = frontier.nextUrl(); // URL Frontier
            if (task == null) {
                if (activeWorkers.get() == 0 && ++idleChecks >= idleConfirmations) {
                    break; // thật sự hết việc
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            idleChecks = 0; // chỉ tích luỹ khi LIÊN TỤC rỗng

            // URL Filter, mức đắt: có thể phải tải robots.txt qua mạng. Các
            // luật rẻ đã chạy từ lúc URL được xếp vào hàng đợi.
            if (!urlFilter.isAllowedByRobots(task.url())) {
                continue;
            }

            activeWorkers.incrementAndGet();
            try {
                processPage(task, config);
            } finally {
                // BẮT BUỘC trong finally: nếu thân vòng lặp ném ngoại lệ mà
                // không giảm, activeWorkers không bao giờ về 0 và điều kiện
                // dừng không bao giờ đúng -> mọi worker kẹt trong vòng lặp
                // ngủ-thử-lại.
                activeWorkers.decrementAndGet();
            }
        }
    }

    /** Một lượt đi qua toàn bộ chuỗi khối, cho đúng một URL. */
    private void processPage(CrawlTask task, CrawlConfig config) {
        Document html;
        try {
            html = htmlDownloader.download(task.url()); // HTML Downloader -> DNS Resolver
        } catch (IOException e) {
            // Gồm cả UnknownHostException do DnsResolver ném — DnsResolver đã tự
            // đếm ca đó, ở đây chỉ cần báo cho listener như mọi lỗi tải khác.
            notifyError(task.url(), e);
            return;
        }

        WebDocument doc = contentParser.parse(task.url(), html); // Content Parser

        // Language Filter — chỉ giữ tiếng Việt và tiếng Anh. Đặt TRƯỚC
        // Content Seen? để trang ngoại ngữ không tốn một lần băm SHA-256,
        // và quan trọng hơn: không bóc liên kết của nó.
        if (!languageFilter.accept(doc)) {
            notifyForeignLanguage(task.url(), doc.getLanguage());
            return;
        }

        // Content Seen? — nhánh "Yes": vứt, KHÔNG bóc liên kết.
        if (contentSeenFilter.seenBefore(doc.getBodyText())) {
            notifyDuplicateContent(task.url());
            return;
        }

        if (!contentStorage.save(doc)) { // Content Storage
            return; // URL này đã có bản ghi, không đếm trùng
        }

        int count = pagesCrawled.incrementAndGet();
        // Đặc, không thủng lỗ vì cấp SAU khi lưu thành công; cộng mốc corpus cũ
        // để phiên nối tiếp không cấp lại docId đã dùng.
        doc.setDocId(restoredDocCount + count - 1);

        // ─── Ranh giới giữa crawler và cụm Modular Services ───────────────
        //
        // Trước đây bốn dòng ở đây gọi thẳng LinkExtractor rồi lặp enqueue().
        // Nay chúng được thay bằng MỘT lời phát lên bus, và ba service phía
        // sau tự lấy phần của mình:
        //
        //     URL Extractor  -> liên kết -> URL Filter -> URL Seen -> Frontier
        //     Image Download -> ảnh
        //     Analytics      -> số liệu
        //
        // CHI PHÍ ĐÃ BIẾT: html.outerHtml() kết xuất lại cây DOM thành chuỗi,
        // và ở chế độ in-process thì UrlExtractorService phân tích chuỗi đó
        // lần nữa — khoảng 3–8 ms mỗi trang, tức DOM bị dựng hai lần. Đây là
        // giá phải trả để CÙNG MỘT đường mã chạy được ở cả hai chế độ. Một
        // đường tắt "nếu in-process thì truyền thẳng Document" sẽ tạo ra một
        // nhánh chỉ chạy ở môi trường thật, tức một nhánh không được test.
        //
        // Đây cũng là lý do phần tải trang vẫn nằm ở đây chứ không thành một
        // service nữa: nó là thứ DUY NHẤT phải tôn trọng chính sách lịch sự
        // theo host, và chính sách đó gắn liền với UrlFrontier.
        bus.publishPage(new PageEvent(
                task.url(), hostOf(task.url()), task.depth(),
                doc.getTitle(), doc.getBodyText(), doc.getLanguage(),
                html.outerHtml(),
                ContentSeenFilter.fingerprint(doc.getBodyText() == null ? "" : doc.getBodyText()),
                doc.getCrawledAt() != null ? doc.getCrawledAt() : Instant.now(),
                jobId));

        // Ở chế độ in-process, phát lên bus là đồng bộ nên outlinks đã được
        // UrlExtractorService ghi vào doc trước dòng này. Ở chế độ Kafka thì
        // chưa — con số outlink trong sự kiện tiến độ sẽ là 0, và đó là hành
        // vi đúng: lúc này crawler THẬT SỰ chưa biết trang có bao nhiêu liên
        // kết. Báo một con số đoán bừa còn tệ hơn báo 0.
        notifyPageCrawled(new CrawlListener.CrawlEvent(
                count, config.maxPages(), task.url(), task.depth(),
                doc.getOutlinks().size(), frontier.size(), frontier.domainCount()));
    }

    /** Host của một URL, lùi về chính URL khi không phân giải được — khoá phân hoạch. */
    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null && !host.isBlank() ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Chặng {@code URL Filter -> URL Seen? -> URL Frontier} của sơ đồ, cho một
     * liên kết vừa bóc được.
     *
     * <p>Ghi nhận "đã gặp" xảy ra ngay tại đây, lúc XẾP HÀNG, chứ không phải
     * lúc lấy ra khỏi hàng đợi. Ghi nhận muộn thì trong khoảng thời gian URL
     * nằm chờ, nó vẫn bị coi là chưa gặp.
     *
     * <p><b>Không chuẩn hoá lại URL ở đây.</b> Mọi đường vào đều đã chuẩn hoá:
     * liên kết đi ra từ {@link LinkExtractor}, seed đi qua {@link #seed}. Gọi
     * thêm một lần nữa chỉ lặp lại đúng kết quả cũ — phép chuẩn hoá là
     * idempotent nên không sai, chỉ thừa.
     *
     * @return {@code true} nếu URL thật sự được xếp vào frontier
     */
    private boolean enqueue(String url, int depth) {
        if (!urlFilter.accept(url, depth)) { // URL Filter
            return false;
        }
        if (!urlSeenFilter.markSeenIfNew(url)) { // URL Seen? -> URL Storage
            return false;
        }
        frontier.addUrl(url, depth, 1); // URL Frontier
        return true;
    }

    private void notifyPageCrawled(CrawlListener.CrawlEvent event) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onPageCrawled(event);
            } catch (Exception e) {
                // Một listener hỏng không được làm chết cả phiên crawl.
                log.warn("Listener {} ném ngoại lệ", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyError(String url, Exception error) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onError(url, error);
            } catch (Exception e) {
                log.warn("Listener {} ném ngoại lệ", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyDuplicateContent(String url) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onDuplicateContent(url);
            } catch (Exception e) {
                log.warn("Listener {} ném ngoại lệ", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyForeignLanguage(String url, String language) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onForeignLanguage(url, language);
            } catch (Exception e) {
                log.warn("Listener {} ném ngoại lệ", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyFinished(int totalPages, long elapsedMs) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onFinished(totalPages, elapsedMs);
            } catch (Exception e) {
                log.warn("Listener {} ném ngoại lệ", listener.getClass().getSimpleName(), e);
            }
        }
    }

    public int getPagesCrawledCount() {
        return pagesCrawled.get();
    }

    public int getQueueSize() {
        return frontier.size();
    }

    public int getBloomFilterBits() {
        return urlSeenFilter.getNumBits();
    }

    // Các khối cấu thành, mở ra để lấy số liệu cho báo cáo. Mỗi khối tự giữ
    // bộ đếm của mình — lớp này không bọc lại thêm một tầng getter nào nữa.

    public DnsResolver getDnsResolver() {
        return dnsResolver;
    }

    public HtmlDownloader getHtmlDownloader() {
        return htmlDownloader;
    }

    public UrlFilter getUrlFilter() {
        return urlFilter;
    }

    public UrlSeenFilter getUrlSeenFilter() {
        return urlSeenFilter;
    }

    /**
     * Bản chụp corpus tại thời điểm gọi — nguồn dữ liệu cho
     * {@link CheckpointCrawlListener}. Gọi được an toàn giữa lúc crawl đang
     * chạy: {@link ContentStorage#all()} trả về bản sao, nên luồng ghi không
     * duyệt trên cấu trúc mà các worker đang sửa.
     */
    public List<WebDocument> snapshotDocuments() {
        return contentStorage.all();
    }

    public ContentSeenFilter getContentSeenFilter() {
        return contentSeenFilter;
    }

    public LanguageFilter getLanguageFilter() {
        return languageFilter;
    }

    // --- Cụm Modular Services ---

    /** Bus đang dùng — in-process hay Kafka tuỳ constructor nào được gọi. */
    public CrawlEventBus getEventBus() {
        return bus;
    }

    public String getJobId() {
        return jobId;
    }

    /**
     * Gán danh tính phiên. Gọi <b>trước</b> {@code crawl()}; đổi giữa chừng
     * thì các sự kiện đã phát mang id cũ và sẽ không tìm được đường về.
     */
    public void setJobId(String jobId) {
        if (jobId != null && !jobId.isBlank()) {
            this.jobId = jobId;
        }
    }

    /** {@code true} khi ba Modular Service chạy trong chính tiến trình này. */
    public boolean isInProcessMode() {
        return ownsBus;
    }

    /** {@code null} ở chế độ Kafka — khi đó service chạy ở tiến trình khác. */
    public UrlExtractorService getUrlExtractorService() {
        return urlExtractorService;
    }

    public ImageDownloadService getImageDownloadService() {
        return imageDownloadService;
    }

    public CrawlAnalyticsService getAnalyticsService() {
        return analyticsService;
    }

    /**
     * Số sự kiện outlinks tới cho một URL không có trong Content Storage.
     *
     * <p>Ở chế độ in-process con số này phải luôn bằng 0 — sự kiện được phát
     * ngay sau khi lưu thành công. Khác 0 là dấu hiệu có lỗi thật. Ở chế độ
     * Kafka thì một lượng nhỏ là bình thường (sự kiện còn sót từ phiên trước).
     */
    public long getOrphanOutlinksCount() {
        return orphanOutlinks.get();
    }
}
