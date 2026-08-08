package com.vnsearch.crawler;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Khối "URL Filter"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p>Trước đây các phép lọc này nằm rải rác thành một biểu thức {@code if}
 * ba vế bên trong vòng lặp worker của {@code CrawlerService}. Gom lại thành
 * một lớp cho ba lợi ích: mỗi khối trong sơ đồ ứng với một lớp, luật lọc
 * kiểm thử được mà không phải chạy crawl thật, và <b>đếm được</b> từng
 * nguyên nhân loại bỏ — số liệu đưa thẳng vào báo cáo.
 *
 * <p><b>Hai mức lọc, tách riêng vì chi phí chênh nhau rất xa.</b>
 * <ul>
 *   <li>{@link #accept(String, int)} — chỉ so sánh số nguyên và phân tích
 *       chuỗi URL, không chạm mạng. Gọi cho <b>mọi</b> liên kết bóc được,
 *       tức hàng trăm lần cho mỗi trang tải về.</li>
 *   <li>{@link #isAllowedByRobots(String)} — có thể phải tải
 *       {@code robots.txt} qua mạng ở lần đầu gặp một host. Chỉ gọi ngay
 *       trước khi thật sự tải một trang.</li>
 * </ul>
 * Gộp hai mức làm một sẽ khiến mỗi liên kết bóc được đều kéo theo một lần
 * tra robots — vô nghĩa với những liên kết bị loại ngay từ luật rẻ nhất.
 *
 * <p><b>Thứ tự kiểm tra trong {@link #accept}</b> đi theo chi phí tăng dần:
 * so sánh độ sâu (một phép so sánh số nguyên) → phân tích URI → so khớp
 * domain → xét đuôi tệp. Java đánh giá ngắn mạch nên phép đắt chỉ chạy khi
 * các phép rẻ đều không loại được.
 *
 * <p>Thread-safe: cấu hình bất biến, chỉ có các bộ đếm nguyên tử thay đổi.
 */
public class UrlFilter {

    /**
     * Đuôi tệp chắc chắn không phải trang HTML.
     *
     * <p>Không lọc thì crawler sẽ tải ảnh, video, tệp nén rồi giao cho
     * {@link ContentParser} — thứ chỉ biết đọc HTML — và nhận về tài liệu
     * rỗng. Trong một phiên crawl báo điện tử, ảnh chiếm phần lớn số liên
     * kết bóc được, nên đây là phép lọc tiết kiệm băng thông nhiều nhất.
     */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            // ảnh
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tif", "tiff",
            // tài nguyên tĩnh của trang
            "css", "js", "json", "xml", "rss", "atom", "woff", "woff2", "ttf", "eot",
            // tài liệu
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "csv",
            // nén và cài đặt
            "zip", "rar", "7z", "tar", "gz", "bz2", "exe", "msi", "apk", "dmg", "iso",
            // đa phương tiện
            "mp3", "mp4", "avi", "mkv", "mov", "wmv", "flv", "wav", "m4a", "webm");

    /**
     * Tiền tố host của các bản ngoại ngữ <b>không phải tiếng Việt, không phải
     * tiếng Anh</b> mà báo Việt Nam xuất bản trên subdomain riêng.
     *
     * <p><b>Chính sách corpus: chỉ giữ tiếng Việt và tiếng Anh.</b> Danh sách
     * này là <b>tuyến phòng thủ thứ nhất</b> — rẻ nhất, vì nó loại URL <i>trước
     * khi tải</i>, chỉ bằng vài phép so sánh chuỗi. Tuyến thứ hai là
     * {@link LanguageFilter}, nhìn nội dung thật sau khi tải; nó bắt được mọi
     * thứ danh sách này bỏ sót, nhưng phải trả giá bằng một lượt tải trang.
     *
     * <p><b>Vì sao chúng lọt vào nếu không chặn.</b> {@link #isAllowedDomain}
     * khớp bằng {@code host.endsWith(domain)}, nên hạt giống {@code nhandan.vn}
     * kéo theo cả {@code cn.nhandan.vn}. Frontier lại chia lượt <b>công bằng
     * theo host</b> ({@code BackQueues}, mỗi host một hàng đợi), nên mỗi bản
     * ngôn ngữ nhận đúng bằng phần của bản tiếng Việt: đo trên phiên crawl
     * 30.001 trang được <b>2.533 trang (8,4%)</b> thuộc ba host
     * {@code cn.nhandan.vn}, {@code zh.vietnamplus.vn}, {@code cn.baochinhphu.vn}
     * — và đó mới chỉ là ba bản tiếng Trung.
     *
     * <p><b>Vì sao tiếng Trung và tiếng Nhật là ca tệ nhất trong số này.</b>
     * Đo bằng chính {@code VietnameseTokenizer} trên tiêu đề thật lấy từ corpus:
     *
     * <pre>
     *   Việt   "Văn hóa là động lực và nguồn lực phát triển quan trọng"
     *          ->  5 token / 43 ký tự   [văn_hóa][động_lực][nguồn_lực]...   TỐT
     *   Anh    "Viet Nam records best-ever result at International Physics..."
     *          -> 11 token / 63 ký tự   [viet][nam][records][best]...       TỐT
     *   Nga    "Высокие цены на личи: Бакнинь получил более 2,6 трлн..."
     *          -> 12 token / 56 ký tự                                       TÁCH ĐƯỢC
     *   Trung  "越南国会常务委员会会议：提交国会审议通过设立广宁市和北宁市决议"
     *          ->  2 token / 31 ký tự   [越南国会常务委员会会议][提交国会审议...]  HỎNG
     * </pre>
     *
     * Chữ Trung/Nhật không đặt dấu cách giữa các từ, nên
     * {@code splitIntoSyllables} trả về nguyên một mệnh đề làm <b>một token 19
     * ký tự</b> — tài liệu nằm trong chỉ mục, làm tăng {@code N} trong công
     * thức IDF của mọi term khác, nhưng <b>vĩnh viễn không thể được tìm thấy</b>.
     * Bản tiếng Nga, Hàn, Pháp thì tách token bình thường; loại chúng là vì
     * chính sách corpus vi/en, không phải vì tokenizer hỏng.
     *
     * <p><b>Danh sách này cố ý ngắn và bảo thủ.</b> Chỉ gồm những tiền tố mà
     * các toà soạn trong tập hạt giống thật sự dùng. Không có {@code it.},
     * {@code id.}, {@code my.} — chúng dễ trùng với subdomain nội bộ
     * ({@code it.} của phòng công nghệ thông tin), mà loại nhầm thì mất trang
     * tiếng Việt thật. Những ca hiếm để {@link LanguageFilter} dọn.
     *
     * <p><b>Không chặn {@code en.} và {@code e.}</b> — đó chính là các bản tiếng
     * Anh mà corpus muốn có.
     */
    public static final Set<String> NON_VI_EN_HOST_PREFIXES = Set.of(
            "cn.", "zh.",           // tieng Trung
            "ja.", "jp.",           // tieng Nhat
            "ko.", "kr.",           // tieng Han
            "ru.",                  // tieng Nga
            "fr.",                  // tieng Phap
            "es.",                  // tieng Tay Ban Nha
            "de.",                  // tieng Duc
            "pt.",                  // tieng Bo Dao Nha
            "ar.",                  // tieng A Rap
            "th.",                  // tieng Thai
            "lo.", "km.");          // tieng Lao, tieng Khmer

    private final Set<String> allowedDomains;
    private final Set<String> excludedHostPrefixes;
    private final int maxDepth;
    private final RobotsTxtParser robotsTxtParser;
    private final String userAgent;

    private final AtomicLong rejectedByDepth = new AtomicLong();
    private final AtomicLong rejectedByScheme = new AtomicLong();
    private final AtomicLong rejectedByDomain = new AtomicLong();
    private final AtomicLong rejectedByHostPrefix = new AtomicLong();
    private final AtomicLong rejectedByExtension = new AtomicLong();
    private final AtomicLong rejectedByRobots = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();

    public UrlFilter(Set<String> allowedDomains, int maxDepth) {
        this(allowedDomains, maxDepth, Set.of(), new RobotsTxtParser(), HtmlDownloader.USER_AGENT);
    }

    public UrlFilter(Set<String> allowedDomains, int maxDepth, Set<String> excludedHostPrefixes) {
        this(allowedDomains, maxDepth, excludedHostPrefixes,
                new RobotsTxtParser(), HtmlDownloader.USER_AGENT);
    }

    public UrlFilter(Set<String> allowedDomains, int maxDepth,
                      RobotsTxtParser robotsTxtParser, String userAgent) {
        this(allowedDomains, maxDepth, Set.of(), robotsTxtParser, userAgent);
    }

    public UrlFilter(Set<String> allowedDomains, int maxDepth, Set<String> excludedHostPrefixes,
                      RobotsTxtParser robotsTxtParser, String userAgent) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth phải >= 0, nhận được: " + maxDepth);
        }
        this.allowedDomains = allowedDomains == null ? Set.of() : Set.copyOf(allowedDomains);
        this.excludedHostPrefixes =
                excludedHostPrefixes == null ? Set.of() : Set.copyOf(excludedHostPrefixes);
        this.maxDepth = maxDepth;
        this.robotsTxtParser = robotsTxtParser;
        this.userAgent = userAgent;
    }

    /**
     * Luật lọc rẻ, <b>không chạm mạng</b>: độ sâu, giao thức, domain, đuôi tệp.
     *
     * @return {@code true} nếu URL xứng đáng được xếp vào hàng đợi
     */
    public boolean accept(String url, int depth) {
        if (depth > maxDepth) {
            rejectedByDepth.incrementAndGet();
            return false;
        }
        if (url == null || url.isBlank()) {
            rejectedByScheme.incrementAndGet();
            return false;
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            rejectedByScheme.incrementAndGet();
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            rejectedByScheme.incrementAndGet();
            return false;
        }

        String host = uri.getHost();
        if (host == null) {
            rejectedByScheme.incrementAndGet();
            return false;
        }
        if (!isAllowedDomain(host)) {
            rejectedByDomain.incrementAndGet();
            return false;
        }
        if (hasExcludedHostPrefix(host)) {
            rejectedByHostPrefix.incrementAndGet();
            return false;
        }
        if (hasBlockedExtension(uri.getRawPath())) {
            rejectedByExtension.incrementAndGet();
            return false;
        }

        accepted.incrementAndGet();
        return true;
    }

    /**
     * Luật lọc <b>đắt</b>: hỏi {@code robots.txt} của host. Lần đầu gặp một
     * host có thể phải tải qua mạng; các lần sau lấy từ cache của
     * {@link RobotsTxtParser}.
     */
    public boolean isAllowedByRobots(String url) {
        boolean allowed = robotsTxtParser.isAllowed(userAgent, url);
        if (!allowed) {
            rejectedByRobots.incrementAndGet();
        }
        return allowed;
    }

    /** Tập rỗng nghĩa là KHÔNG giới hạn domain. */
    private boolean isAllowedDomain(String host) {
        if (allowedDomains.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String domain : allowedDomains) {
            if (lower.endsWith(domain.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loại các subdomain ngoại ngữ — xem {@link #NON_VI_EN_HOST_PREFIXES}.
     *
     * <p>Khớp theo tiền tố có <b>dấu chấm</b> ({@code "en."} chứ không phải
     * {@code "en"}) để {@code enviro.example.vn} hay {@code endorse.example.vn}
     * không bị loại oan.
     */
    private boolean hasExcludedHostPrefix(String host) {
        if (excludedHostPrefixes.isEmpty()) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String prefix : excludedHostPrefixes) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Xét đuôi tệp của đoạn cuối đường dẫn; đường dẫn không có dấu chấm thì cho qua. */
    private boolean hasBlockedExtension(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        int dot = lastSegment.lastIndexOf('.');
        if (dot < 0 || dot == lastSegment.length() - 1) {
            return false;
        }
        String extension = lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BLOCKED_EXTENSIONS.contains(extension);
    }

    public long getAcceptedCount() {
        return accepted.get();
    }

    public long getRejectedByDepthCount() {
        return rejectedByDepth.get();
    }

    public long getRejectedBySchemeCount() {
        return rejectedByScheme.get();
    }

    public long getRejectedByDomainCount() {
        return rejectedByDomain.get();
    }

    public long getRejectedByExtensionCount() {
        return rejectedByExtension.get();
    }

    /** Số URL bị loại vì thuộc subdomain ngoại ngữ. */
    public long getRejectedByHostPrefixCount() {
        return rejectedByHostPrefix.get();
    }

    public long getRejectedByRobotsCount() {
        return rejectedByRobots.get();
    }

    /** Tổng số URL bị loại, gộp mọi nguyên nhân. */
    public long getTotalRejectedCount() {
        return rejectedByDepth.get() + rejectedByScheme.get() + rejectedByDomain.get()
                + rejectedByHostPrefix.get() + rejectedByExtension.get() + rejectedByRobots.get();
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        UrlFilter filter = new UrlFilter(Set.of("vnexpress.net"), 3);

        System.out.println("Bài viết hợp lệ      : " + filter.accept("https://vnexpress.net/bai-1.html", 1));
        System.out.println("Ngoài domain cho phép: " + filter.accept("https://facebook.com/x", 1));
        System.out.println("Ảnh (đuôi bị chặn)   : " + filter.accept("https://vnexpress.net/anh.jpg", 1));
        System.out.println("Sâu quá maxDepth     : " + filter.accept("https://vnexpress.net/bai-2.html", 9));
        System.out.println("Không phải http(s)   : " + filter.accept("mailto:toasoan@vnexpress.net", 1));

        System.out.println();
        System.out.println("Đã nhận       : " + filter.getAcceptedCount());
        System.out.println("Loại vì domain: " + filter.getRejectedByDomainCount());
        System.out.println("Loại vì đuôi  : " + filter.getRejectedByExtensionCount());
        System.out.println("Loại vì độ sâu: " + filter.getRejectedByDepthCount());
        System.out.println("Loại vì scheme: " + filter.getRejectedBySchemeCount());
    }
}
