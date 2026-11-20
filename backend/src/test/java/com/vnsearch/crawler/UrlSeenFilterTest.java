package com.vnsearch.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlSeenFilterTest {

    @Test
    void firstSightOfUrlIsNew() {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(100);

        assertTrue(filter.markSeenIfNew("https://a.vn/x"));
        assertFalse(filter.markSeenIfNew("https://a.vn/x"), "Lan thu hai phai bao da gap");
        assertEquals(1, filter.getSeenCount());
    }

    @Test
    void seenBeforeDoesNotRecord() {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(100);

        assertFalse(filter.seenBefore("https://a.vn/x"));
        assertEquals(0, filter.getSeenCount(), "Chi hoi thi khong duoc ghi nhan");
        assertTrue(filter.markSeenIfNew("https://a.vn/x"));
        assertTrue(filter.seenBefore("https://a.vn/x"));
    }

    @Test
    void blankUrlIsNeverAccepted() {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(100);
        assertFalse(filter.markSeenIfNew(null));
        assertFalse(filter.markSeenIfNew("  "));
        assertEquals(0, filter.getSeenCount());
    }

    /**
     * Bo loc phai duoc cap phat theo SO URL SE GAP chu khong phai so trang se
     * luu: moi trang tin sinh hang chuc lien ket ra, tat ca deu di qua bo loc.
     * Cap phat theo maxPages se khien ty le bit bat vot len gan 100%.
     */
    @Test
    void filterIsSizedForUrlsSeenNotPagesStored() {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(5_000);
        int expectedUrls = 5_000 * UrlSeenFilter.URLS_SEEN_PER_PAGE;

        // m = -n*ln(p)/(ln2)^2, voi p = 0.01 thi m/n ~ 9.6 bit moi phan tu.
        assertTrue(filter.getNumBits() > expectedUrls * 9,
                "Bo loc qua nho so voi so URL du kien: " + filter.getNumBits());
    }

    @Test
    void smallCrawlStillGetsMinimumFilterSize() {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(1);
        assertTrue(filter.getNumBits() > UrlSeenFilter.MIN_EXPECTED_URLS * 9);
    }

    /**
     * BloomFilter khong thread-safe (phep {@code bits[i] |= mask} la doc-sua-ghi).
     * UrlSeenFilter phai dong bo lai: neu mat mot phep ghi, bo loc sinh false
     * negative va crawler tai lai trang cu.
     */
    @Test
    void concurrentMarkOfSameUrlLetsExactlyOneThrough() throws Exception {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(1_000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (filter.markSeenIfNew("https://a.vn/cung-mot-url")) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "Test bi treo");
        pool.shutdownNow();

        assertEquals(1, accepted.get(), "Dung mot luong duoc xep URL vao hang doi");
    }

    @Test
    void withoutStorageNothingIsWritten() {
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(100);
        filter.markSeenIfNew("https://a.vn/x");

        assertFalse(filter.getUrlStorage().isEnabled());
        assertEquals(0, filter.getUrlStorage().getWrittenCount());
        assertEquals(0, filter.replayFromStorage());
    }

    @Test
    void storageRecordsEverySeenUrl(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seen-urls.txt");
        UrlStorage storage = UrlStorage.file(file);
        UrlSeenFilter filter = UrlSeenFilter.forMaxPages(100, storage);

        filter.markSeenIfNew("https://a.vn/x");
        filter.markSeenIfNew("https://a.vn/y");
        filter.markSeenIfNew("https://a.vn/x"); // trung, khong duoc ghi lai
        storage.close();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("https://a.vn/x", "https://a.vn/y"), lines);
        assertEquals(2, storage.getWrittenCount());
    }

    /** Phien sau nap lai kho URL thi khong tai lai nhung trang da co. */
    @Test
    void replayRebuildsFilterFromStorage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seen-urls.txt");

        UrlStorage firstSession = UrlStorage.file(file);
        UrlSeenFilter first = new UrlSeenFilter(10_000, firstSession);
        first.markSeenIfNew("https://a.vn/x");
        first.markSeenIfNew("https://a.vn/y");
        firstSession.close();

        UrlStorage secondSession = UrlStorage.file(file);
        UrlSeenFilter second = new UrlSeenFilter(10_000, secondSession);
        assertFalse(second.seenBefore("https://a.vn/x"), "Truoc khi nap thi chua biet gi");

        assertEquals(2, second.replayFromStorage());
        assertTrue(second.seenBefore("https://a.vn/x"));
        assertTrue(second.seenBefore("https://a.vn/y"));
        assertFalse(second.markSeenIfNew("https://a.vn/x"), "Da nap roi thi khong crawl lai");
        secondSession.close();
    }

    @Test
    void replayOnMissingFileReturnsZero(@TempDir Path dir) {
        UrlStorage storage = UrlStorage.file(dir.resolve("chua-ton-tai.txt"));
        UrlSeenFilter filter = new UrlSeenFilter(1_000, storage);
        assertEquals(0, filter.replayFromStorage());
    }
}
