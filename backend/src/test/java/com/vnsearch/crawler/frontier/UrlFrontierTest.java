package com.vnsearch.crawler.frontier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test cho {@link UrlFrontier} — hàng đợi hai tầng của crawler. Kiểm chứng
 * thứ tự ưu tiên, politeness delay, chặn kích thước, và tính an toàn khi
 * nhiều thread cùng dùng.
 *
 * <p>Các test về <b>thứ tự</b> dùng {@link StrictPrioritySelector} chứ không
 * dùng bộ chọn mặc định: mặc định là ngẫu nhiên có trọng số, nên "mức cao đi
 * trước" chỉ đúng theo xác suất. Hành vi ngẫu nhiên đó được kiểm riêng trong
 * {@code FrontQueuesTest}.
 */
class UrlFrontierTest {

    private static UrlFrontier strictFrontier() {
        return strictFrontier(UrlFrontier.DEFAULT_MAX_SIZE);
    }

    private static UrlFrontier strictFrontier(int maxSize) {
        return new UrlFrontier(maxSize, new DefaultPrioritizer(), new StrictPrioritySelector(),
                UrlFrontier.DEFAULT_BACK_QUEUE_COUNT);
    }

    @Test
    void emptyFrontierReturnsNull() {
        UrlFrontier frontier = new UrlFrontier();
        assertTrue(frontier.isEmpty());
        assertEquals(0, frontier.size());
        assertNull(frontier.nextUrl(), "Frontier rỗng phải trả về null để báo crawler dừng");
    }

    @Test
    void rejectsDuplicateUrls() {
        UrlFrontier frontier = new UrlFrontier();
        assertTrue(frontier.addUrl("https://a.com/x", 0, 0));
        assertFalse(frontier.addUrl("https://a.com/x", 0, 0), "URL trùng phải bị từ chối");
        assertEquals(1, frontier.size());
    }

    @Test
    void vnDomainGetsHigherPriority() {
        UrlFrontier frontier = strictFrontier();
        // Cùng độ sâu, cùng số backlink -> chỉ khác nhau ở đuôi .vn (nâng một bậc).
        frontier.addUrl("https://example.com/a", 1, 2);
        frontier.addUrl("https://example.vn/b", 1, 2);

        CrawlTask first = frontier.nextUrl();
        assertNotNull(first);
        assertEquals("https://example.vn/b", first.url(), "Domain .vn phải được ưu tiên trước");
    }

    @Test
    void shallowerDepthGetsHigherPriority() {
        UrlFrontier frontier = strictFrontier();
        frontier.addUrl("https://a.com/deep", 5, 0);
        frontier.addUrl("https://a.com/shallow", 0, 0);

        CrawlTask first = frontier.nextUrl();
        assertNotNull(first);
        assertEquals("https://a.com/shallow", first.url(), "Trang gần seed hơn phải được crawl trước");
    }

    @Test
    void moreBacklinksGetsHigherPriority() {
        UrlFrontier frontier = strictFrontier();
        frontier.addUrl("https://a.com/cold", 1, 0);
        frontier.addUrl("https://a.com/hot", 1, 40);

        CrawlTask first = frontier.nextUrl();
        assertNotNull(first);
        assertEquals("https://a.com/hot", first.url(), "Trang nhiều backlink hơn phải được ưu tiên");
    }

    /** Trong cùng một mức ưu tiên, thứ tự phải là FIFO để phiên crawl lặp lại được. */
    @Test
    void sameLevelKeepsDiscoveryOrder() {
        UrlFrontier frontier = strictFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://b.com/1", 0, 0);
        frontier.addUrl("https://c.com/1", 0, 0);

        assertEquals("https://a.com/1", frontier.nextUrl().url());
        assertEquals("https://b.com/1", frontier.nextUrl().url());
        assertEquals("https://c.com/1", frontier.nextUrl().url());
    }

    @Test
    void politenessDelayForcesRoundRobinAcrossDomains() {
        UrlFrontier frontier = strictFrontier();
        // 3 URL cùng host a.com (ưu tiên rất cao) + 1 URL host khác (ưu tiên thấp).
        frontier.addUrl("https://a.com/1", 0, 50);
        frontier.addUrl("https://a.com/2", 0, 50);
        frontier.addUrl("https://a.com/3", 0, 50);
        frontier.addUrl("https://b.com/1", 5, 0);

        CrawlTask first = frontier.nextUrl();
        CrawlTask second = frontier.nextUrl();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("a.com", first.host(), "Lần đầu phải lấy host ưu tiên cao nhất");
        assertEquals("b.com", second.host(),
                "a.com vừa được truy cập nên đang trong politeness delay; "
                        + "phải chuyển sang host khác dù ưu tiên thấp hơn");
    }

