package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * Test cho {@link UrlFrontier} — hàng đợi ưu tiên tách theo domain của
 * crawler. Kiểm chứng cả thứ tự ưu tiên, politeness delay, chặn kích
 * thước, lẫn tính an toàn khi nhiều thread cùng dùng.
 */
class UrlFrontierTest {

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
        UrlFrontier frontier = new UrlFrontier();
        // Cùng độ sâu, cùng số backlink -> chỉ khác nhau ở đuôi .vn (+5 điểm).
        frontier.addUrl("https://example.com/a", 1, 2);
        frontier.addUrl("https://example.vn/b", 1, 2);

        UrlFrontier.Task first = frontier.nextUrl();
        assertNotNull(first);
        assertEquals("https://example.vn/b", first.url(), "Domain .vn phải được ưu tiên trước");
    }

    @Test
    void shallowerDepthGetsHigherPriorityWithinSameDomain() {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://a.com/deep", 5, 0);
        frontier.addUrl("https://a.com/shallow", 0, 0);

        UrlFrontier.Task first = frontier.nextUrl();
        assertNotNull(first);
        assertEquals("https://a.com/shallow", first.url(), "Trang gần seed hơn phải được crawl trước");
    }

    @Test
    void moreBacklinksGetsHigherPriorityWithinSameDomain() {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://a.com/cold", 1, 0);
        frontier.addUrl("https://a.com/hot", 1, 40);

        UrlFrontier.Task first = frontier.nextUrl();
        assertNotNull(first);
        assertEquals("https://a.com/hot", first.url(), "Trang nhiều backlink hơn phải được ưu tiên");
    }

    @Test
    void politenessDelayForcesRoundRobinAcrossDomains() {
        UrlFrontier frontier = new UrlFrontier();
        // 3 URL cùng domain a.com (ưu tiên rất cao) + 1 URL domain khác (ưu tiên thấp).
        frontier.addUrl("https://a.com/1", 0, 50);
        frontier.addUrl("https://a.com/2", 0, 50);
        frontier.addUrl("https://a.com/3", 0, 50);
        frontier.addUrl("https://b.com/1", 5, 0);

        UrlFrontier.Task first = frontier.nextUrl();
        UrlFrontier.Task second = frontier.nextUrl();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("a.com", hostOf(first.url()), "Lần đầu phải lấy domain ưu tiên cao nhất");
        assertEquals("b.com", hostOf(second.url()),
                "a.com vừa được truy cập nên đang trong politeness delay; "
                        + "phải chuyển sang domain khác dù ưu tiên thấp hơn");
    }

    @Test
    void doesNotBlockForeverWhenOnlyDelayedDomainRemains() {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://a.com/2", 0, 0);

        long start = System.currentTimeMillis();
        assertNotNull(frontier.nextUrl());
        assertNotNull(frontier.nextUrl(), "URL thứ 2 cùng domain vẫn phải lấy được, chỉ là phải chờ");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= UrlFrontier.POLITENESS_DELAY_MS,
                "Phải chờ đủ politeness delay giữa 2 lần truy cập cùng domain, thực tế chờ " + elapsed + "ms");
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
    void rejectsInvalidMaxSize() {
        assertThrows(IllegalArgumentException.class, () -> new UrlFrontier(0));
    }

    @Test
    void tracksDistinctDomainCount() {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://a.com/1", 0, 0);
        frontier.addUrl("https://a.com/2", 0, 0);
        frontier.addUrl("https://b.com/1", 0, 0);
        assertEquals(2, frontier.domainCount());
    }

    @Test
    void neverHandsOutSameUrlTwiceUnderConcurrency() throws InterruptedException {
        UrlFrontier frontier = new UrlFrontier();
        int urlCount = 60;
        // Trải trên nhiều domain để politeness delay không làm test chạy quá lâu.
        for (int i = 0; i < urlCount; i++) {
            frontier.addUrl("https://d" + i + ".com/page", 0, 0);
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> collected = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nullCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        UrlFrontier.Task task = frontier.nextUrl();
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

    private String hostOf(String url) {
        return java.net.URI.create(url).getHost();
    }
}
