package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử khối {@code Language Filter} — chính sách "chỉ giữ tiếng Việt và
 * tiếng Anh".
 *
 * <p>Văn bản mẫu cố tình dài hơn {@code MIN_TOKENS_FOR_CONTENT_EVIDENCE}: đó
 * là ngưỡng phân đôi hành vi của bộ lọc, và một mẫu ngắn hơn sẽ đi vào nhánh
 * "tin {@code <html lang>}" chứ không kiểm thử thứ mình định kiểm thử.
 */
class LanguageFilterTest {

    private static final String VIETNAMESE_ARTICLE =
            "Đội tuyển Việt Nam đã giành chiến thắng trong trận đấu tối qua tại sân vận động "
            + "quốc gia, với hai bàn thắng được ghi trong hiệp hai của trận đấu mà người hâm "
            + "mộ cả nước đã chờ đợi từ nhiều tháng nay. Huấn luyện viên trưởng cho biết đội "
            + "sẽ tiếp tục tập luyện để chuẩn bị cho vòng đấu kế tiếp vào cuối tháng này.";

    private static final String ENGLISH_ARTICLE =
            "The national team won the match last night at the stadium, with two goals that "
            + "were scored in the second half of a game which fans from all over the country "
            + "had been waiting for over the last few months. The head coach said that the "
            + "team will keep training for the next round at the end of this month.";

    private static final String CHINESE_ARTICLE =
            "越南国会常务委员会会议提交国会审议通过设立广宁市和北宁市的决议，会议还讨论了其他若干"
            + "重要议题，并就下一阶段的工作作出安排，要求各有关部门认真落实会议精神，确保各项任务"
            + "按期完成，为国家经济社会发展作出更大贡献。";

    private static final String FRENCH_ARTICLE =
            "Le championnat national de football a repris ses droits avec une rencontre "
            + "disputée hier soir dans le stade de la capitale, devant des milliers de "
            + "supporters venus de toutes les régions du pays pour encourager leur équipe "
            + "favorite lors de cette soirée particulièrement attendue par les amateurs.";