    @Test
    void doesNotBlockForeverWhenOnlyDelayedDomainRemains() {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://a.com/2", 0, 0);

        long start = System.currentTimeMillis();
        assertNotNull(frontier.nextUrl());
        assertNotNull(frontier.nextUrl(), "URL thứ 2 cùng host vẫn phải lấy được, chỉ là phải chờ");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= UrlFrontier.POLITENESS_DELAY_MS,
                "Phải chờ đủ politeness delay giữa 2 lần truy cập cùng host, thực tế chờ " + elapsed + "ms");
        assertTrue(frontier.isEmpty());
    }

    @Test
    void respectsMaxSizeCap() {
        UrlFrontier frontier = new UrlFrontier(3);
        assertTrue(frontier.addUrl("https://a.com/1", 0, 0));
        assertTrue(frontier.addUrl("https://a.com/2", 0, 0));
        assertTrue(frontier.addUrl("https://a.com/3", 0, 0));
        assertFalse(frontier.addUrl("https://a.com/4", 0, 0), "Vượt maxSize phải bị từ chối");

        assertEquals(3, frontier.size());
        assertEquals(1, frontier.getDroppedDueToCapacity());
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(IllegalArgumentException.class, () -> new UrlFrontier(0));
        assertThrows(IllegalArgumentException.class,
                () -> new UrlFrontier(10, null, new StrictPrioritySelector(), 4));
        assertThrows(IllegalArgumentException.class,
                () -> new UrlFrontier(10, new DefaultPrioritizer(), new StrictPrioritySelector(), 0));
    }

    @Test
    void tracksDistinctDomainCount() {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://a.com/2", 0, 0);
        frontier.addUrl("https://b.com/1", 0, 0);
        assertEquals(2, frontier.domainCount());
    }

    /**
     * Bộ đếm host phải CO LẠI khi URL được phát ra hết — bản trước dùng một
     * map thời điểm truy cập lớn dần theo mọi host từng gặp và không bao giờ
     * co lại.
     */
    @Test
    void domainCountShrinksWhenUrlsAreHandedOut() {
        UrlFrontier frontier = strictFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://b.com/1", 0, 0);
        assertEquals(2, frontier.domainCount());

        frontier.nextUrl();
        assertEquals(1, frontier.domainCount());
        frontier.nextUrl();
        assertEquals(0, frontier.domainCount(), "Hết URL thì không host nào còn được đếm");
    }

    /** URL đi từ tầng trước sang tầng sau, không nằm ở cả hai nơi cùng lúc. */
    @Test
    void urlsMoveFromFrontTierToBackTier() {
        UrlFrontier frontier = strictFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://a.com/2", 0, 0);

        assertEquals(2, frontier.frontQueueSize(), "Lúc mới thêm, URL nằm ở tầng trước");
        assertEquals(0, frontier.backQueueSize());

        frontier.nextUrl();
        assertEquals(0, frontier.frontQueueSize());
        assertEquals(1, frontier.backQueueSize(), "URL còn lại đã được định tuyến sang tầng sau");
        assertEquals(1, frontier.activeHostCount());
    }

    @Test
    void neverHandsOutSameUrlTwiceUnderConcurrency() throws InterruptedException {
        UrlFrontier frontier = new UrlFrontier();
        int urlCount = 60;
        // Trải trên nhiều host để politeness delay không làm test chạy quá lâu.
        for (int i = 0; i < urlCount; i++) {
            frontier.addUrl("https://d" + i + ".com/page", 0, 0);
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> collected = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nullCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        CrawlTask task = frontier.nextUrl();
                        if (task == null) {
                            nullCount.incrementAndGet();
                            return;
                        }
                        collected.add(task.url());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "Các worker phải kết thúc, không được treo");
        pool.shutdownNow();

        assertEquals(urlCount, collected.size(), "Phải phát ra đúng số URL đã nạp, không thiếu không thừa");
        Set<String> unique = new HashSet<>(collected);
        assertEquals(urlCount, unique.size(), "Không URL nào được phát cho 2 thread khác nhau");
        assertTrue(frontier.isEmpty());
    }
}
