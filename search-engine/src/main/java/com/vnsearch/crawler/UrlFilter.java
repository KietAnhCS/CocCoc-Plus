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

    private final Set<String> allowedDomains;
    private final int maxDepth;
    private final RobotsTxtParser robotsTxtParser;
    private final String userAgent;

    private final AtomicLong rejectedByDepth = new AtomicLong();
    private final AtomicLong rejectedByScheme = new AtomicLong();
    private final AtomicLong rejectedByDomain = new AtomicLong();
    private final AtomicLong rejectedByExtension = new AtomicLong();
    private final AtomicLong rejectedByRobots = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();

    public UrlFilter(Set<String> allowedDomains, int maxDepth) {
        this(allowedDomains, maxDepth, new RobotsTxtParser(), HtmlDownloader.USER_AGENT);
    }

    public UrlFilter(Set<String> allowedDomains, int maxDepth,
                      RobotsTxtParser robotsTxtParser, String userAgent) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth phải >= 0, nhận được: " + maxDepth);
        }
        this.allowedDomains = allowedDomains == null ? Set.of() : Set.copyOf(allowedDomains);
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

    public long getRejectedByRobotsCount() {
        return rejectedByRobots.get();
    }

    /** Tổng số URL bị loại, gộp mọi nguyên nhân. */
    public long getTotalRejectedCount() {
        return rejectedByDepth.get() + rejectedByScheme.get() + rejectedByDomain.get()
                + rejectedByExtension.get() + rejectedByRobots.get();
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
