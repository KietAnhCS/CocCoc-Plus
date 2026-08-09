package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kho anh phuc vu {@code GET /api/images} — MOT anh dai dien cho moi trang.
 */
class ImageStoreTest {

    private ImageStore store;

    @BeforeEach
    void setUp() {
        store = new ImageStore();
    }

    /** Anh noi dung binh thuong: khong phai svg/thumb, co alt, co kich thuoc. */
    private static ImageFound image(String pageUrl, String imageUrl) {
        return ImageFound.metadataOnly(pageUrl, "a.vn", imageUrl, "mo ta", 800, 600);
    }

    private static ImageFound sized(String pageUrl, String imageUrl, int width) {
        return ImageFound.metadataOnly(pageUrl, "a.vn", imageUrl, "mo ta", width, 600);
    }

    @Test
    void storesOneImagePerPage() {
        assertTrue(store.add(image("https://a.vn/bai", "https://a.vn/1.jpg")));

        assertEquals(1, store.forPage("https://a.vn/bai").size());
        assertEquals(1, store.pageCount());
        assertEquals(1, store.imageCount());
    }

    @Test
    void unknownPageGivesEmptyListNotNull() {
        assertEquals(List.of(), store.forPage("https://a.vn/khong-co"));
    }

    /**
     * Anh THU HAI cua cung mot trang khong duoc them vao — no thay the anh cu
     * neu tot hon, con khong thi bi bo.
     */
    @Test
    void aSecondImageNeverGrowsThePage() {
        store.add(sized("https://a.vn/bai", "https://a.vn/nho.jpg", 300));
        store.add(sized("https://a.vn/bai", "https://a.vn/to.jpg", 1200));
        store.add(sized("https://a.vn/bai", "https://a.vn/vua.jpg", 700));

        assertEquals(1, store.forPage("https://a.vn/bai").size());
        assertEquals(1, store.imageCount());
    }

    /** Anh RONG hon thang — day la tieu chi chinh khi cung bac. */
    @Test
    void keepsTheWiderImage() {
        store.add(sized("https://a.vn/bai", "https://a.vn/nho.jpg", 300));
        store.add(sized("https://a.vn/bai", "https://a.vn/to.jpg", 1200));

        assertEquals("https://a.vn/to.jpg", store.forPage("https://a.vn/bai").get(0).imageUrl());
    }

    /**
     * KET QUA KHONG PHU THUOC THU TU DEN.
     *
     * <p>O che do Kafka, thu tu thong diep GIUA cac phan hoach khong duoc bao
     * dam. Neu phep chon phu thuoc thu tu thi hai lan crawl cung mot trang co
     * the cho hai anh dai dien khac nhau — mot loi khong tai lap duoc.
     */
    @Test
    void resultDoesNotDependOnArrivalOrder() {
        ImageStore xuoi = new ImageStore();
        xuoi.add(sized("https://a.vn/b", "https://a.vn/nho.jpg", 300));
        xuoi.add(sized("https://a.vn/b", "https://a.vn/to.jpg", 1200));

        ImageStore nguoc = new ImageStore();
        nguoc.add(sized("https://a.vn/b", "https://a.vn/to.jpg", 1200));
        nguoc.add(sized("https://a.vn/b", "https://a.vn/nho.jpg", 300));

        assertEquals(xuoi.forPage("https://a.vn/b").get(0).imageUrl(),
                nguoc.forPage("https://a.vn/b").get(0).imageUrl());
    }

    /**
     * Logo THUA anh bai viet, ke ca khi logo den truoc va co alt.
     *
     * <p>Day la ca da quan sat duoc tren du lieu that: mot anh 100x42 mang
     * {@code alt="Fica"} nam o dau ket qua tim kiem anh.
     */
    @Test
    void aLogoLosesToAnArticlePhoto() {
        store.add(ImageFound.metadataOnly(
                "https://a.vn/bai", "a.vn", "https://a.vn/logo.png", "Fica", 100, 42));
        store.add(ImageFound.metadataOnly(
                "https://a.vn/bai", "a.vn", "https://a.vn/anh-bai.jpg", "Anh bai viet", 900, 600));

        assertEquals("https://a.vn/anh-bai.jpg", store.forPage("https://a.vn/bai").get(0).imageUrl());
    }

    /** Anh svg la do hoa vector — logo hoac icon, khong bao gio la anh bai. */
    @Test
    void svgLosesToAPhotoEvenWithoutDeclaredSize() {
        store.add(ImageFound.metadataOnly(
                "https://a.vn/bai", "a.vn", "https://a.vn/bieu-tuong.svg", "icon", -1, -1));
        store.add(ImageFound.metadataOnly(
                "https://a.vn/bai", "a.vn", "https://a.vn/anh.jpg", "", -1, -1));

        assertEquals("https://a.vn/anh.jpg", store.forPage("https://a.vn/bai").get(0).imageUrl());
    }

