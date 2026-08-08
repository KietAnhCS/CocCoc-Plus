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

    /**
     * THU TU PHAI XAC DINH — day la dieu kien de phan trang chay dung.
     *
     * Truoc day map ben trong la ConcurrentHashMap, thu tu no tra ve khong xac
     * dinh va co the khac nhau giua hai lan doc. Hau qua: lo 2 cua tab Hinh anh
     * lech so voi lo 1, nen anh vua LAP vua THIEU khi nguoi dung cuon xuong —
     * khong loi nao duoc nem ra.
     */
    @Test
    void keepsInsertionOrderAcrossRepeatedReads() {
        for (int i = 0; i < 20; i++) {
            store.add(image("https://a.vn/bai", "https://a.vn/" + i + ".jpg"));
        }
        List<String> lan1 = store.forPage("https://a.vn/bai").stream()
                .map(ImageFound::imageUrl).toList();

        for (int lap = 0; lap < 5; lap++) {
            List<String> lanSau = store.forPage("https://a.vn/bai").stream()
                    .map(ImageFound::imageUrl).toList();
            assertEquals(lan1, lanSau, "Moi lan doc phai cho cung mot thu tu");
        }

        // Va thu tu do phai la thu tu CHEN VAO (= thu tu xuat hien trong DOM),
        // vi anh dau tien cua mot bai bao gan nhu luon la anh chinh.
        assertEquals("https://a.vn/0.jpg", lan1.get(0));
        assertEquals("https://a.vn/19.jpg", lan1.get(19));
    }

    /**
     * Cat lat lien tiep phai phu het danh sach, khong lap khong thieu — chinh
     * la thu ma tab Hinh anh lam khi cuon.
     */
    @Test
    void consecutiveSlicesCoverEverythingExactlyOnce() {
        for (int i = 0; i < 25; i++) {
            store.add(image("https://a.vn/bai", "https://a.vn/" + i + ".jpg"));
        }
        List<ImageFound> tatCa = store.forPages(List.of("https://a.vn/bai"), 100);
        assertEquals(25, tatCa.size());

        java.util.Set<String> gom = new java.util.LinkedHashSet<>();
        for (int from = 0; from < tatCa.size(); from += 8) {
            int to = Math.min(from + 8, tatCa.size());
            tatCa.subList(from, to).forEach(img -> gom.add(img.imageUrl()));
        }
        assertEquals(25, gom.size(), "Cac lat phai phu het va khong trung nhau");
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
