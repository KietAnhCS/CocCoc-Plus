package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.bus.PageEvent;
import com.vnsearch.crawler.bus.PageEventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * <b>Modular Service 3 — "Analytics Service"</b> trong sơ đồ kiến trúc.
 *
 * <p>Khối này <b>chưa từng tồn tại</b> trong dự án. Trước đây số liệu crawl
 * nằm rải rác trong bộ đếm của từng khối ({@code HtmlDownloader.downloaded},
 * {@code ContentSeenFilter.duplicates}...), chỉ đọc được bằng cách gọi getter
 * từ trong cùng một JVM, và biến mất khi phiên crawl kết thúc.
 *
 * <p>Service này biến chúng thành thang đo Prometheus — nghĩa là <b>còn lại
 * sau khi phiên crawl kết thúc</b>, vẽ được theo thời gian, và đặt cảnh báo
 * được.
 *
 * <h2>Vì sao host KHÔNG phải là nhãn Prometheus</h2>
 *
 * <p>Đây là quyết định quan trọng nhất trong lớp này, và là cái bẫy phổ biến
 * nhất khi người ta lần đầu gắn thang đo.
 *
 * <p>Viết {@code Counter.builder("crawl.pages").tag("host", host)} trông rất
 * tự nhiên. Nhưng Prometheus tạo <b>một chuỗi thời gian riêng cho mỗi tổ hợp
 * nhãn</b>, và mỗi chuỗi tốn khoảng 1–3&nbsp;KB bộ nhớ thường trú trên máy
 * chủ Prometheus. Một phiên crawl chạm 30.000 host phân biệt sẽ tạo 30.000
 * chuỗi từ <i>một</i> thang đo — vài chục MB cho một con số. Và host là dữ
 * liệu <b>do bên ngoài quyết định</b>: crawler không kiểm soát được nó sẽ gặp
 * bao nhiêu host, nên đây là một nhãn có lực lượng <i>không chặn trên
 * được</i>. Đó là cách kinh điển để giết một máy chủ Prometheus.
 *
 * <p>Cách làm ở đây:
 *
 * <table border="1">
 *   <caption>Chiều nào đi đâu</caption>
 *   <tr><th>Chiều</th><th>Lực lượng</th><th>Đi đâu</th></tr>
 *   <tr><td>ngôn ngữ</td><td>3 ({@code vi}, {@code en}, {@code und})</td><td><b>Nhãn Prometheus</b></td></tr>
 *   <tr><td>host</td><td>không chặn trên</td><td>Bảng trong bộ nhớ, có trần, phơi qua API quản trị</td></tr>
 * </table>
 *
 * <p>Nguyên tắc rút ra: <i>nhãn dành cho chiều có lực lượng nhỏ và biết
 * trước; chiều lực lượng lớn thì tổng hợp trước khi phơi.</i>
 *
 * <h2>Trần cho bảng host</h2>
 *
 * <p>{@link #MAX_TRACKED_HOSTS} chặn chính bảng trong bộ nhớ — nếu không thì
 * ta chỉ chuyển bài toán nổ bộ nhớ từ Prometheus sang JVM của mình. Khi đầy,
 * host mới không được thêm nữa (số trang của chúng vẫn vào tổng), vì các host
 * xuất hiện sớm gần như luôn là các host đáng quan tâm — crawler đi theo bề
 * rộng nên seed và các site lớn xuất hiện trước.
 *
 * <h2>Vì sao {@link LongAdder} chứ không phải {@link AtomicLong}</h2>
 *
 * <p>Bảng host bị <b>mọi</b> worker thread ghi vào cho <b>mọi</b> trang.
 * {@code AtomicLong} dùng vòng lặp CAS trên một ô nhớ duy nhất, nên khi nhiều
 * luồng cùng tăng một bộ đếm thì phần lớn thời gian trôi vào việc thử lại.
 * {@code LongAdder} rải phép cộng ra nhiều ô rồi mới cộng lại lúc đọc — đúng
 * ca dùng "ghi rất nhiều, đọc rất hiếm" của một bộ đếm thống kê.
 *
 * <p>Thread-safe hoàn toàn.
 */
public class CrawlAnalyticsService implements PageEventHandler {

    /** Trần số host theo dõi riêng — xem Javadoc lớp. */
    public static final int MAX_TRACKED_HOSTS = 10_000;

    private final MeterRegistry registry;

    /**
     * Bộ đếm theo ngôn ngữ, tạo sẵn theo yêu cầu.
     *
     * <p>Ngôn ngữ có lực lượng nhỏ và biết trước ({@code vi}, {@code en},
     * {@code und}) nên an toàn làm nhãn. Vẫn dùng map để không phải sửa mã khi
     * {@code LanguageFilter} nhận thêm một mã ngôn ngữ.
     */
    private final Map<String, Counter> pagesByLanguage = new ConcurrentHashMap<>();

    /** host -> số trang. Có trần, KHÔNG đẩy sang Prometheus. */
    private final Map<String, LongAdder> pagesByHost = new ConcurrentHashMap<>();

    private final DistributionSummary pageSizeBytes;
    private final DistributionSummary bodyTextLength;
    private final DistributionSummary imagesPerPage;

    private final Counter imagesTotal;
    private final Counter imagesMissingAlt;

    private final AtomicLong pagesTotal = new AtomicLong();
    private final AtomicLong maxDepthSeen = new AtomicLong();
    private final AtomicLong hostsDropped = new AtomicLong();

    /** Số ảnh của trang đang xử lý, gom lại để đưa vào {@link #imagesPerPage}. */
    private final Map<String, LongAdder> imagesOfPage = new ConcurrentHashMap<>();

    public CrawlAnalyticsService(MeterRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("CrawlAnalyticsService cần một MeterRegistry");
        }
        this.registry = registry;

        // Histogram chứ không chỉ trung bình: phân bố kích thước trang có đuôi
        // rất dài (vài KB tới vài MB), và trung bình của một phân bố như vậy
        // không mô tả được trang nào cả. publishPercentileHistogram đẩy cả
        // histogram sang Prometheus nên phân vị cộng dồn được từ nhiều bản sao
        // — cùng lý do đã ghi trong application.properties cho độ trễ HTTP.
        this.pageSizeBytes = DistributionSummary.builder("vnsearch.crawl.page.size.bytes")
                .description("Kich thuoc HTML tho cua trang da crawl")
                .baseUnit("bytes")
                .publishPercentileHistogram()
                .register(registry);

        this.bodyTextLength = DistributionSummary.builder("vnsearch.crawl.page.text.chars")
                .description("So ky tu than bai sau khi boc")
                .register(registry);

        this.imagesPerPage = DistributionSummary.builder("vnsearch.crawl.page.images")
                .description("So anh tim thay tren moi trang")
                .register(registry);

        this.imagesTotal = Counter.builder("vnsearch.crawl.images.total")
                .description("Tong so anh tim thay")
                .register(registry);

        this.imagesMissingAlt = Counter.builder("vnsearch.crawl.images.missing.alt.total")
                .description("So anh khong co thuoc tinh alt")
                .register(registry);

        // Gauge nhận HÀM lấy giá trị, không phải giá trị. Đẩy một con số vào
        // lúc khởi tạo thì thang đo đóng băng ở thời điểm đó — cùng bài học đã
        // ghi trong MetricsConfig.
        Gauge.builder("vnsearch.crawl.pages.total", pagesTotal, AtomicLong::get)
                .description("Tong so trang da qua Analytics Service")
                .register(registry);

        Gauge.builder("vnsearch.crawl.hosts.distinct", pagesByHost, Map::size)
                .description("So host phan biet da gap (chan tren "
                        + MAX_TRACKED_HOSTS + ")")
                .register(registry);

        Gauge.builder("vnsearch.crawl.depth.max", maxDepthSeen, AtomicLong::get)
                .description("Do sau BFS lon nhat da cham toi")
                .register(registry);
    }

    @Override
    public void onPage(PageEvent event) {
        pagesTotal.incrementAndGet();

        // Nhãn ngôn ngữ: lực lượng nhỏ, an toàn. Chuẩn hoá chuỗi rỗng thành
        // "und" để không sinh một nhãn rỗng khó đọc trên bảng điều khiển.
        String language = event.language() == null || event.language().isBlank()
                ? "und" : event.language();
        pagesByLanguage.computeIfAbsent(language, lang -> Counter
                        .builder("vnsearch.crawl.pages.by.language.total")
                        .tag("language", lang)
                        .description("So trang da crawl theo ngon ngu")
                        .register(registry))
                .increment();

        pageSizeBytes.record(event.htmlSizeBytes());
        if (event.bodyText() != null) {
            bodyTextLength.record(event.bodyText().length());
        }

        trackHost(event.host());

        // updateAndGet chứ không phải if-rồi-set: hai worker cùng thấy độ sâu
        // lớn hơn có thể ghi đè nhau và giữ lại giá trị nhỏ hơn.
        maxDepthSeen.updateAndGet(current -> Math.max(current, event.depth()));

        // Chốt số ảnh của trang TRƯỚC đó nếu có — xem onImage.
        LongAdder counted = imagesOfPage.remove(event.url());
        if (counted != null) {
            imagesPerPage.record(counted.sum());
        }
    }

    /**
     * Nhận đầu ra của {@link ImageDownloadService}.
     *
     * <p>Đây là chỗ hai Modular Service <b>gặp nhau qua bus</b> chứ không gọi
     * thẳng nhau: Image Download phát {@link ImageFound}, Analytics đăng ký
     * nhận. Nhờ vậy tắt Image Download đi thì Analytics vẫn chạy bình thường,
     * chỉ thiếu mấy con số về ảnh — không có lời gọi nào gãy.
     */
    public void onImage(ImageFound image) {
        imagesTotal.increment();
        if (image.missingAlt()) {
            imagesMissingAlt.increment();
        }
        imagesOfPage.computeIfAbsent(image.pageUrl(), url -> new LongAdder()).increment();
    }

    /** Ghi nhận một trang cho host, tôn trọng trần {@link #MAX_TRACKED_HOSTS}. */
    private void trackHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        LongAdder counter = pagesByHost.get(host);
        if (counter != null) {
            counter.increment();
            return;
        }
        // Kiểm tra trần rồi mới thêm. Có một cửa sổ đua nhỏ ở đây — hai luồng
        // cùng thấy size() == MAX-1 và cùng thêm — nên bảng có thể vượt trần
        // vài mục. Chấp nhận được: đây là bảng thống kê, không phải bất biến
        // an toàn, và một khoá quanh đường nóng này đắt hơn nhiều so với vài
        // mục thừa.
        if (pagesByHost.size() >= MAX_TRACKED_HOSTS) {
            hostsDropped.incrementAndGet();
            return;
        }
        pagesByHost.computeIfAbsent(host, h -> new LongAdder()).increment();
    }

    @Override
    public String handlerName() {
        return "Analytics Service";
    }

    // --- Đọc số liệu cho API quản trị và cho báo cáo ---

    public long getPagesTotal() {
        return pagesTotal.get();
    }

    public int getDistinctHostCount() {
        return pagesByHost.size();
    }

    /** Số host bị bỏ qua vì bảng đã chạm trần. */
    public long getHostsDroppedCount() {
        return hostsDropped.get();
    }

    public long getMaxDepthSeen() {
        return maxDepthSeen.get();
    }

    /**
     * {@code n} host có nhiều trang nhất — thứ mà nhãn Prometheus không làm
     * được nhưng người vận hành vẫn cần.
     *
     * <p>Sắp xếp tại thời điểm gọi thay vì giữ một cấu trúc luôn có thứ tự:
     * hàm này được gọi vài lần một ngày từ API quản trị, còn phép ghi thì hàng
     * chục nghìn lần một phút. Tối ưu cho phía ghi là lựa chọn đúng.
     */
    public Map<String, Long> topHosts(int n) {
        return pagesByHost.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(
                        e -> e.getValue().sum()).reversed())
                .limit(Math.max(0, n))
                .collect(LinkedHashMap::new,
                        (map, e) -> map.put(e.getKey(), e.getValue().sum()),
                        LinkedHashMap::putAll);
    }

    /** Bản tóm tắt gọn cho {@code /api/admin/stats}. */
    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pagesTotal", pagesTotal.get());
        stats.put("distinctHosts", pagesByHost.size());
        stats.put("hostsDropped", hostsDropped.get());
        stats.put("maxDepth", maxDepthSeen.get());
        stats.put("topHosts", topHosts(10));
        return stats;
    }

    /** Danh sách ngôn ngữ đã gặp — dùng trong test và trong báo cáo. */
    public List<String> languagesSeen() {
        return pagesByLanguage.keySet().stream().sorted().toList();
    }
}
