package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.LinkExtractor;
import com.vnsearch.crawler.UrlFilter;
import com.vnsearch.crawler.UrlSeenFilter;
import com.vnsearch.crawler.bus.CrawlEventBus;
import com.vnsearch.crawler.bus.DiscoveredUrl;
import com.vnsearch.crawler.bus.OutlinksExtracted;
import com.vnsearch.crawler.bus.PageEvent;
import com.vnsearch.crawler.bus.PageEventHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * <b>Modular Service 1 — "URL Extractor"</b> trong sơ đồ kiến trúc.
 *
 * <p>Nhận một trang đã sạch từ bus, rồi chạy trọn chặng dưới của sơ đồ:
 *
 * <pre>
 *   [ Kafka ] ──▶ URL Extractor ──▶ URL Filter ──▶ URL Seen Detector ──▶ URL Storage
 *                       │                                   │
 *                       │ outlinks (CHƯA lọc)               │ URL mới (ĐÃ lọc)
 *                       ▼                                   ▼
 *                 Content Storage                      URL Frontier
 *                 (cho PageRank)                       (vòng lặp crawl)
 * </pre>
 *
 * <p><b>Phần việc này trước đây nằm trong {@code CrawlerService.processPage}</b>
 * — bốn dòng gọi {@code linkExtractor.extract} rồi lặp {@code enqueue}. Tách
 * ra thành service riêng được ba thứ:
 *
 * <ol>
 *   <li>Nó co giãn độc lập. Bóc liên kết là việc <b>tốn CPU</b> (phân tích lại
 *       DOM); tải trang là việc <b>chờ mạng</b>. Nhốt chung một tiến trình thì
 *       phải chọn số luồng thoả hiệp cho cả hai; tách ra thì mỗi bên chỉnh
 *       riêng.</li>
 *   <li>Nó hỏng riêng. Một trang có DOM dị dạng làm Jsoup ném ngoại lệ không
 *       còn kéo theo vòng lặp tải trang.</li>
 *   <li>Nó chạy lại được. Ở chế độ Kafka, sửa luật lọc rồi tua lại offset là
 *       bóc lại toàn bộ liên kết của corpus cũ — không phải crawl lại một
 *       trang nào. Đây là thứ mà kiến trúc gọi hàm trực tiếp không làm được,
 *       và cũng là lý do dùng Kafka (log tua lại được) chứ không phải một
 *       hàng đợi công việc (thông điệp tiêu thụ xong là mất).</li>
 * </ol>
 *
 * <h2>Vì sao phân tích lại HTML thay vì nhận sẵn cây DOM</h2>
 *
 * <p>Cây DOM của Jsoup <b>không serialize được</b> — nó là đồ thị đối tượng có
 * tham chiếu vòng (mỗi nút trỏ về cha). Qua Kafka thì bắt buộc phải là HTML
 * thô, và phân tích lại tốn khoảng 3–8 ms mỗi trang. Đó là cái giá thật của
 * việc tách tiến trình, ghi ra đây để không ai tưởng nó miễn phí.
 *
 * <p>Đổi lại, ở chế độ in-process thì crawler đã có sẵn cây DOM và <i>vẫn</i>
 * phải phân tích lại — vì service này chỉ nhận {@link PageEvent}. Đó là lựa
 * chọn có chủ ý: <b>một đường mã duy nhất cho cả hai chế độ</b>. Thêm một
 * đường tắt "nếu in-process thì truyền thẳng Document" sẽ tạo ra hai nhánh
 * hành xử khác nhau, và nhánh chỉ chạy ở môi trường thật là nhánh không được
 * test.
 *
 * <h2>Thứ tự hai phép lọc</h2>
 *
 * <p>{@code URL Filter} chạy trước {@code URL Seen Detector}, giữ nguyên thứ
 * tự của sơ đồ và của mã cũ: luật lọc rẻ (độ sâu, domain, đuôi tệp) loại phần
 * lớn liên kết trước khi phải trả chi phí băm k lần của bộ lọc Bloom.
 *
 * <p>Thread-safe: không giữ trạng thái nào ngoài các bộ đếm nguyên tử;
 * {@link UrlSeenFilter} tự đồng bộ bên trong.
 */
public class UrlExtractorService implements PageEventHandler {

    private static final Logger log = LoggerFactory.getLogger(UrlExtractorService.class);

    private final LinkExtractor linkExtractor;

    /**
     * Lấy bộ lọc <b>tại thời điểm gọi</b>, không giữ tham chiếu cố định.
     *
     * <p>Bắt buộc phải là {@link Supplier}: {@code CrawlerService} cấp phát
     * lại {@link UrlFilter} và {@link UrlSeenFilter} cho <i>từng phiên</i>
     * crawl (chúng phụ thuộc {@code allowedDomains}, {@code maxDepth},
     * {@code maxPages}). Giữ tham chiếu cố định thì service này sẽ mãi mãi
     * dùng bộ lọc của phiên ĐẦU TIÊN — nghĩa là phiên thứ hai lọc theo domain
     * của phiên thứ nhất, và bộ lọc Bloom thì không bao giờ được làm mới nên
     * càng chạy càng báo "đã gặp" cho mọi thứ.
     */
    private final Supplier<UrlFilter> urlFilter;
    private final Supplier<UrlSeenFilter> urlSeenFilter;