    @Test
    void keepsVietnamese() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals(LanguageFilter.VIETNAMESE, filter.detect("", VIETNAMESE_ARTICLE));
    }

    @Test
    void keepsEnglish() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals(LanguageFilter.ENGLISH, filter.detect("", ENGLISH_ARTICLE));
    }

    /** Hệ chữ viết là bằng chứng mạnh nhất: chữ Hán không thể là tiếng Việt. */
    @Test
    void rejectsChineseByScript() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals("zh", filter.detect("", CHINESE_ARTICLE));
    }

    /**
     * Tiếng Pháp là ca khó nhất: cùng hệ chữ Latinh, cùng có dấu phụ.
     *
     * <p>Bắt được nó là lý do bộ lọc chỉ đếm dấu ĐẶC TRƯNG tiếng Việt
     * ({@code ơ ư ă đ} và khối {@code U+1EA0..U+1EF9}) chứ không đếm mọi ký tự
     * có dấu — {@code é à ô} thì tiếng Pháp cũng đầy.
     */
    @Test
    void rejectsFrenchEvenThoughItIsLatinWithDiacritics() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals(LanguageFilter.OTHER_LATIN, filter.detect("", FRENCH_ARTICLE));
    }

    /**
     * Một tên riêng tiếng Hán trong bài tiếng Việt không được làm trang bị loại
     * — vì thế ngưỡng hệ chữ là 10% chứ không phải 0.
     */
    @Test
    void toleratesAFewForeignCharactersInsideAVietnamesePage() {
        LanguageFilter filter = new LanguageFilter();
        String mixed = VIETNAMESE_ARTICLE + " (Trung Quốc: 越南)";
        assertEquals(LanguageFilter.VIETNAMESE, filter.detect("", mixed));
    }

    /**
     * Trang quá ngắn thì thiếu bằng chứng: rơi về {@code <html lang>}, và khi
     * cả thẻ đó cũng không có thì CHO QUA.
     *
     * <p>Vứt những trang này sẽ cắt cụt cả một nhánh đồ thị crawl, vì trang
     * danh mục ít chữ lại chính là nơi có nhiều liên kết nhất.
     */
    @Test
    void shortPagesFallBackToDeclaredLanguageAndAreKeptWhenUnknown() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals(LanguageFilter.ENGLISH, filter.detect("en-US", "Home page"));
        assertEquals(LanguageFilter.VIETNAMESE, filter.detect("vi", "Trang chu"));
        assertEquals(LanguageFilter.UNDETERMINED, filter.detect("", "Trang chu"));
        assertEquals(LanguageFilter.UNDETERMINED, filter.detect("fr", "Accueil"));
    }

    /**
     * Dấu đặc trưng tiếng Việt kết luận được ngay cả khi văn bản quá ngắn cho
     * phép đếm từ chức năng — hai chữ "Trang chủ" đã đủ.
     *
     * <p>Đây là lý do tầng dấu phụ đứng TRƯỚC phép kiểm tra độ dài: chỉ cần
     * một ký tự thuộc khối {@code U+1EA0..U+1EF9} là bằng chứng đã đủ mạnh,
     * không cần tới 40 token.
     */
    @Test
    void vietnameseDiacriticsDecideEvenInVeryShortText() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals(LanguageFilter.VIETNAMESE, filter.detect("", "Trang chủ"));
        assertEquals(LanguageFilter.VIETNAMESE, filter.detect("en", "Thể thao"));
    }

    /**
     * Nội dung THẮNG {@code <html lang>} khi hai thứ mâu thuẫn.
     *
     * <p>Rất nhiều mã nguồn website để mặc định {@code lang="en"} trên toàn
     * site kể cả trang tiếng Việt; tin thẻ đó sẽ gán nhầm nhãn cho phần lớn
     * corpus.
     */
    @Test
    void contentBeatsAWrongHtmlLangAttribute() {
        LanguageFilter filter = new LanguageFilter();
        assertEquals(LanguageFilter.VIETNAMESE, filter.detect("en", VIETNAMESE_ARTICLE));
        assertEquals(LanguageFilter.ENGLISH, filter.detect("vi", ENGLISH_ARTICLE));
        assertEquals("zh", filter.detect("vi", CHINESE_ARTICLE));
    }

    @Test
    void acceptTagsTheDocumentAndCountsPerLanguage() {
        LanguageFilter filter = new LanguageFilter();

        assertTrue(filter.accept(docWith(VIETNAMESE_ARTICLE)));
        assertTrue(filter.accept(docWith(ENGLISH_ARTICLE)));
        assertFalse(filter.accept(docWith(CHINESE_ARTICLE)));
        assertFalse(filter.accept(docWith(FRENCH_ARTICLE)));

        assertEquals(1, filter.getAcceptedVietnameseCount());
        assertEquals(1, filter.getAcceptedEnglishCount());
        assertEquals(2, filter.getRejectedCount());
        assertEquals(1L, filter.getRejectedByLanguage().get("zh"));
        assertEquals(1L, filter.getRejectedByLanguage().get(LanguageFilter.OTHER_LATIN));
    }

    /** Tài liệu được giữ phải mang theo nhãn ngôn ngữ cho khâu đánh chỉ mục. */
    @Test
    void acceptWritesDetectedLanguageBackIntoTheDocument() {
        LanguageFilter filter = new LanguageFilter();
        WebDocument doc = docWith(ENGLISH_ARTICLE);
        doc.setLanguage("vi"); // <html lang> khai sai

        assertTrue(filter.accept(doc));
        assertEquals(LanguageFilter.ENGLISH, doc.getLanguage());
    }

    /** Tiêu đề được ghép vào văn bản xét: trang danh mục gần như chỉ có tiêu đề. */
    @Test
    void titleCountsAsEvidenceWhenTheBodyIsEmpty() {
        LanguageFilter filter = new LanguageFilter();
        WebDocument doc = new WebDocument();
        doc.setUrl("https://cn.example.vn/x");
        doc.setTitle(CHINESE_ARTICLE);
        doc.setBodyText("");

        assertFalse(filter.accept(doc));
        assertEquals("zh", doc.getLanguage());
    }

    @Test
    void normalizeLanguageTagKeepsOnlyThePrimarySubtag() {
        assertEquals("en", LanguageFilter.normalizeLanguageTag("en-US"));
        assertEquals("vi", LanguageFilter.normalizeLanguageTag("  VI  "));
        assertEquals("zh", LanguageFilter.normalizeLanguageTag("zh_CN"));
        assertEquals("", LanguageFilter.normalizeLanguageTag(null));
        assertEquals("", LanguageFilter.normalizeLanguageTag(""));
    }

    private static WebDocument docWith(String bodyText) {
        WebDocument doc = new WebDocument();
        doc.setUrl("https://example.vn/" + Math.abs(bodyText.hashCode()));
        doc.setTitle("");
        doc.setBodyText(bodyText);
        return doc;
    }
}
