package com.vnsearch.crawler;

import com.vnsearch.datastructure.UrlFrontier;
import com.vnsearch.model.WebDocument;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ điều phối một phiên crawl — lớp này <b>không tự làm gì</b>, nó chỉ nối
 * các khối lại theo đúng sơ đồ kiến trúc crawler:
 *
 * <pre>
 *   seed URLs
 *       |
 *       v
 *   URL Frontier  --->  HTML Downloader  --->  Content Parser  --->  Content Seen? ---> (Yes) vứt
 *       ^                     |                                          |
 *       |                     v                                          | (No)
 *       |               DNS Resolver                                     v
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
 *
 * <p><b>Chống trùng ở hai mức khác nhau.</b> {@link UrlSeenFilter} chặn tải
 * lại cùng một <i>địa chỉ</i>; {@link ContentSeenFilter} chặn lưu lại cùng
 * một <i>nội dung</i> đến từ hai địa chỉ khác nhau. Thiếu mức thứ hai thì
 * các bản sao cùng lọt vào chỉ mục và cùng hiện trong một trang kết quả.
 *
 * <p>Duyệt theo BFS (ưu tiên theo điểm của {@link UrlFrontier}), chia việc
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
    private final ContentSeenFilter contentSeenFilter = new ContentSeenFilter();
    private final ContentStorage contentStorage = new ContentStorage();
    private final LinkExtractor linkExtractor = new LinkExtractor();

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

    /** Số worker đang THỰC SỰ xử lý một trang — dùng để biết khi nào thật sự hết việc. */
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    /**
     * Danh sách listener. {@code CopyOnWriteArrayList} vì nó được ĐỌC từ nhiều
     * worker thread nhưng hiếm khi ghi (chỉ lúc đăng ký) — đúng ca sử dụng mà
     * cấu trúc này được thiết kế cho.
     */
    private final List<CrawlListener> listeners = new CopyOnWriteArrayList<>();

    /** Đăng ký một bộ quan sát phiên crawl (Observer pattern). */
    public CrawlerService addListener(CrawlListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /** Chạy một phiên crawl BFS đầy đủ, trả về danh sách WebDocument đã crawl được. */
    public List<WebDocument> crawl(List<String> seedUrls, CrawlConfig config) {
        long start = System.currentTimeMillis();

        // Biến cục bộ, không phải trường: kho URL chỉ sống trong đúng một phiên
        // crawl, và ai cần tới nó về sau đều lấy được qua urlSeenFilter.
        UrlStorage urlStorage = config.urlStoragePath() == null
                ? UrlStorage.disabled()
                : UrlStorage.file(Path.of(config.urlStoragePath()));
        urlFilter = new UrlFilter(config.allowedDomains(), config.maxDepth());
        urlSeenFilter = UrlSeenFilter.forMaxPages(config.maxPages(), urlStorage);

        try {
            long replayed = urlSeenFilter.replayFromStorage();
            if (replayed > 0) {
                log.info("Đã nạp lại {} URL từ {} — những trang này sẽ không tải lại.",
                        replayed, config.urlStoragePath());
            }

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
            UrlFrontier.Task task = frontier.nextUrl(); // URL Frontier
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
    private void processPage(UrlFrontier.Task task, CrawlConfig config) {
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

        // Content Seen? — nhánh "Yes": vứt, KHÔNG bóc liên kết.
        if (contentSeenFilter.seenBefore(doc.getBodyText())) {
            notifyDuplicateContent(task.url());
            return;
        }

        doc.setOutlinks(linkExtractor.extract(task.url(), html)); // Link Extractor

        if (!contentStorage.save(doc)) { // Content Storage
            return; // URL này đã có bản ghi, không đếm trùng
        }

        int count = pagesCrawled.incrementAndGet();
        doc.setDocId(count - 1); // đặc, không thủng lỗ vì cấp SAU khi lưu thành công
        notifyPageCrawled(new CrawlListener.CrawlEvent(
                count, config.maxPages(), task.url(), task.depth(),
                doc.getOutlinks().size(), frontier.size(), frontier.domainCount()));

        // Không tự chặn độ sâu ở đây: đó là việc của khối URL Filter. Chặn cả
        // hai chỗ thì luật độ sâu có hai nguồn sự thật, và bộ đếm
        // getRejectedByDepthCount() luôn bằng 0 nên không phát hiện được khi
        // một trong hai chỗ sai.
        for (String outlink : doc.getOutlinks()) {
            enqueue(outlink, task.depth() + 1);
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
     */
    private void enqueue(String url, int depth) {
        if (!urlFilter.accept(url, depth)) { // URL Filter
            return;
        }
        if (!urlSeenFilter.markSeenIfNew(url)) { // URL Seen? -> URL Storage
            return;
        }
        frontier.addUrl(url, depth, 1); // URL Frontier
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

    public ContentSeenFilter getContentSeenFilter() {
        return contentSeenFilter;
    }
}
