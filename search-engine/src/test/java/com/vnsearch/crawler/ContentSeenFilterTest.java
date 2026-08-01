package com.vnsearch.crawler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSeenFilterTest {

    @Test
    void firstTimeContentIsNotSeen() {
        ContentSeenFilter filter = new ContentSeenFilter();
        assertFalse(filter.seenBefore("Noi dung bai bao."));
        assertEquals(1, filter.size());
        assertEquals(0, filter.getDuplicateCount());
    }

    @Test
    void identicalContentIsDetectedAsDuplicate() {
        ContentSeenFilter filter = new ContentSeenFilter();
        filter.seenBefore("Noi dung bai bao.");

        assertTrue(filter.seenBefore("Noi dung bai bao."));
        assertEquals(1, filter.size(), "Ban trung khong duoc tao them van tay moi");
        assertEquals(1, filter.getDuplicateCount());
    }

    /**
     * Ly do phai chuan hoa truoc khi bam: hai ban sao cua cung mot bai
     * thuong chi khac nhau o cach xuong dong trong HTML.
     */
    @Test
    void whitespaceAndCaseDifferencesStillCountAsDuplicate() {
        ContentSeenFilter filter = new ContentSeenFilter();
        filter.seenBefore("Doi tuyen Viet Nam thang 2-0.");

        assertTrue(filter.seenBefore("doi tuyen   Viet Nam\n\tthang 2-0."));
        assertEquals(1, filter.getDuplicateCount());
    }

    @Test
    void differentContentIsNotDuplicate() {
        ContentSeenFilter filter = new ContentSeenFilter();
        filter.seenBefore("Bai mot.");

        assertFalse(filter.seenBefore("Bai hai."));
        assertEquals(2, filter.size());
        assertEquals(0, filter.getDuplicateCount());
    }

    /**
     * Than bai rong thuong la dau hieu trich xuat that bai (trang dung bang
     * JavaScript), khong phai cac trang do giong nhau. Gom chung lam mot se
     * khien moi trang loi sau trang dau tien bi vut im lang.
     */
    @Test
    void blankContentIsAlwaysLetThrough() {
        ContentSeenFilter filter = new ContentSeenFilter();

        assertFalse(filter.seenBefore(""));
        assertFalse(filter.seenBefore("   \n  "));
        assertFalse(filter.seenBefore(null));

        assertEquals(0, filter.size());
        assertEquals(0, filter.getDuplicateCount());
        assertEquals(3, filter.getBlankSkippedCount());
    }

    @Test
    void fingerprintIsStableAndDiffersForDifferentText() {
        assertEquals(ContentSeenFilter.fingerprint("abc"), ContentSeenFilter.fingerprint("abc"));
        assertNotEquals(ContentSeenFilter.fingerprint("abc"), ContentSeenFilter.fingerprint("abd"));
        assertEquals(64, ContentSeenFilter.fingerprint("abc").length(), "SHA-256 dang hex la 64 ky tu");
    }

    /**
     * Kiem tra tinh nguyen tu: nhieu worker cung gap cung mot noi dung thi
     * dung MOT worker duoc di tiep, phan con lai phai thay "da trung".
     */
    @Test
    void concurrentIdenticalContentLetsExactlyOneThrough() throws Exception {
        ContentSeenFilter filter = new ContentSeenFilter();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger passedThrough = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (!filter.seenBefore("Cung mot noi dung.")) {
                        passedThrough.incrementAndGet();
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

        assertEquals(1, passedThrough.get(), "Dung mot luong duoc di tiep");
        assertEquals(threads - 1, filter.getDuplicateCount());
    }

    /** Van tay phai khac nhau giua cac noi dung khac nhau, khong bi dung do. */
    @Test
    void manyDistinctDocumentsProduceDistinctFingerprints() {
        ContentSeenFilter filter = new ContentSeenFilter();
        ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
        for (int i = 0; i < 2000; i++) {
            String text = "Bai viet so " + i + " voi noi dung rieng.";
            assertFalse(filter.seenBefore(text));
            assertTrue(seen.put(ContentSeenFilter.fingerprint(text), Boolean.TRUE) == null);
        }
        assertEquals(2000, filter.size());
    }
}
