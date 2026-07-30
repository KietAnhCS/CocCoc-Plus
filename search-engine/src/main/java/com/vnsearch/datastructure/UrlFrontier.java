package com.vnsearch.datastructure;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Hàng đợi URL có ưu tiên cho crawler, xây trên {@link MinHeap} tự cài
 * (không dùng {@code java.util.PriorityQueue} có sẵn).
 *
 * <p><b>Kiến trúc: mỗi domain một hàng đợi riêng.</b> Thay vì một heap
 * toàn cục duy nhất, frontier giữ {@code Map<domain, MinHeap>} — đây chính
 * là mô hình "back queue theo host" của crawler Mercator (Heydon &amp;
 * Najork, 1999). Lý do bắt buộc phải làm vậy được phân tích ngay dưới đây.
 *
 * <p><b>Vì sao KHÔNG dùng một heap toàn cục:</b> với một heap duy nhất,
 * khi phần tử ưu tiên cao nhất thuộc domain đang trong thời gian hoãn
 * (politeness delay), ta buộc phải rút nó ra, gác sang một danh sách tạm,
 * rồi rút tiếp phần tử sau... Trường hợp xấu nhất — mọi URL đang chờ đều
 * thuộc các domain vừa được truy cập — phải rút CẠN cả heap rồi nhét lại
 * toàn bộ, tức {@code O(n log n)} cho MỖI lần lấy một URL. Ở quy mô nhỏ
 * (vài trăm URL) chi phí này không thấy được, nhưng frontier tăng rất
 * nhanh: mỗi trang tin tức sinh trung bình hơn 100 outlink, nên crawl
 * 10.000 trang đẩy n lên hàng trăm nghìn và crawler thực tế đứng hình.
 *
 * <p><b>Cách làm hiện tại:</b> tách theo domain rồi chỉ quét qua các
 * domain (D domain, D rất nhỏ — thường dưới 10 vì crawl bị giới hạn trong
 * {@code allowedDomains}), chọn domain vừa hết thời gian hoãn vừa có phần
 * tử đầu hàng ưu tiên cao nhất, rồi {@code extractMin} đúng một lần trên
 * heap của domain đó. Chi phí giảm từ {@code O(n log n)} xuống
 * {@code O(D + log n_d)} với {@code n_d} là số URL của riêng domain đó —
 * không còn phụ thuộc vào tổng kích thước frontier.
 *
 * <p>Điểm ưu tiên = f(độ sâu, số backlink đã biết, domain có phải .vn
 * không) — trang càng nóng (nhiều backlink), càng gần seed (độ sâu nhỏ),
 * domain .vn (ưu tiên theo yêu cầu đề bài) thì càng được crawl sớm.
 *
 * <p><b>Kỹ thuật tái sử dụng MinHeap:</b> MinHeap luôn {@code extractMin()}
 * ra phần tử NHỎ NHẤT, nhưng ta muốn lấy phần tử có priority CAO NHẤT
 * trước. Giải pháp: sắp heap theo {@code -priority} — phần tử có priority
 * cao nhất sẽ có {@code -priority} nhỏ nhất nên vẫn là phần tử
 * {@code extractMin()} trả về đầu tiên. Đây là kỹ thuật phổ biến để biến
 * một min-heap thành "max-heap theo tiêu chí X" mà không phải viết lại
 * cấu trúc heap.
 *
 * <p><b>Chặn trên kích thước:</b> vì mỗi trang sinh hơn 100 outlink, một
 * phiên crawl 10.000 trang có thể đẩy vào frontier hơn một triệu URL (vài
 * trăm MB chuỗi). {@link #DEFAULT_MAX_SIZE} chặn số URL đang chờ; khi đầy,
 * URL mới bị bỏ qua thay vì để bộ nhớ phình không kiểm soát. Đây là đánh
 * đổi có chủ ý: crawler ưu tiên theo bề rộng nên các URL bị bỏ hầu hết là
 * URL độ sâu lớn, vốn có điểm ưu tiên thấp nhất.
 *
 * <p>Thread-safe: toàn bộ thao tác đọc/ghi cấu trúc dữ liệu nội bộ đều nằm
 * trong khối {@code synchronized}, cho phép nhiều crawler thread gọi đồng
 * thời.
 *
 * <p>Độ phức tạp thời gian: {@link #addUrl} là {@code O(log n_d)};
 * {@link #nextUrl} là {@code O(D + log n_d)} với D là số domain phân biệt.
 */
public class UrlFrontier {

    /** Khoảng cách tối thiểu giữa 2 lần truy cập cùng một domain. */
    public static final long POLITENESS_DELAY_MS = 1000L;

    /** Số URL đang chờ tối đa, chặn bộ nhớ khi crawl quy mô lớn. */
    public static final int DEFAULT_MAX_SIZE = 500_000;

    /** Một URL cần crawl, kèm độ sâu (dùng để giới hạn maxDepth ở CrawlerService). */
    public record Task(String url, int depth) {
    }

    private record FrontierEntry(String url, int depth, double priority) {
    }

    /** domain -> hàng đợi ưu tiên riêng của domain đó. */
    private final Map<String, MinHeap<FrontierEntry>> byDomain = new HashMap<>();
    private final Map<String, Long> lastAccessTime = new HashMap<>();
    private final Set<String> enqueued = new HashSet<>();
    private final Object lock = new Object();
    private final int maxSize;

    private int totalSize = 0;
    private long droppedDueToCapacity = 0;

    public UrlFrontier() {
        this(DEFAULT_MAX_SIZE);
    }

    public UrlFrontier(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize phải > 0");
        }
        this.maxSize = maxSize;
    }

    /**
     * {@code O(log n_d)} - thêm URL mới vào hàng đợi của domain tương ứng
     * nếu URL đó chưa từng được xếp hàng và frontier chưa đầy.
     *
     * @return true nếu URL thực sự được thêm vào
     */
    public boolean addUrl(String rawUrl, int depth, int knownBacklinks) {
        // Chuan hoa ngay tai cua vao: day la choke point duy nhat ma moi URL
        // deu phai di qua, nen chuan hoa o day dam bao tap enqueued khong bao
        // gio chua 2 bien the cua cung mot trang.
        String url = com.vnsearch.crawler.UrlCanonicalizer.canonicalize(rawUrl);
        synchronized (lock) {
            if (enqueued.contains(url)) {
                return false;
            }
            if (totalSize >= maxSize) {
                droppedDueToCapacity++;
                return false;
            }
            double priority = computePriority(url, depth, knownBacklinks);
            String domain = extractDomain(url);
            byDomain.computeIfAbsent(domain,
                            d -> new MinHeap<>((a, b) -> Double.compare(-a.priority(), -b.priority())))
                    .insert(new FrontierEntry(url, depth, priority));
            enqueued.add(url);
            totalSize++;
            return true;
        }
    }

    private double computePriority(String url, int depth, int knownBacklinks) {
        double score = 0;
        score -= depth * 2.0; // càng sâu càng ít ưu tiên
        score += Math.min(knownBacklinks, 50) * 0.5; // nhiều backlink -> ưu tiên hơn
        if (isVnDomain(url)) {
            score += 5.0; // ưu tiên domain .vn theo yêu cầu đề bài
        }
        return score;
    }

    private boolean isVnDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.endsWith(".vn");
        } catch (Exception e) {
            return false;
        }
    }

    private String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Lấy URL ưu tiên cao nhất trong số các domain hiện đang được phép truy
     * cập (đã qua politeness delay). Blocking: nếu frontier còn URL nhưng
     * mọi domain đều đang trong thời gian hoãn, thread sẽ ngủ ngắn (50ms)
     * rồi thử lại. Trả về {@code null} khi hàng đợi thật sự rỗng (báo hiệu
     * có thể dừng crawler).
     */
    public Task nextUrl() {
        while (true) {
            synchronized (lock) {
                if (totalSize == 0) {
                    return null;
                }
                long now = System.currentTimeMillis();
                String bestDomain = null;
                double bestPriority = Double.NEGATIVE_INFINITY;

                // Quét qua các domain (D nhỏ) thay vì quét qua các URL (n lớn).
                Iterator<Map.Entry<String, MinHeap<FrontierEntry>>> it = byDomain.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, MinHeap<FrontierEntry>> entry = it.next();
                    MinHeap<FrontierEntry> heap = entry.getValue();
                    if (heap.isEmpty()) {
                        it.remove(); // dọn hàng đợi rỗng để vòng quét sau khỏi phải xét lại
                        continue;
                    }
                    Long last = lastAccessTime.get(entry.getKey());
                    if (last != null && now - last < POLITENESS_DELAY_MS) {
                        continue; // domain này đang trong thời gian hoãn
                    }
                    double priority = heap.peek().priority();
                    if (priority > bestPriority) {
                        bestPriority = priority;
                        bestDomain = entry.getKey();
                    }
                }

                if (bestDomain != null) {
                    FrontierEntry chosen = byDomain.get(bestDomain).extractMin();
                    lastAccessTime.put(bestDomain, now);
                    enqueued.remove(chosen.url());
                    totalSize--;
                    return new Task(chosen.url(), chosen.depth());
                }
            }
            // Mọi domain đều đang bị hoãn -> ngủ NGOÀI khối synchronized để
            // không giữ khoá, tránh chặn các thread đang muốn addUrl.
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
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

    /** Số domain phân biệt đang có URL chờ - dùng cho thống kê/báo cáo. */
    public int domainCount() {
        synchronized (lock) {
            return byDomain.size();
        }
    }

    /** Số URL bị bỏ do frontier đã đầy - dùng cho thống kê/báo cáo. */
    public long getDroppedDueToCapacity() {
        synchronized (lock) {
            return droppedDueToCapacity;
        }
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://example.com/a", 1, 2);
        frontier.addUrl("https://congty.gov.vn/b", 1, 2); // domain .vn thật -> +5 điểm ưu tiên
        frontier.addUrl("https://example.com/c", 3, 0);   // sâu hơn -> ít ưu tiên hơn

        System.out.println("Số domain phân biệt: " + frontier.domainCount());
        System.out.println("Task 1 (ưu tiên cao nhất, phải là domain .vn): " + frontier.nextUrl());
        System.out.println("Task 2: " + frontier.nextUrl());
        System.out.println("Task 3 (sâu nhất -> ít ưu tiên nhất): " + frontier.nextUrl());
    }
}
