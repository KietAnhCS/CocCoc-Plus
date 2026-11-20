package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.DnsResolver;
import com.vnsearch.crawler.HtmlDownloader;
import com.vnsearch.crawler.SeedUrlValidator;
import com.vnsearch.crawler.UrlCanonicalizer;
import com.vnsearch.crawler.bus.CrawlEventBus;
import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.bus.PageEvent;
import com.vnsearch.crawler.bus.PageEventHandler;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Modular Service 2 — "Image Download"</b> trong sơ đồ kiến trúc.
 *
 * <p>Khối này <b>chưa từng tồn tại</b> trong dự án: crawler cũ vứt bỏ hoàn
 * toàn thẻ {@code <img>}. Đây là service mới, và nó là ví dụ điển hình cho
 * việc "vì sao phải tách tiến trình" — nội dung ảnh không đóng góp gì cho chỉ
 * mục văn bản, nhưng nếu để chung tiến trình thì nó là thứ tiêu băng thông
 * lớn nhất và sẽ bóp nghẹt việc tải trang.
 *
 * <h2>Hai chế độ, mặc định là chế độ an toàn</h2>
 *
 * <table border="1">
 *   <caption>{@code app.crawler.images.download}</caption>
 *   <tr><th></th><th>{@code false} (mặc định)</th><th>{@code true}</th></tr>
 *   <tr><td>Việc làm</td><td>Bóc siêu dữ liệu từ DOM</td><td>Bóc + tải nội dung</td></tr>
 *   <tr><td>Băng thông</td><td>0</td><td>~100–500 KB mỗi ảnh</td></tr>
 *   <tr><td>Bề mặt SSRF</td><td>Không mở kết nối nào</td><td>Có — xem phần dưới</td></tr>
 *   <tr><td>Cho ra</td><td>url, alt, kích thước khai báo</td><td>thêm kích thước thật + SHA-256</td></tr>
 * </table>
 *
 * <p>Ngay ở chế độ mặc định service vẫn có ích thật: đếm ảnh theo host, phát
 * hiện ảnh thiếu {@code alt} (tín hiệu chất lượng trang, và cũng là tiêu chí
 * tiếp cận), và ghi lại địa chỉ để một lần chạy sau có thể tải nếu cần — nhờ
 * Kafka giữ log, việc "chạy sau" đó không cần crawl lại trang nào.
 *
 * <h2>SSRF: vì sao KHÔNG tự mở kết nối</h2>
 *
 * <p>Mỗi lần tải ảnh là một lần mở kết nối tới địa chỉ do <b>trang đích</b>
 * chỉ định. Đó chính xác là đường tấn công mà {@link HtmlDownloader} đã phải
 * vá cho HTML:
 *
 * <pre>
 *   &lt;img src="http://169.254.169.254/latest/meta-data/iam/"&gt;
 *        ↑ crawler tự tải = tự gửi request vào hạ tầng nội bộ
 * </pre>
 *
 * <p>Nên khi bật tải, lớp này dùng lại <b>đúng</b> {@link SeedUrlValidator} và
 * {@link DnsResolver} mà {@code HtmlDownloader} dùng, chứ không viết bản kiểm
 * tra thứ hai. Javadoc của {@code HtmlDownloader} đã nêu lý do và nó áp
 * nguyên vẹn ở đây: hai cài đặt song song của cùng một quy tắc bảo mật thì
 * sớm muộn cũng lệch nhau, và bản bị quên cập nhật chính là lỗ hổng.
 *
 * <p>Ngoài ra {@code followRedirects(false)}: một ảnh chuyển hướng vào mạng
 * nội bộ là cách vòng qua phép kiểm tra chặng đầu. Ở đây không đi theo chuyển
 * hướng chút nào — ảnh không đáng để mở thêm bề mặt tấn công.
 *
 * <h2>Ba trần chặn</h2>
 *
 * <ul>
 *   <li>{@link #maxImagesPerPage} — một trang thư viện ảnh có thể có hàng
 *       nghìn thẻ {@code <img>}; không chặn thì một trang duy nhất sinh ra
 *       hàng nghìn thông điệp.</li>
 *   <li>{@link #maxImageBytes} — chặn ngay ở tầng Jsoup
 *       ({@code maxBodySize}), nên một tệp 2&nbsp;GB đội lốt ảnh bị cắt chứ
 *       không nuốt hết bộ nhớ.</li>
 *   <li>Đuôi tệp phải nằm trong {@link #IMAGE_EXTENSIONS} — loại thẳng những
 *       {@code src} trỏ tới tài liệu hoặc mã.</li>
 * </ul>
 *
 * <p>Thread-safe: chỉ giữ cấu hình bất biến và các bộ đếm nguyên tử.
 */
public class ImageDownloadService implements PageEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageDownloadService.class);

    /**
     * Đuôi tệp được coi là ảnh.
     *
     * <p>Lọc theo đuôi chứ không theo {@code Content-Type}: biết được
     * Content-Type thì đã phải mở kết nối rồi, mà mục đích của phép lọc này
     * chính là để <b>khỏi</b> mở kết nối.
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp", ".svg");

    public static final int DEFAULT_MAX_IMAGES_PER_PAGE = 50;
    public static final long DEFAULT_MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final int DEFAULT_TIMEOUT_MS = 8_000;

    private final CrawlEventBus bus;
    private final DnsResolver dnsResolver;
    private final boolean downloadEnabled;
    private final int maxImagesPerPage;
    private final long maxImageBytes;
    private final int timeoutMs;

    private final AtomicLong pagesProcessed = new AtomicLong();
    private final AtomicLong imagesFound = new AtomicLong();
    private final AtomicLong imagesSkippedByExtension = new AtomicLong();
    private final AtomicLong imagesOverPageLimit = new AtomicLong();
    private final AtomicLong imagesMissingAlt = new AtomicLong();
    private final AtomicLong imagesDownloaded = new AtomicLong();
    private final AtomicLong imagesBlocked = new AtomicLong();
    private final AtomicLong downloadFailures = new AtomicLong();
    private final AtomicLong bytesDownloaded = new AtomicLong();

    /** Chế độ mặc định: chỉ siêu dữ liệu, không mở kết nối nào. */
    public ImageDownloadService(CrawlEventBus bus) {
        this(bus, new DnsResolver(), false, DEFAULT_MAX_IMAGES_PER_PAGE,
                DEFAULT_MAX_IMAGE_BYTES, DEFAULT_TIMEOUT_MS);
    }

    public ImageDownloadService(CrawlEventBus bus, DnsResolver dnsResolver,
                                 boolean downloadEnabled, int maxImagesPerPage,
                                 long maxImageBytes, int timeoutMs) {
        if (bus == null) {
            throw new IllegalArgumentException("ImageDownloadService cần một bus");
        }
        if (maxImagesPerPage <= 0) {
            throw new IllegalArgumentException(
                    "maxImagesPerPage phải > 0, nhận được: " + maxImagesPerPage);
        }
        if (maxImageBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxImageBytes phải > 0, nhận được: " + maxImageBytes);
        }
        this.bus = bus;
        this.dnsResolver = dnsResolver == null ? new DnsResolver() : dnsResolver;
        this.downloadEnabled = downloadEnabled;
        this.maxImagesPerPage = maxImagesPerPage;
        this.maxImageBytes = maxImageBytes;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void onPage(PageEvent event) {
        if (event.html() == null || event.html().isBlank()) {
            return;
        }
        pagesProcessed.incrementAndGet();

        Document document = Jsoup.parse(event.html(), event.url());

        // LinkedHashSet: một trang thường lặp cùng một ảnh (bản thường + bản
        // srcset + ảnh nền). Giữ thứ tự xuất hiện để kết quả ổn định giữa các
        // lần chạy — cùng lý do mà LinkExtractor dùng cấu trúc này.
        Set<String> seen = new LinkedHashSet<>();
        int emitted = 0;

        // `img` trần, KHÔNG phải `img[src]`: ảnh lazy-load có thể chỉ có
        // `data-src` — xem resolveSource().
        for (Element img : document.select("img")) {
            String raw = resolveSource(img);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String imageUrl = UrlCanonicalizer.canonicalize(raw);
            if (imageUrl == null || imageUrl.isBlank() || !seen.add(imageUrl)) {
                continue;
            }
            if (!hasImageExtension(imageUrl)) {
                imagesSkippedByExtension.incrementAndGet();
                continue;
            }
            if (emitted >= maxImagesPerPage) {
                imagesOverPageLimit.incrementAndGet();
                continue;
            }

            String alt = img.attr("alt");
            ImageFound found = describe(event, imageUrl, alt,
                    parseDimension(img.attr("width")), parseDimension(img.attr("height")));

            imagesFound.incrementAndGet();
            if (found.missingAlt()) {
                imagesMissingAlt.incrementAndGet();
            }
            bus.publishImage(found);
            emitted++;
        }
    }

    /**
     * Địa chỉ THẬT của một ảnh, xử lý được cả ảnh nạp trễ (lazy loading).
     *
     * <h3>Vì sao không thể chỉ đọc {@code src}</h3>
     *
     * <p>Báo điện tử Việt Nam nạp ảnh bằng JavaScript để trang hiện nhanh: thẻ
     * {@code <img>} mang {@code class="lazy"}, thuộc tính {@code src} chứa một
     * ảnh giữ chỗ (hoặc một chuỗi mẫu JavaScript), còn địa chỉ thật nằm ở
     * {@code data-src} và chỉ được gán khi người dùng cuộn tới.
     *
     * <p>Đo trên trang chủ vnexpress.net: <b>22 trong 31</b> thẻ {@code <img>}
     * có {@code data-src}. Chỉ đọc {@code src} thì thứ duy nhất lọt vào kho là
     * <b>logo và icon của site</b> — đúng triệu chứng đã quan sát được: tab
     * Hình ảnh đầy những ảnh không liên quan.
     *
     * <p>Crawler này <b>không chạy JavaScript</b> (nó dùng Jsoup, không phải
     * trình duyệt thật), nên nó phải tự hiểu quy ước lazy loading. Đó là cái
     * giá của việc chọn một bộ phân tích HTML thay vì điều khiển một trình
     * duyệt — rẻ hơn hàng chục lần về tài nguyên, đổi lại phải biết vài quy
     * ước như thế này.
     *
     * <h3>Thứ tự ưu tiên</h3>
     *
     * <p>{@code data-src} đứng TRƯỚC {@code src}: khi có cả hai thì {@code src}
     * chính là ảnh giữ chỗ. Lấy nhầm nó sẽ cho ra hàng loạt bản ghi trỏ tới
     * cùng một ảnh mờ 1×1.
     *
     * <p>{@code data-original} là quy ước của thư viện jQuery Lazy Load cũ,
     * vẫn còn gặp trên các site đời trước.
     */
    private static String resolveSource(Element img) {
        for (String attr : new String[] {"data-src", "data-original", "src"}) {
            if (!img.hasAttr(attr)) {
                continue;
            }
            // absUrl phân giải theo baseUri của tài liệu, nên nó xử lý được cả
            // đường dẫn tương đối lẫn dạng "//cdn.example.com/anh.jpg".
            String resolved = img.absUrl(attr);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * Dựng bản ghi cho một ảnh — tải nội dung nếu và chỉ nếu đã bật.
     *
     * <p>Mọi lỗi tải đều bị nuốt và lùi về bản ghi siêu dữ liệu: một ảnh hỏng
     * không phải lý do để mất luôn thông tin rằng trang đó <i>có</i> ảnh ấy.
     */
    private ImageFound describe(PageEvent event, String imageUrl, String alt,
                                 int width, int height) {
        ImageFound metadata = ImageFound.metadataOnly(
                event.url(), event.host(), imageUrl, alt, width, height);
        if (!downloadEnabled) {
            return metadata;
        }
        try {
            byte[] body = fetchImage(imageUrl);
            imagesDownloaded.incrementAndGet();
            bytesDownloaded.addAndGet(body.length);
            return new ImageFound(event.url(), event.host(), imageUrl, alt, width, height,
                    body.length, sha256Hex(body));
        } catch (BlockedImageException e) {
            imagesBlocked.incrementAndGet();
            log.warn("Chan tai anh {} tu trang {}: {}", imageUrl, event.url(), e.getMessage());
            return metadata;
        } catch (Exception e) {
            downloadFailures.incrementAndGet();
            log.debug("Khong tai duoc anh {}: {}", imageUrl, e.toString());
            return metadata;
        }
    }

    /**
     * Tải nội dung ảnh, sau khi đã kiểm tra đích đến.
     *
     * <p>Phép kiểm tra <b>đứng trước</b> lời gọi mạng, không phải sau — đây là
     * điểm mấu chốt của việc chống SSRF, và cũng là thứ mà bản vá của
     * {@code HtmlDownloader} đã xác lập.
     */
    private byte[] fetchImage(String imageUrl) throws Exception {
        assertTargetAllowed(imageUrl);

        Connection.Response response = Jsoup.connect(imageUrl)
                .userAgent(HtmlDownloader.USER_AGENT)
                .timeout(timeoutMs)
                // Jsoup mặc định chỉ nhận HTML; ảnh là nhị phân.
                .ignoreContentType(true)
                // Cắt tại trần, nên một tệp khổng lồ đội lốt ảnh không nuốt hết heap.
                .maxBodySize((int) Math.min(maxImageBytes, Integer.MAX_VALUE))
                // KHÔNG đi theo chuyển hướng: một ảnh chuyển hướng vào mạng nội
                // bộ là cách vòng qua phép kiểm tra ở trên. HtmlDownloader phải
                // đi theo vì trang thật hay chuyển http->https; ảnh thì không
                // đáng để mở thêm bề mặt tấn công.
                .followRedirects(false)
                .execute();

        int status = response.statusCode();
        if (status >= 300) {
            throw new java.io.IOException("HTTP " + status + " khi tai anh " + imageUrl);
        }
        return response.bodyAsBytes();
    }

    /** Dùng lại nguyên phép kiểm tra của {@link SeedUrlValidator} — xem Javadoc lớp. */
    private void assertTargetAllowed(String url) throws BlockedImageException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BlockedImageException("URL anh khong hop le");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BlockedImageException("Chi chap nhan http/https");
        }
        String host = uri.getHost();
        if (SeedUrlValidator.isBlockedHostname(host)) {
            throw new BlockedImageException("Ten may nam trong danh sach chan");
        }
        InetAddress address;
        try {
            address = dnsResolver.resolve(host);
        } catch (Exception e) {
            throw new BlockedImageException("Khong phan giai duoc ten may");
        }
        if (SeedUrlValidator.isBlockedAddress(address)) {
            throw new BlockedImageException("Dia chi noi bo, khong duoc phep tai");
        }
    }

    /** Ảnh bị chặn vì lý do bảo mật — phân biệt với lỗi mạng thường. */
    private static final class BlockedImageException extends Exception {
        BlockedImageException(String message) {
            super(message);
        }
    }

    /**
     * Đuôi tệp có phải ảnh không, bỏ qua query string.
     *
     * <p>Phải cắt query trước khi xét: {@code /anh.jpg?w=800&v=2} kết thúc
     * bằng {@code "2"} chứ không phải {@code ".jpg"}, và trên báo điện tử thì
     * gần như mọi ảnh đều có tham số đổi kích thước như vậy — bỏ bước này thì
     * service lọc mất gần hết ảnh thật.
     */
    static boolean hasImageExtension(String url) {
        String path = url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        for (String ext : IMAGE_EXTENSIONS) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Giá trị thuộc tính {@code width}/{@code height}, {@code -1} nếu không đọc được. */
    private static int parseDimension(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            // Cắt đuôi đơn vị: HTML cho phép width="800px".
            String digits = value.trim().replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? -1 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 là thuật toán bắt buộc mọi JVM phải có theo đặc tả Java.
            throw new IllegalStateException("JVM khong ho tro SHA-256", e);
        }
    }

    @Override
    public String handlerName() {
        return "Image Download";
    }

    // --- Số liệu ---

    public boolean isDownloadEnabled() {
        return downloadEnabled;
    }

    public long getPagesProcessedCount() {
        return pagesProcessed.get();
    }

    public long getImagesFoundCount() {
        return imagesFound.get();
    }

    public long getImagesMissingAltCount() {
        return imagesMissingAlt.get();
    }

    public long getImagesSkippedByExtensionCount() {
        return imagesSkippedByExtension.get();
    }

    public long getImagesOverPageLimitCount() {
        return imagesOverPageLimit.get();
    }

    public long getImagesDownloadedCount() {
        return imagesDownloaded.get();
    }

    public long getImagesBlockedCount() {
        return imagesBlocked.get();
    }

    public long getDownloadFailureCount() {
        return downloadFailures.get();
    }

    public long getBytesDownloadedCount() {
        return bytesDownloaded.get();
    }

    /** Tỷ lệ ảnh thiếu {@code alt} — chỉ số tiếp cận, từ 0 đến 1. */
    public double getMissingAltRate() {
        long total = imagesFound.get();
        return total == 0 ? 0.0 : (double) imagesMissingAlt.get() / total;
    }

    /** Số ảnh trung bình mỗi trang. */
    public double getAverageImagesPerPage() {
        long pages = pagesProcessed.get();
        return pages == 0 ? 0.0 : (double) imagesFound.get() / pages;
    }
}