    private final CrawlEventBus bus;

    private final AtomicLong pagesProcessed = new AtomicLong();
    private final AtomicLong linksExtracted = new AtomicLong();
    private final AtomicLong linksAccepted = new AtomicLong();
    private final AtomicLong rejectedByFilter = new AtomicLong();
    private final AtomicLong rejectedAsSeen = new AtomicLong();
    private final AtomicLong pagesWithoutHtml = new AtomicLong();

    public UrlExtractorService(LinkExtractor linkExtractor,
                                Supplier<UrlFilter> urlFilter,
                                Supplier<UrlSeenFilter> urlSeenFilter,
                                CrawlEventBus bus) {
        if (linkExtractor == null || urlFilter == null || urlSeenFilter == null || bus == null) {
            throw new IllegalArgumentException(
                    "UrlExtractorService cần đủ linkExtractor, urlFilter, urlSeenFilter và bus");
        }
        this.linkExtractor = linkExtractor;
        this.urlFilter = urlFilter;
        this.urlSeenFilter = urlSeenFilter;
        this.bus = bus;
    }

    @Override
    public void onPage(PageEvent event) {
        if (event.html() == null || event.html().isBlank()) {
            // Không phải lỗi: một PageEvent rút gọn (xem PageEvent.withoutHtml)
            // vẫn hợp lệ cho Analytics. Chỉ service này mới cần DOM.
            pagesWithoutHtml.incrementAndGet();
            return;
        }

        // baseUri PHẢI truyền vào: nó là thứ để absUrl("href") phân giải liên
        // kết tương đối. Thiếu nó thì mọi href dạng "/tin-tuc/bai-1" trả về
        // chuỗi rỗng và trang coi như không có liên kết nào — hỏng lặng lẽ,
        // crawler dừng sau vài trang mà không có lỗi nào được ghi.
        Document document = Jsoup.parse(event.html(), event.url());
        List<String> outlinks = linkExtractor.extract(event.url(), document);

        pagesProcessed.incrementAndGet();
        linksExtracted.addAndGet(outlinks.size());

        // Gửi TRƯỚC khi lọc, và gửi tập ĐẦY ĐỦ: đây là dữ liệu cho PageRank.
        // Xem Javadoc của OutlinksExtracted về việc vì sao không dùng lại tập
        // đã lọc ở dưới.
        bus.publishOutlinks(new OutlinksExtracted(
                event.url(), event.host(), outlinks, event.jobId()));

        int childDepth = event.depth() + 1;
        for (String link : outlinks) {
            if (!urlFilter.get().accept(link, childDepth)) {   // URL Filter
                rejectedByFilter.incrementAndGet();
                continue;
            }
            if (!urlSeenFilter.get().markSeenIfNew(link)) {    // URL Seen? -> URL Storage
                rejectedAsSeen.incrementAndGet();
                continue;
            }
            linksAccepted.incrementAndGet();
            // jobId đi theo suốt chuỗi: trang -> liên kết -> frontier. Mất nó ở
            // một chặng thì URL không biết đường về phiên crawl của mình.
            bus.publishDiscoveredUrl(new DiscoveredUrl(
                    link, hostOf(link), childDepth, event.url(), event.jobId()));
        }

        if (log.isTraceEnabled()) {
            log.trace("URL Extractor: {} -> {} liên kết, {} được chấp nhận",
                    event.url(), outlinks.size(), linksAccepted.get());
        }
    }

    /**
     * Host của một URL, lùi về chính URL khi không phân giải được.
     *
     * <p>Cùng quy ước với {@code UrlFrontier.addUrl}: khoá phân hoạch phải
     * <b>luôn</b> có giá trị, vì {@link DiscoveredUrl} từ chối host rỗng. Một
     * URL dị dạng lọt tới đây thì tự nó làm khoá — nó sẽ nằm một mình trên một
     * phân hoạch nào đó, vô hại.
     */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && !host.isBlank() ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    @Override
    public String handlerName() {
        return "URL Extractor";
    }

    // --- Số liệu cho báo cáo và cho Prometheus ---

    public long getPagesProcessedCount() {
        return pagesProcessed.get();
    }

    public long getLinksExtractedCount() {
        return linksExtracted.get();
    }

    public long getLinksAcceptedCount() {
        return linksAccepted.get();
    }

    public long getRejectedByFilterCount() {
        return rejectedByFilter.get();
    }

    public long getRejectedAsSeenCount() {
        return rejectedAsSeen.get();
    }

    public long getPagesWithoutHtmlCount() {
        return pagesWithoutHtml.get();
    }

    /**
     * Số liên kết trung bình bóc được trên mỗi trang.
     *
     * <p>Con số này là thứ {@code UrlSeenFilter.URLS_SEEN_PER_PAGE} dựa vào để
     * cấp phát bộ lọc Bloom (đang đặt 200, đo được 78,8). Phơi ra đây để lần
     * đo sau không phải viết một công cụ riêng.
     */
    public double getAverageOutlinksPerPage() {
        long pages = pagesProcessed.get();
        return pages == 0 ? 0.0 : (double) linksExtracted.get() / pages;
    }
}
