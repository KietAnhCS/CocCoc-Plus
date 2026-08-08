package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kho anh phuc vu {@code GET /api/images}. */
class ImageStoreTest {

    private ImageStore store;

    @BeforeEach
    void setUp() {
        store = new ImageStore();
    }

    private static ImageFound image(String pageUrl, String imageUrl) {
        return ImageFound.metadataOnly(pageUrl, "a.vn", imageUrl, "mo ta", 800, 600);
    }

    @Test
    void storesAndReadsBackByPage() {
        assertTrue(store.add(image("https://a.vn/bai", "https://a.vn/1.jpg")));
        assertTrue(store.add(image("https://a.vn/bai", "https://a.vn/2.jpg")));

        assertEquals(2, store.forPage("https://a.vn/bai").size());
        assertEquals(1, store.pageCount());
        assertEquals(2, store.imageCount());
    }

    @Test
    void unknownPageGivesEmptyListNotNull() {
        assertEquals(List.of(), store.forPage("https://a.vn/khong-co"));
    }

    /**
     * Mot trang thuong tro toi cung mot anh tu nhieu cho (ban thuong, ban
     * srcset, anh nen). Kho phai tu khu, vi cung mot trang co the duoc xu ly
     * lai o phien crawl sau.
     */
    @Test
    void deduplicatesByImageUrl() {
        assertTrue(store.add(image("https://a.vn/bai", "https://a.vn/1.jpg")));
        assertFalse(store.add(image("https://a.vn/bai", "https://a.vn/1.jpg")));

        assertEquals(1, store.forPage("https://a.vn/bai").size());
        assertEquals(1, store.imageCount());
        assertEquals(1, store.getDuplicateCount());
    }

    /**
     * Khu theo imageUrl chu KHONG theo contentHash: van tay noi dung chi co khi
     * bat tai anh, ma mac dinh la tat — dung no lam khoa thi o cau hinh mac
     * dinh moi anh deu co khoa null va gop thanh mot.
     */
    @Test
    void twoDifferentImagesWithoutHashAreBothKept() {
        store.add(ImageFound.metadataOnly("https://a.vn/b", "a.vn", "https://a.vn/1.jpg", "", 1, 1));
        store.add(ImageFound.metadataOnly("https://a.vn/b", "a.vn", "https://a.vn/2.jpg", "", 1, 1));

        assertEquals(2, store.forPage("https://a.vn/b").size(),
                "Hai anh khac dia chi phai duoc giu ca hai du deu khong co contentHash");
    }

    /**
     * Thu tu trang truyen vao CHINH LA thu tu xep hang cua may tim kiem, nen
     * phai giu nguyen — nho vay tab Hinh anh thua huong chat luong xep hang cua
     * tab Web ma khong can mot mo hinh xep hang rieng cho anh.
     */
    @Test
    void forPagesKeepsThePageOrder() {
        store.add(image("https://a.vn/hai", "https://a.vn/2.jpg"));
        store.add(image("https://a.vn/mot", "https://a.vn/1.jpg"));

        List<ImageFound> images =
                store.forPages(List.of("https://a.vn/mot", "https://a.vn/hai"), 10);

        assertEquals(2, images.size());
        assertEquals("https://a.vn/1.jpg", images.get(0).imageUrl());
        assertEquals("https://a.vn/2.jpg", images.get(1).imageUrl());
    }

    /** Cung mot anh tren nhieu trang (logo, anh chuyen muc) chi hien mot lan. */
    @Test
    void forPagesShowsARepeatedImageOnlyOnce() {
        store.add(image("https://a.vn/mot", "https://a.vn/logo.png"));
        store.add(image("https://a.vn/hai", "https://a.vn/logo.png"));

        assertEquals(1, store.forPages(List.of("https://a.vn/mot", "https://a.vn/hai"), 10).size());
    }

    @Test
    void forPagesRespectsTheLimit() {
        for (int i = 0; i < 10; i++) {
            store.add(image("https://a.vn/bai", "https://a.vn/" + i + ".jpg"));
        }
        assertEquals(3, store.forPages(List.of("https://a.vn/bai"), 3).size());
        assertEquals(0, store.forPages(List.of("https://a.vn/bai"), 0).size());
        assertEquals(0, store.forPages(null, 5).size());
    }

    @Test
    void respectsThePerPageLimit() {
        for (int i = 0; i < ImageStore.MAX_IMAGES_PER_PAGE + 10; i++) {
            store.add(image("https://a.vn/bai", "https://a.vn/" + i + ".jpg"));
        }
        assertEquals(ImageStore.MAX_IMAGES_PER_PAGE, store.forPage("https://a.vn/bai").size());
        assertEquals(10, store.getDroppedByPerPageLimitCount());
    }

    @Test
    void nullImageIsIgnored() {
        assertFalse(store.add(null));
        assertEquals(0, store.imageCount());
    }

    @Test
    void clearEmptiesTheStore() {
        store.add(image("https://a.vn/bai", "https://a.vn/1.jpg"));
        store.clear();
        assertEquals(0, store.pageCount());
        assertEquals(List.of(), store.forPage("https://a.vn/bai"));
    }

    @Test
    void snapshotReportsTheHeadlineNumbers() {
        store.add(image("https://a.vn/bai", "https://a.vn/1.jpg"));
        store.add(image("https://a.vn/bai", "https://a.vn/1.jpg")); // trung

        Map<String, Object> snapshot = store.snapshot();
        assertEquals(1, snapshot.get("pagesWithImages"));
        assertEquals(1L, snapshot.get("images"));
        assertEquals(1L, snapshot.get("duplicates"));
    }

    /**
     * Kho bi ghi tu nhieu worker cua crawler VA tu nhieu luong consumer Kafka
     * cung luc. Bai test nay bat mat mat khi dem.
     */
    @Test
    void isThreadSafeUnderConcurrentWrites() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        store.add(image("https://a.vn/trang-" + id, "https://a.vn/" + id + "-" + i + ".jpg"));
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(threads, store.pageCount());
        // MAX_IMAGES_PER_PAGE chan lai, nen moi trang chi giu duoc phan dau.
        assertEquals((long) threads * ImageStore.MAX_IMAGES_PER_PAGE, store.imageCount());
    }
}
