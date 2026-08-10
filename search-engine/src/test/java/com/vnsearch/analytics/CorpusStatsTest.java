package com.vnsearch.analytics;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** So lieu mo ta corpus: tinh MOT luot, doc nhieu lan. */
class CorpusStatsTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    /**
     * Ham do do dai dung cho test: dem ky tu than bai.
     *
     * <p>Khi chay that, {@code SearchEngineFacade} truyen vao
     * {@code SearchIndex::getDocLength} (so token). Bai kiem thu dung mot ham
     * khac de khong phai dung ca mot chi muc chi de dem do dai — va do chinh la
     * ly do tham so nay duoc nhan tu ngoai vao.
     */
    private static final java.util.function.ToIntFunction<WebDocument> BODY_CHARS =
            document -> document.getBodyText() == null ? 0 : document.getBodyText().length();

    private static WebDocument doc(String url, String language, List<String> outlinks,
                                    String body, Instant crawledAt) {
        WebDocument document = new WebDocument(0, url, "Tieu de", "mo ta",
                body, outlinks, crawledAt);
        document.setLanguage(language);
        return document;
    }

    @Test
    void corpusRongTraVeSoKhongChuKhongPhaiNaN() {
        CorpusStats stats = CorpusStats.from(List.of(), BODY_CHARS, ZONE);

        assertEquals(0, stats.documents());
        assertEquals(0.0, stats.avgOutlinks());
        assertEquals(0.0, stats.avgDocLength());
        assertNull(stats.oldestCrawledAt());
        assertTrue(stats.topHosts().isEmpty());
        assertTrue(stats.languages().isEmpty());
    }

    @Test
    void danhSachNullDuocDoiXuNhuCorpusRong() {
        assertEquals(0, CorpusStats.from(null, BODY_CHARS, ZONE).documents());
    }

    @Test
    void demTrangHostVaLienKet() {
        CorpusStats stats = CorpusStats.from(List.of(
                doc("https://vnexpress.net/a", "vi",
                        List.of("https://vnexpress.net/b", "https://tuoitre.vn/x"),
                        "noi dung", Instant.parse("2026-08-09T08:00:00Z")),
                doc("https://www.vnexpress.net/b", "vi",
                        List.of("https://tuoitre.vn/x"),
                        "noi dung dai hon", Instant.parse("2026-08-10T08:00:00Z")),
                doc("https://tuoitre.vn/x", "en", List.of(),
                        "content", Instant.parse("2026-08-10T09:00:00Z"))), BODY_CHARS, ZONE);

        assertEquals(3, stats.documents());
        // www. bi bo nen hai trang vnexpress gop lam mot host.
        assertEquals(2, stats.distinctHosts());
        assertEquals(3, stats.totalOutlinks());
        assertEquals(2, stats.distinctLinkTargets()); // /b va /x, /x lap lai
        assertEquals(1.0, stats.avgOutlinks(), 1e-9);
        assertEquals(1, stats.danglingDocuments()); // tuoitre.vn/x khong co lien ket ra
        assertEquals("vnexpress.net", stats.topHosts().get(0).label());
        assertEquals(2, stats.topHosts().get(0).count());
    }

    @Test
    void phanBoNgonNguSapXepGiamDan() {
        CorpusStats stats = CorpusStats.from(List.of(
                doc("https://a.vn/1", "vi", List.of(), "x", Instant.EPOCH),
                doc("https://a.vn/2", "vi", List.of(), "x", Instant.EPOCH),
                doc("https://b.com/1", "en", List.of(), "x", Instant.EPOCH),
                doc("https://c.io/1", "", List.of(), "x", Instant.EPOCH)), BODY_CHARS, ZONE);

        List<UsageSnapshot.Counted> languages = stats.languages();

        assertEquals("vi", languages.get(0).label());
        assertEquals(2, languages.get(0).count());
        // Ngon ngu rong duoc chuan hoa thanh "und", khong de nhan rong.
        assertTrue(languages.stream().anyMatch(entry -> entry.label().equals("und")));
    }

    /**
     * Trung binh va trung vi phai bao ca hai: mot trang khong lo keo trung binh
     * di rat xa trong khi trung vi mo ta dung phan lon corpus.
     */
    @Test
    void baoCaTrungBinhVaTrungViDoDaiThanBai() {
        CorpusStats stats = CorpusStats.from(List.of(
                doc("https://a.vn/1", "vi", List.of(), "x".repeat(10), Instant.EPOCH),
                doc("https://a.vn/2", "vi", List.of(), "x".repeat(20), Instant.EPOCH),
                doc("https://a.vn/3", "vi", List.of(), "x".repeat(3_000), Instant.EPOCH)), BODY_CHARS, ZONE);

        assertEquals(1_010.0, stats.avgDocLength(), 1e-9);
        assertEquals(20, stats.medianDocLength());
    }

    @Test
    void mocThoiGianCuNhatVaMoiNhat() {
        Instant older = Instant.parse("2026-08-01T00:00:00Z");
        Instant newer = Instant.parse("2026-08-10T00:00:00Z");

        CorpusStats stats = CorpusStats.from(List.of(
                doc("https://a.vn/1", "vi", List.of(), "x", newer),
                doc("https://a.vn/2", "vi", List.of(), "x", older)), BODY_CHARS, ZONE);

        assertEquals(older, stats.oldestCrawledAt());
        assertEquals(newer, stats.newestCrawledAt());
    }

    @Test
    void chuoiNgayLienTucVaDuDoDai() {
        CorpusStats stats = CorpusStats.from(List.of(
                doc("https://a.vn/1", "vi", List.of(), "x", Instant.now())), BODY_CHARS, ZONE);

        List<CorpusStats.DayCount> days = stats.crawledPerDay();

        assertEquals(CorpusStats.DAYS_TRACKED, days.size());
        assertEquals(1, days.get(days.size() - 1).count()); // hom nay
        assertEquals(0, days.get(0).count());
    }

    @Test
    void trangKhongCoMocThoiGianKhongLamNgaGiCa() {
        CorpusStats stats = CorpusStats.from(List.of(
                doc("https://a.vn/1", "vi", List.of(), "x", null)), BODY_CHARS, ZONE);

        assertEquals(1, stats.documents());
        assertNull(stats.oldestCrawledAt());
    }
}
