package com.vnsearch.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
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

    public static final String USER_AGENT = "VnSearchBot/1.0 (+do an DSA; hoc thuat)";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_MAX_RETRIES = 2;

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
        // Mũi tên "HTML Downloader -> DNS Resolver" trong sơ đồ.
        dnsResolver.resolveHostOf(url); // ném UnknownHostException nếu host chết

        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                retries.incrementAndGet();
            }
            try {
                Document document = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(timeoutMs)
                        .followRedirects(true)
                        .get();
                downloaded.incrementAndGet();
                return document;
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
