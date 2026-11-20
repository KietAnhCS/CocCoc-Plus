package com.vnsearch.crawler.frontier;

import com.vnsearch.crawler.UrlCanonicalizer;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>Khối "URL Frontier"</b> của crawler — hàng đợi hai tầng theo đúng sơ đồ
 * kiến trúc, dựng trên {@link com.vnsearch.datastructure.MinHeap} tự cài
 * (không dùng {@code java.util.PriorityQueue} có sẵn).
 *
 * <pre>
 *   input URLs
 *       |
 *       v
 *   Prioritizer  --->  f1 f2 ... fn  --->  Front queue selector
 *                      (theo muc uu tien)          |
 *                                                  v  output URLs
 *                                          Back queue router  &lt;-&gt;  Mapping Table
 *                                                  |
 *                                                  v
 *                                          b1 b2 ... bn (moi hang doi MOT host)
 *                                                  |
 *                                                  v
 *                                          Back queue selector  --->  worker threads
 * </pre>
 *
 * <p><b>Facade pattern.</b> Lớp này không tự cài thuật toán nào; nó ghép bốn
 * thành phần lại và cung cấp đúng hai thao tác mà crawler cần —
 * {@link #addUrl} và {@link #nextUrl}. Bên trong:
 *
 * <table border="1">
 *   <caption>Khối trong sơ đồ và lớp cài đặt</caption>
 *   <tr><th>Khối</th><th>Lớp</th></tr>
 *   <tr><td>Prioritizer</td><td>{@link Prioritizer} — mặc định {@link DefaultPrioritizer}</td></tr>
 *   <tr><td>f1..fn + Front queue selector</td><td>{@link FrontQueues} + {@link FrontQueueSelector}</td></tr>
 *   <tr><td>Back queue router + Mapping Table + b1..bn + Back queue selector</td><td>{@link BackQueues}</td></tr>
 * </table>
 *
 * <h2>Vì sao phải hai tầng</h2>
 *
 * <p>Hai tầng giải hai bài toán <b>xung đột nhau</b>:
 *
 * <ul>
 *   <li><b>Ưu tiên</b> muốn lấy URL tốt nhất trước, bất kể nó thuộc host nào.</li>
 *   <li><b>Lịch sự</b> cấm chạm cùng một host hai lần trong vòng một giây,
 *       bất kể URL đó tốt tới đâu.</li>
 * </ul>
 *
 * <p>Gộp vào một cấu trúc thì một trong hai phải nhường. Tách ra thì tầng
 * trước xếp theo ưu tiên và <b>không biết gì về host</b>, tầng sau gom theo
 * host và <b>không biết gì về ưu tiên</b> — mỗi tầng làm đúng một việc và làm
 * trọn vẹn.
 *
 * <h2>Chặn trên kích thước</h2>
 *
 * <p>Mỗi trang tin sinh hơn 90 liên kết ra, nên một phiên crawl 10.000 trang
 * có thể đẩy vào frontier hơn một triệu URL. {@link #DEFAULT_MAX_SIZE} chặn
 * số URL đang chờ; khi đầy, URL mới bị bỏ qua thay vì để bộ nhớ phình không
 * kiểm soát. Đây là đánh đổi có chủ ý: crawler ưu tiên theo bề rộng nên các
 * URL bị bỏ hầu hết là URL độ sâu lớn, vốn nằm ở mức ưu tiên thấp nhất.
 *
 * <h2>Đồng thời</h2>
 *
 * <p>Thread-safe: {@link FrontQueues} và {@link BackQueues} đều <b>không</b>
 * tự đồng bộ; lớp này bọc mọi thao tác trong một khối {@code synchronized}
 * duy nhất. Một khoá cho cả hai tầng là cố ý — chúng phải đổi trạng thái cùng
 * nhau trong {@link #nextUrl} (nạp lại tầng sau rồi mới lấy), nên hai khoá
 * riêng chỉ tạo thêm cơ hội cho tình trạng đua mà không tăng thông lượng
 * thực: thân khoá không có thao tác vào/ra nào.
 *
 * <p>Khi mọi host đều đang trong thời gian hoãn, luồng ngủ <b>ngoài</b> khối
 * khoá, và ngủ đúng tới thời điểm hàng đợi sớm nhất khả dụng trở lại thay vì
 * thăm dò theo chu kỳ cố định.
 *
 * <h2>Độ phức tạp</h2>
 *
 * <table border="1">
 *   <caption>So với bản một tầng trước đây</caption>
 *   <tr><th>Thao tác</th><th>Bản cũ (heap theo host)</th><th>Bản này</th></tr>
 *   <tr><td>{@link #addUrl}</td><td>$O(\log n_d)$</td><td><b>$O(1)$</b></td></tr>
 *   <tr><td>{@link #nextUrl}</td><td>$O(D + \log n_d)$, quét mọi host</td><td><b>$O(\log n)$</b>, n = số hàng đợi sau</td></tr>
 * </table>
 *
 * <p>Điểm đáng chú ý: chi phí lấy URL không còn phụ thuộc vào <b>số host đã
 * gặp</b> nữa, mà chỉ phụ thuộc số hàng đợi sau — một hằng số cấu hình.
 */
public class UrlFrontier {

    /** Khoảng cách tối thiểu giữa 2 lần truy cập cùng một host. */
    public static final long POLITENESS_DELAY_MS = 1000L;

    /** Số URL đang chờ tối đa, chặn bộ nhớ khi crawl quy mô lớn. */
    public static final int DEFAULT_MAX_SIZE = 500_000;

    /**
     * Số hàng đợi sau, tức số host được phép hoạt động cùng lúc.
     *
     * <p>Trần thông lượng của crawler là {@code số hàng đợi / politeness
     * delay} = 128 trang/giây — cao hơn hẳn mức mà mạng và số worker cho
     * phép, nên nó không phải nút thắt. Host vượt quá con số này không bị mất
     * mà nằm chờ ở tầng trước.
     */
    public static final int DEFAULT_BACK_QUEUE_COUNT = 128;

    /**
     * Trần thời gian ngủ mỗi lượt chờ.
     *
     * <p>Ngủ đúng tới thời điểm khả dụng kế tiếp là tối ưu khi frontier đứng
     * yên, nhưng một worker khác có thể <b>thêm URL mới</b> ngay sau đó. Trần
     * này bảo đảm luồng thức dậy đủ sớm để thấy URL mới.
     */
    private static final long MAX_SLEEP_MS = 50L;

    private final Prioritizer prioritizer;
    private final FrontQueues frontQueues;
    private final BackQueues backQueues;

    /** Chống xếp hàng trùng: chính xác tuyệt đối, khác với Bloom Filter ở tầng crawler. */
    private final Set<String> enqueued = new HashSet<>();

    /** host -&gt; số URL của host đó đang chờ. Mục bị xoá khi về 0 nên không phình. */
    private final Map<String, Integer> pendingPerHost = new HashMap<>();

    private final Object lock = new Object();
    private final int maxSize;

    private int totalSize;
    private long droppedDueToCapacity;

    public UrlFrontier() {
        this(DEFAULT_MAX_SIZE);
    }

    public UrlFrontier(int maxSize) {
        this(maxSize, new DefaultPrioritizer(), new WeightedRandomSelector(),
                DEFAULT_BACK_QUEUE_COUNT);
    }

    /**
     * @param maxSize        số URL đang chờ tối đa
     * @param prioritizer    chính sách xếp mức ưu tiên
     * @param selector       chính sách chọn hàng đợi trước
     * @param backQueueCount số host được phép hoạt động cùng lúc
     */
    public UrlFrontier(int maxSize, Prioritizer prioritizer, FrontQueueSelector selector,
                        int backQueueCount) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize phải > 0, nhận được: " + maxSize);
        }
        if (prioritizer == null) {
            throw new IllegalArgumentException("prioritizer không được null");
        }
        this.maxSize = maxSize;
        this.prioritizer = prioritizer;
        this.frontQueues = new FrontQueues(prioritizer.levels(), selector);
        this.backQueues = new BackQueues(backQueueCount, POLITENESS_DELAY_MS);
    }

    /**
     * $O(1)$ — chuẩn hoá URL, xếp mức ưu tiên, rồi đưa vào hàng đợi trước
     * tương ứng.
     *
     * <p>Chuẩn hoá ngay tại cửa vào: đây là choke point duy nhất mà mọi URL
     * đều phải đi qua, nên chuẩn hoá ở đây bảo đảm tập {@code enqueued} không
     * bao giờ chứa hai biến thể của cùng một trang.
     *
     * @return {@code true} nếu URL thực sự được thêm vào
     */
    public boolean addUrl(String rawUrl, int depth, int knownBacklinks) {
        String url = UrlCanonicalizer.canonicalize(rawUrl);
        if (url == null || url.isBlank()) {
            return false;
        }
        // Phân tích URL một lần duy nhất, ngoài khối khoá; host đi theo
        // CrawlTask nên cả prioritizer lẫn tầng sau không phải phân tích lại.
        String host = hostOf(url);
        CrawlTask task = new CrawlTask(url, host != null ? host : url, depth);
        int level = prioritizer.levelOf(url, task.host(), depth, knownBacklinks);

        synchronized (lock) {
            if (enqueued.contains(url)) {
                return false;
            }
            if (totalSize >= maxSize) {
                droppedDueToCapacity++;
                return false;
            }
            frontQueues.add(task, level);       // Prioritizer -> f1..fn
            enqueued.add(url);
            pendingPerHost.merge(task.host(), 1, Integer::sum);
            totalSize++;
            return true;
        }
    }

    /**
     * Lấy URL kế tiếp nên crawl.
     *
     * <p>Blocking: nếu frontier còn URL nhưng mọi host đều đang trong thời
     * gian hoãn, luồng sẽ ngủ rồi thử lại. Trả về {@code null} khi hàng đợi
     * thật sự rỗng — tín hiệu để crawler cân nhắc dừng.
     */
    public CrawlTask nextUrl() {
        while (true) {
            long sleepMs;
            synchronized (lock) {
                if (totalSize == 0) {
                    return null;
                }
                backQueues.refillFrom(frontQueues);          // Back queue router

                long now = System.currentTimeMillis();
                CrawlTask task = backQueues.poll(now);       // Back queue selector
                if (task != null) {
                    enqueued.remove(task.url());
                    releaseHost(task.host());
                    totalSize--;
                    return task;
                }
                sleepMs = sleepUntilNextSlot(now);
            }
            // Ngủ NGOÀI khối khoá để không chặn các luồng đang muốn addUrl.
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** Ngủ vừa đủ tới lúc hàng đợi sớm nhất khả dụng, nhưng không quá {@link #MAX_SLEEP_MS}. */
    private long sleepUntilNextSlot(long now) {
        long earliest = backQueues.earliestAvailableAt();
        if (earliest == Long.MAX_VALUE) {
            return MAX_SLEEP_MS; // không hàng đợi sau nào còn hàng, chỉ chờ URL mới
        }
        return Math.min(MAX_SLEEP_MS, Math.max(1L, earliest - now));
    }

    private void releaseHost(String host) {
        pendingPerHost.computeIfPresent(host, (key, count) -> count == 1 ? null : count - 1);
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public int size() {
        synchronized (lock) {
            return totalSize;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return totalSize == 0;
        }
    }

    /** Số host phân biệt đang có URL chờ, tính trên cả hai tầng. */
    public int domainCount() {
        synchronized (lock) {
            return pendingPerHost.size();
        }
    }

    /** Số URL bị bỏ do frontier đã đầy - dùng cho thống kê/báo cáo. */
    public long getDroppedDueToCapacity() {
        synchronized (lock) {
            return droppedDueToCapacity;
        }
    }

    /** Số URL đang nằm ở tầng trước (chưa được định tuyến về host). */
    public int frontQueueSize() {
        synchronized (lock) {
            return frontQueues.size();
        }
    }

    /** Số URL đang nằm ở tầng sau (đã gán host, chờ tới lượt). */
    public int backQueueSize() {
        synchronized (lock) {
            return backQueues.pendingCount();
        }
    }

    /** Số host đang chiếm một hàng đợi sau — trần là số hàng đợi sau. */
    public int activeHostCount() {
        synchronized (lock) {
            return backQueues.boundHostCount();
        }
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        // Bộ chọn tất định để đầu ra của demo lặp lại được.
        UrlFrontier frontier = new UrlFrontier(DEFAULT_MAX_SIZE, new DefaultPrioritizer(),
                new StrictPrioritySelector(), DEFAULT_BACK_QUEUE_COUNT);

        frontier.addUrl("https://example.com/a", 1, 2);
        frontier.addUrl("https://congty.gov.vn/b", 1, 2); // domain .vn -> nâng một bậc ưu tiên
        frontier.addUrl("https://example.com/c", 3, 0);   // sâu hơn -> mức thấp hơn

        System.out.println("Số host đang chờ: " + frontier.domainCount());
        System.out.println("Task 1 (ưu tiên cao nhất, phải là domain .vn): " + frontier.nextUrl());
        System.out.println("Task 2: " + frontier.nextUrl());
        System.out.println("Task 3 (sâu nhất -> ưu tiên thấp nhất): " + frontier.nextUrl());
        System.out.println("Còn lại: " + frontier.size());
    }
}