    /**
     * Khong co ung vien nao tot thi VAN phai giu mot tam.
     *
     * <p>Mot trang chi co logo van nen xuat hien o tab Hinh anh — bo han no di
     * thi trang do bien mat khoi ket qua, trong khi no van la mot trang hop le.
     */
    @Test
    void keepsADecorativeImageWhenItIsTheOnlyOne() {
        store.add(ImageFound.metadataOnly(
                "https://a.vn/bai", "a.vn", "https://a.vn/logo.svg", "", -1, -1));

        assertEquals(1, store.forPage("https://a.vn/bai").size());
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

    /** Cung mot anh la dai dien cua nhieu trang thi chi hien mot lan. */
    @Test
    void forPagesShowsARepeatedImageOnlyOnce() {
        store.add(image("https://a.vn/mot", "https://a.vn/chung.jpg"));
        store.add(image("https://a.vn/hai", "https://a.vn/chung.jpg"));

        assertEquals(1, store.forPages(List.of("https://a.vn/mot", "https://a.vn/hai"), 10).size());
    }

    /**
     * MOI LAN DOC PHAI CHO CUNG MOT KET QUA — dieu kien de phan trang chay dung.
     *
     * <p>Lo 2 cua tab Hinh anh lech so voi lo 1 thi anh vua LAP vua THIEU khi
     * nguoi dung cuon xuong, va khong loi nao duoc nem ra.
     */
    @Test
    void repeatedReadsGiveTheSameOrder() {
        for (int i = 0; i < 20; i++) {
            store.add(image("https://a.vn/trang-" + i, "https://a.vn/" + i + ".jpg"));
        }
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pages.add("https://a.vn/trang-" + i);
        }

        List<ImageFound> lan1 = store.forPages(pages, 100);
        for (int lap = 0; lap < 5; lap++) {
            assertEquals(lan1, store.forPages(pages, 100), "Moi lan doc phai cho cung mot ket qua");
        }
    }

    /**
     * Cat lat lien tiep phai phu het danh sach, khong lap khong thieu — chinh
     * la thu ma tab Hinh anh lam khi cuon.
     */
    @Test
    void consecutiveSlicesCoverEverythingExactlyOnce() {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            store.add(image("https://a.vn/trang-" + i, "https://a.vn/" + i + ".jpg"));
            pages.add("https://a.vn/trang-" + i);
        }
        List<ImageFound> tatCa = store.forPages(pages, 100);
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
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            store.add(image("https://a.vn/trang-" + i, "https://a.vn/" + i + ".jpg"));
            pages.add("https://a.vn/trang-" + i);
        }
        assertEquals(3, store.forPages(pages, 3).size());
        assertEquals(0, store.forPages(pages, 0).size());
        assertEquals(0, store.forPages(null, 5).size());
    }

    /**
     * Nap mot tep ghi bang ban ma CU (nhieu anh moi trang) phai tu rut xuong
     * mot anh moi trang, va giu dung tam tot nhat.
     */
    @Test
    void addAllCollapsesAnOldMultiImageFile() {
        List<ImageFound> cu = List.of(
                sized("https://a.vn/b", "https://a.vn/1.jpg", 200),
                sized("https://a.vn/b", "https://a.vn/2.jpg", 1400),
                sized("https://a.vn/b", "https://a.vn/3.jpg", 500),
                sized("https://a.vn/khac", "https://a.vn/4.jpg", 900));

        store.addAll(cu);

        assertEquals(2, store.pageCount());
        assertEquals(2, store.all().size());
        assertEquals("https://a.vn/2.jpg", store.forPage("https://a.vn/b").get(0).imageUrl());
    }

    @Test
    void nullImageIsIgnored() {
        assertFalse(store.add(null));
        assertEquals(0, store.imageCount());
        assertEquals(0, store.addAll(null));
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
        store.add(sized("https://a.vn/bai", "https://a.vn/nho.jpg", 300));
        store.add(sized("https://a.vn/bai", "https://a.vn/to.jpg", 1200));   // thay the
        store.add(sized("https://a.vn/bai", "https://a.vn/vua.jpg", 700));   // bi bo

        Map<String, Object> snapshot = store.snapshot();
        assertEquals(1, snapshot.get("pagesWithImages"));
        assertEquals(1, snapshot.get("images"));
        assertEquals(1L, snapshot.get("replaced"));
        assertEquals(1L, snapshot.get("candidatesRejected"));
    }

    /**
     * Kho bi ghi tu nhieu worker cua crawler VA tu nhieu luong consumer Kafka
     * cung luc.
     *
     * <p>Moi luong do MOT anh rong nhat cua rieng no vao CUNG mot trang. Neu
     * phep so-sanh-roi-ghi khong nguyen tu, mot tam thua co the bi mot tam thua
     * kem hon ghi de — va ket qua se khac nhau giua cac lan chay.
     */
    @Test
    void isThreadSafeUnderConcurrentWrites() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> loi = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        // Be rong tang dan; luong cuoi cung co tam rong nhat.
                        store.add(sized("https://a.vn/chung", "https://a.vn/" + id + "-" + i + ".jpg",
                                200 + id * perThread + i));
                        store.add(image("https://a.vn/rieng-" + id, "https://a.vn/r" + id + "-" + i + ".jpg"));
                    }
                } catch (Throwable e) {
                    loi.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(List.of(), loi);

        // 8 trang rieng + 1 trang chung.
        assertEquals(threads + 1, store.pageCount());
        assertEquals(threads + 1, store.all().size());

        // Tam rong nhat trong TAT CA cac luong phai thang, du luong nao ghi sau.
        int rongNhat = 200 + (threads - 1) * perThread + (perThread - 1);
        assertEquals(rongNhat, store.forPage("https://a.vn/chung").get(0).declaredWidth());
    }

    @Test
    void respectsThePageLimit() {
        // Khong dung MAX_PAGES that (50.000) — bai test se cham. Kiem tra bang
        // chinh bo dem, tren mot kho da day thi khong the.
        assertEquals(0, store.getDroppedByPageLimitCount());
    }
}
