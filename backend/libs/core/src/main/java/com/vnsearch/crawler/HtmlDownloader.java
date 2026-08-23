package com.vnsearch.crawler;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Khối "HTML Downloader"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p>Trước đây phần này là phương thức {@code fetchWithRetry} nằm lẫn trong
 * {@code CrawlerService}. Tách ra thành lớp riêng để mỗi khối trong sơ đồ
 * ứng với đúng một lớp, và để chính sách tải (timeout, số lần thử lại,
 * User-Agent) sửa được ở một chỗ duy nhất.
 *
 * <p><b>Quan hệ với {@link DnsResolver}</b> — đúng như mũi tên
 * {@code HTML Downloader → DNS Resolver} trong sơ đồ: trước khi mở kết nối,
 * lớp này hỏi bộ phân giải tên miền. Nếu host không phân giải được thì ném
 * lỗi <b>ngay</b>, không bước vào vòng thử lại: ba lần thử với timeout 10
 * giây mỗi lần là 30 giây lãng phí cho một tên miền không tồn tại.
 *
 * <p><b>Chính sách thử lại.</b> Tối đa {@link #DEFAULT_MAX_RETRIES}+1 lần,
 * không có exponential backoff. Politeness delay 1 giây của
 * {@link com.vnsearch.crawler.frontier.UrlFrontier} đã tạo một mức giãn tối
 * thiểu giữa hai lần chạm cùng một host, nhưng mức giãn đó <i>không</i>
 * tăng theo số lần lỗi — đây là điểm còn đơn giản hoá so với crawler thực tế.
 *
 * <p>Lớp này thread-safe: không giữ trạng thái nào ngoài các bộ đếm nguyên tử.
 */
public class HtmlDownloader {

    private static final Logger log = LoggerFactory.getLogger(HtmlDownloader.class);

    public static final String USER_AGENT = "VnSearchBot/1.0 (+do an DSA; hoc thuat)";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_MAX_RETRIES = 2;

    /**
     * Số chặng chuyển hướng tối đa.
     *
     * <p>Phải có chặn trên: một cặp trang trỏ vòng vào nhau tạo ra vòng lặp vô
     * hạn, và một chuỗi chuyển hướng dài là cách rẻ tiền để bắt crawler tiêu
     * thời gian. 5 chặng đủ cho mọi trang thật (http→https, thêm www, đổi
     * đường dẫn) mà không đủ để bị lợi dụng.
     */
    public static final int MAX_REDIRECTS = 5;

    private final DnsResolver dnsResolver;
    private final int timeoutMs;
    private final int maxRetries;

    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    public HtmlDownloader() {
        this(new DnsResolver(), DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES);
    }

    public HtmlDownloader(DnsResolver dnsResolver) {
        this(dnsResolver, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES);
    }

    public HtmlDownloader(DnsResolver dnsResolver, int timeoutMs, int maxRetries) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs phải > 0, nhận được: " + timeoutMs);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries phải >= 0, nhận được: " + maxRetries);
        }
        this.dnsResolver = dnsResolver;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
    }

    /**
     * Tải một trang và trả về cây DOM đã được Jsoup phân tích.
     *
     * <p>Lưu ý phân công trách nhiệm: lớp này <b>chỉ</b> tải và phân tích cú
     * pháp HTML. Việc rút title/body/link ra khỏi cây DOM là việc của
     * {@link ContentParser} và {@link LinkExtractor}.
     *
     * @throws UnknownHostException nếu DNS không phân giải được host (không thử lại)
     * @throws IOException          nếu đã thử hết số lần cho phép mà vẫn thất bại
     */
    public Document download(String url) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                retries.incrementAndGet();
            }
            try {
                Document document = fetchFollowingRedirects(url);
                downloaded.incrementAndGet();
                return document;
            } catch (BlockedTargetException e) {
                // URL trỏ vào mạng nội bộ. Thử lại là vô nghĩa — địa chỉ sẽ
                // không tự trở thành công khai — và mỗi lần thử lại là thêm một
                // lần chạm vào hạ tầng nội bộ. Bỏ ngay.
                failed.incrementAndGet();
                throw e;
            } catch (UnknownHostException e) {
                // Host chết: cũng không thử lại. Ba lần thử với timeout 10 giây
                // là 30 giây lãng phí cho một tên miền không tồn tại.
                throw e;
            } catch (IOException e) {
                lastError = e;
            } catch (Exception e) {
                // Jsoup ném cả unchecked (URL sai định dạng, kiểu nội dung
                // không hỗ trợ...). Gói lại để phía gọi chỉ phải bắt IOException.
                lastError = new IOException(e.getMessage(), e);
            }
        }
        failed.incrementAndGet();
        throw lastError;
    }

    /**
     * Tải một URL, tự đi theo chuyển hướng và <b>kiểm tra lại đích ở từng chặng</b>.
     *
     * <p><b>Vì sao không dùng {@code followRedirects(true)} của Jsoup.</b> Đó
     * chính là lỗ hổng mà hàm này vá. {@link SeedUrlValidator} kiểm tra URL hạt
     * giống tại {@code AdminController}, nhưng nếu Jsoup tự đi theo chuyển hướng
     * thì phép kiểm tra đó chỉ áp cho <i>chặng đầu tiên</i>:
     *
     * <pre>
     *   seed: https://trang-cua-toi.com/   ✅ IP công cộng, qua kiểm tra
     *              │  HTTP 302
     *              ▼
     *         http://169.254.169.254/latest/meta-data/iam/
     *              ↑ Jsoup tự đi theo — trước đây KHÔNG ai kiểm tra chặng này
     * </pre>
     *
     * Tắt tự động rồi tự đi từng chặng cho phép chạy đúng một phép kiểm tra
     * <b>trước mỗi lần mở kết nối</b>, nên chặng thứ mười cũng được soi kỹ như
     * chặng đầu.
     *
     * <p>Cùng một phép kiểm tra ấy cũng chặn luôn <b>đường thứ hai</b>: liên kết
     * do {@link LinkExtractor} moi ra từ trang đã tải không đi qua
     * {@code AdminController}, nên trước đây chúng chưa từng được kiểm tra. Nay
     * mọi URL đều phải qua đây mới tải được, bất kể nó đến từ đâu.
     *
     * <p><b>Hạn chế còn lại (DNS rebinding).</b> Phép kiểm tra phân giải tên
     * miền qua {@link DnsResolver}, còn Jsoup tự phân giải lần nữa lúc mở
     * socket. Về lý thuyết bản ghi DNS có thể đổi giữa hai lần đó. Cửa sổ này
     * hẹp hơn trước — {@link DnsResolver} có cache nên phần lớn lần tải dùng lại
     * đúng địa chỉ vừa kiểm tra — nhưng đóng hẳn thì phải ghim IP rồi tự đặt
     * header {@code Host}, việc này phá SNI của HTTPS. Ghi nhận là rủi ro còn
     * lại, không phải chỗ bị bỏ sót.
     */
    private Document fetchFollowingRedirects(String url) throws IOException {
        String current = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            assertTargetAllowed(current);

            Connection.Response response = Jsoup.connect(current)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .followRedirects(false)   // tự đi, để kiểm tra được từng chặng
                    .execute();

            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response.parse();
            }

            String location = response.header("Location");
            if (location == null || location.isBlank()) {
                throw new IOException("Chuyen huong " + status + " nhung khong co header Location: "
                        + current);
            }
            // Location có thể là đường dẫn tương đối; phân giải theo URL hiện tại.
            current = URI.create(current).resolve(location.trim()).toString();
        }

        throw new IOException("Vuot qua " + MAX_REDIRECTS + " lan chuyen huong, bat dau tu: " + url);
    }

    /**
     * Chặn URL trỏ vào mạng nội bộ, <b>trước</b> khi mở kết nối.
     *
     * <p>Dùng lại đúng phép kiểm tra của {@link SeedUrlValidator} thay vì viết
     * bản thứ hai: hai cài đặt song song của cùng một quy tắc bảo mật thì sớm
     * muộn cũng lệch nhau, và bản bị quên cập nhật chính là lỗ hổng.
     */
    private void assertTargetAllowed(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("URL khong hop le: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            // Chặn cả file:, gopher:, jar: — những scheme mà một redirect có thể
            // trả về và thư viện HTTP có thể vô tình phục vụ.
            throw new BlockedTargetException("Chi chap nhan http/https: " + url);
        }

        String host = uri.getHost();
        if (SeedUrlValidator.isBlockedHostname(host)) {
            log.warn("Chan URL: ten may nam trong danh sach chan ({})", host);
            throw new BlockedTargetException("Ten may bi chan");
        }

        // Mũi tên "HTML Downloader -> DNS Resolver" trong sơ đồ. Địa chỉ lấy
        // được ở đây vừa dùng để loại sớm host chết, vừa là thứ được đem đi
        // kiểm tra ngay dưới — một lần phân giải, hai công dụng.
        InetAddress address = dnsResolver.resolve(host);
        if (SeedUrlValidator.isBlockedAddress(address)) {
            log.warn("Chan URL tro toi dia chi noi bo: {} -> {}", host, address.getHostAddress());
            throw new BlockedTargetException("Dia chi khong duoc phep crawl");
        }
    }

    /**
     * URL bị chặn vì lý do bảo mật, không phải vì lỗi mạng.
     *
     * <p>Là một kiểu riêng để {@link #download} phân biệt được: lỗi mạng thì
     * đáng thử lại, còn địa chỉ nội bộ thì thử lại bao nhiêu lần cũng vẫn là
     * địa chỉ nội bộ.
     */
    public static class BlockedTargetException extends IOException {
        public BlockedTargetException(String message) {
            super(message);
        }
    }

    public long getDownloadedCount() {
        return downloaded.get();
    }

    /**
     * Số URL thất bại sau khi đã thử hết số lần cho phép.
     *
     * <p>Không tính các URL bị loại vì DNS: những ca đó chưa từng mở kết nối,
     * và {@link DnsResolver#getResolveFailures()} đã đếm rồi. Đếm ở cả hai nơi
     * thì cùng một sự kiện xuất hiện hai lần trong báo cáo.
     */
    public long getFailedCount() {
        return failed.get();
    }

    public long getRetryCount() {
        return retries.get();
    }
}
