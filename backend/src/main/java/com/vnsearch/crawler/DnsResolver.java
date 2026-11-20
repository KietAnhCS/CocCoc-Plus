package com.vnsearch.crawler;

import com.vnsearch.datastructure.LRUCache;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Khối "DNS Resolver"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p><b>Vì sao cần một khối riêng.</b> Trước đây crawler gọi thẳng
 * {@code Jsoup.connect(url)}, và việc phân giải tên miền bị chôn bên trong
 * tầng mạng của JVM — không quan sát được, không đo được, không thay thế
 * được khi test. Tách ra thành một khối riêng cho ba lợi ích cụ thể:
 *
 * <ul>
 *   <li><b>Loại sớm host chết.</b> Một tên miền không phân giải được sẽ
 *       khiến {@link HtmlDownloader} thử lại 3 lần, mỗi lần chờ hết timeout
 *       10 giây — 30 giây cho một URL vốn không bao giờ tải được. Hỏi DNS
 *       trước tốn vài mili giây và loại được ca đó ngay.</li>
 *   <li><b>Cache theo host.</b> Crawler bị giới hạn trong
 *       {@code allowedDomains} nên hàng nghìn URL dùng chung vài host.
 *       Phân giải một lần rồi tái sử dụng.</li>
 *   <li><b>Đo được.</b> Tỷ lệ trúng cache là một số liệu đưa thẳng vào báo
 *       cáo, xem {@link #hitRate()}.</li>
 * </ul>
 *
 * <p><b>Tái sử dụng cấu trúc dữ liệu tự cài.</b> Cache dùng
 * {@link LRUCache} — chính cấu trúc đã viết cho cache kết quả tìm kiếm.
 * Đây là lần thứ hai nó được dùng trong hệ thống, cho một mục đích hoàn
 * toàn khác, nên là một minh chứng tốt rằng cấu trúc đó đủ tổng quát.
 *
 * <p><b>Ghi chú về đồng thời.</b> {@link LRUCache} tự thread-safe, nhưng
 * cặp thao tác {@code get} rồi {@code put} ở đây <i>không</i> nguyên tử:
 * hai worker cùng gặp một host mới có thể cùng gọi
 * {@link InetAddress#getByName}. Đây là đánh đổi có chủ ý — hậu quả xấu
 * nhất là một lần truy vấn DNS thừa, trong khi khoá toàn bộ quá trình phân
 * giải sẽ chặn mọi worker khác trong lúc chờ mạng.
 *
 * <p><b>Không cache kết quả thất bại.</b> {@link LRUCache} không lưu được
 * giá trị {@code null}, và quan trọng hơn: một host tạm thời không phân
 * giải được (mạng chập chờn) không nên bị loại vĩnh viễn khỏi phiên crawl.
 *
 * <p>Độ phức tạp: {@link #resolve} là O(1) khi trúng cache, còn khi trượt
 * thì chi phí do mạng quyết định.
 */
public class DnsResolver {

    /** Đủ chứa toàn bộ host của một phiên crawl vài chục domain, còn dư nhiều. */
    public static final int DEFAULT_CACHE_SIZE = 1_000;

    private final LRUCache<String, InetAddress> cache;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public DnsResolver() {
        this(DEFAULT_CACHE_SIZE);
    }

    public DnsResolver(int cacheSize) {
        this.cache = new LRUCache<>(cacheSize);
    }

    /**
     * Phân giải một tên miền thành địa chỉ IP, ưu tiên lấy từ cache.
     *
     * @throws UnknownHostException nếu host rỗng hoặc không phân giải được
     */
    public InetAddress resolve(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            // Tính vào failures luôn: đây là bộ đếm DUY NHẤT cho "URL bị loại
            // vì không phân giải được tên miền". Bỏ sót ca này thì con số trong
            // báo cáo nhỏ hơn thực tế.
            failures.incrementAndGet();
            throw new UnknownHostException("Tên miền rỗng");
        }
        String key = host.toLowerCase(Locale.ROOT);

        InetAddress cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }

        misses.incrementAndGet();
        try {
            InetAddress resolved = InetAddress.getByName(key);
            cache.put(key, resolved);
            return resolved;
        } catch (UnknownHostException e) {
            failures.incrementAndGet();
            throw e;
        }
    }

    /** Tiện ích: rút host ra khỏi URL rồi phân giải. */
    public InetAddress resolveHostOf(String url) throws UnknownHostException {
        return resolve(hostOf(url));
    }

    /** Rút phần host của một URL; trả về {@code null} nếu URL không phân tích được. */
    public static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public long getCacheHits() {
        return hits.get();
    }

    public long getCacheMisses() {
        return misses.get();
    }

    /**
     * Số tên miền không phân giải được — cũng chính là số URL bị loại trước khi
     * {@link HtmlDownloader} kịp mở kết nối. Đây là bộ đếm duy nhất cho sự kiện
     * đó trong toàn hệ thống.
     */
    public long getResolveFailures() {
        return failures.get();
    }

    public int getCachedHostCount() {
        return cache.size();
    }

    /** Tỷ lệ trúng cache trong khoảng [0, 1]; trả về 0 khi chưa có lượt tra nào. */
    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) throws Exception {
        DnsResolver resolver = new DnsResolver();
        System.out.println("Lần 1 (trượt cache): " + resolver.resolve("vnexpress.net"));
        System.out.println("Lần 2 (trúng cache): " + resolver.resolve("vnexpress.net"));
        System.out.println("Số lượt trúng : " + resolver.getCacheHits());
        System.out.println("Số lượt trượt : " + resolver.getCacheMisses());
        System.out.printf("Tỷ lệ trúng   : %.0f%%%n", resolver.hitRate() * 100);
    }
}
