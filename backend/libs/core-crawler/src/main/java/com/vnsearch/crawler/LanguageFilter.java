package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Khối "Language Filter"</b> trong sơ đồ kiến trúc crawler — khối nằm
 * giữa {@code Content Parser} và {@code Content Seen?}.
 *
 * <p><b>Vì sao cần khối này.</b> Trước đây việc chặn ngoại ngữ chỉ dựa vào
 * tiền tố host ({@link UrlFilter#NON_VI_EN_HOST_PREFIXES}) — rẻ, nhưng chỉ
 * bắt được đúng những bản ngoại ngữ mà toà soạn <i>tự đặt lên subdomain có
 * quy ước</i>. Ba loại trang lọt qua hoàn toàn:
 * <ul>
 *   <li>bài tiếng Trung/Nhật/Hàn nằm lẫn trong đường dẫn của trang tiếng Việt
 *       ({@code /the-gioi/...} dẫn sang bản dịch);</li>
 *   <li>toà soạn đặt bản ngoại ngữ ở đường dẫn chứ không ở subdomain
 *       ({@code /zh/}, {@code /ru/});</li>
 *   <li>trang có tiêu đề tiếng Việt nhưng thân bài là ngôn ngữ khác.</li>
 * </ul>
 * Lọc theo NỘI DUNG bắt được cả ba, vì nó nhìn thứ thật sự sẽ vào chỉ mục.
 *
 * <p><b>Chính sách:</b> chỉ giữ <b>tiếng Việt</b> và <b>tiếng Anh</b>. Mọi
 * ngôn ngữ khác bị vứt ngay sau khi phân tích nội dung, và trang bị vứt
 * <b>không được bóc liên kết</b> — giống hệt cách {@code Content Seen?} xử lý
 * bản trùng. Đây là điểm quan trọng nhất về hiệu quả: một bài tiếng Trung
 * hầu như chỉ trỏ sang các bài tiếng Trung khác, nên chặn ở mức tài liệu mà
 * vẫn nuốt liên kết của nó thì crawler tiếp tục đi sâu vào vùng ngoại ngữ,
 * tải hàng nghìn trang chỉ để vứt.
 *
 * <p><b>Ba tầng bằng chứng, xếp theo độ tin cậy giảm dần.</b>
 * <ol>
 *   <li><b>Hệ chữ viết</b> ({@link Character.UnicodeScript}). Chữ Hán, Kana,
 *       Hangul, Kirin, Ả Rập, Thái... không thể là tiếng Việt hay tiếng Anh —
 *       đây là bằng chứng gần như tuyệt đối, và rẻ (một phép tra bảng cho mỗi
 *       ký tự). Ngưỡng {@value #FOREIGN_SCRIPT_THRESHOLD} chứ không phải 0 vì
 *       trang tiếng Việt bình thường vẫn có thể trích một tên riêng tiếng Hán
 *       hay một dòng tiếng Nga.</li>
 *   <li><b>Dấu phụ đặc trưng tiếng Việt.</b> Khối Unicode
 *       {@code U+1EA0..U+1EF9} cùng {@code ơ ư ă đ} gần như chỉ xuất hiện
 *       trong tiếng Việt — khác hẳn {@code é à ô} vốn dùng chung với tiếng
 *       Pháp, Bồ, Tây Ban Nha. Dùng chúng làm dấu hiệu nhận tiếng Việt cho
 *       kết quả chắc chắn hơn nhiều so với đếm từ chức năng.</li>
 *   <li><b>Từ chức năng tiếng Anh.</b> Với văn bản chữ Latinh không có dấu
 *       tiếng Việt, cách phân biệt tiếng Anh với tiếng Pháp/Đức/Indonesia là
 *       tỷ lệ từ chức năng: văn bản tiếng Anh thật có 25–40% token nằm trong
 *       {@link #ENGLISH_FUNCTION_WORDS}, các thứ tiếng khác hiếm khi vượt
 *       {@value #ENGLISH_WORD_THRESHOLD}.</li>
 * </ol>
 *
 * <p><b>Vì sao KHÔNG tin thuộc tính {@code <html lang>}.</b> Nó sai rất
 * thường xuyên: nhiều mã nguồn mở để mặc định {@code lang="en"} trên toàn
 * site, kể cả trang tiếng Việt. Ở đây thuộc tính đó chỉ được dùng làm phương
 * án cuối, khi trang quá ngắn để có bằng chứng nội dung (xem
 * {@link #MIN_TOKENS_FOR_CONTENT_EVIDENCE}).
 *
 * <p><b>Nguyên tắc khi thiếu bằng chứng: CHO QUA.</b> Trang chỉ có menu và
 * vài chữ (trang chuyên mục, trang phân trang) không đủ dữ liệu để kết luận
 * — mà đó lại chính là những trang cung cấp nhiều liên kết nhất. Vứt nhầm
 * chúng làm cụt cả một nhánh của đồ thị crawl, trong khi giữ nhầm chỉ tốn
 * một bản ghi gần như rỗng.
 *
 * <p>Thread-safe: bảng từ bất biến, chỉ có bộ đếm nguyên tử thay đổi.
 *
 * <p>Độ phức tạp: O(độ dài văn bản) cho mỗi trang, có trần
 * {@value #SAMPLE_LIMIT} ký tự nên không phụ thuộc độ dài bài.
 */
public class LanguageFilter {

    /** Mã ngôn ngữ được giữ lại. */
    public static final String VIETNAMESE = "vi";
    public static final String ENGLISH = "en";

    /** Không đủ bằng chứng để kết luận — vẫn được giữ (xem Javadoc lớp). */
    public static final String UNDETERMINED = "und";

    /** Chữ Latinh nhưng không phải tiếng Việt cũng không phải tiếng Anh. */
    public static final String OTHER_LATIN = "other";

    /**
     * Chỉ xét {@value} ký tự đầu của thân bài.
     *
     * <p>Một bài báo dài 40.000 ký tự không cho biết gì thêm về ngôn ngữ so
     * với 20.000 ký tự đầu, trong khi phần thừa nhân đôi chi phí trên đường
     * đi nóng của crawler — khối này chạy cho MỌI trang tải về.
     */
    private static final int SAMPLE_LIMIT = 20_000;

    /** Tỷ lệ chữ cái thuộc hệ chữ khác đủ để kết luận trang không phải vi/en. */
    private static final double FOREIGN_SCRIPT_THRESHOLD = 0.10;

    /** Tỷ lệ ký tự mang dấu đặc trưng tiếng Việt đủ để kết luận là tiếng Việt. */
    private static final double VIETNAMESE_DIACRITIC_THRESHOLD = 0.005;

    /** Tỷ lệ token là từ chức năng tiếng Việt đủ để kết luận là tiếng Việt. */
    private static final double VIETNAMESE_WORD_THRESHOLD = 0.05;

    /** Tỷ lệ token là từ chức năng tiếng Anh đủ để kết luận là tiếng Anh. */
    private static final double ENGLISH_WORD_THRESHOLD = 0.12;

    /**
     * Ngưỡng nới cho tiếng Anh khi {@code <html lang>} cũng khai là tiếng Anh
     * — hai bằng chứng yếu độc lập cộng lại đủ mạnh.
     */
    private static final double ENGLISH_WORD_THRESHOLD_WITH_HINT = 0.05;

    /** Dưới mức này thì văn bản quá ngắn để kết luận; rơi về {@code <html lang>}. */
    private static final int MIN_TOKENS_FOR_CONTENT_EVIDENCE = 40;

    /**
     * Ký tự chỉ tiếng Việt mới có, ngoài khối {@code U+1EA0..U+1EF9}.
     *
     * <p>Cố ý KHÔNG có {@code â ê ô é à á}: tiếng Pháp, Bồ Đào Nha và Tây Ban
     * Nha dùng chung, đưa vào sẽ nhận nhầm bài tiếng Pháp thành tiếng Việt.
     * {@code đ} cũng có trong tiếng Croatia nhưng crawler này không đi tới đó.
     */
    private static final Set<Character> VIETNAMESE_ONLY_CHARS = Set.of(
            'ơ', 'ư', 'ă', 'đ', 'Ơ', 'Ư', 'Ă', 'Đ');

    /**
     * Từ chức năng tiếng Việt. Chọn những từ <b>có dấu</b> là chính, vì từ
     * không dấu ({@code co}, {@code cho}, {@code ra}) trùng với chuỗi ký tự
     * của nhiều thứ tiếng khác.
     */
    private static final Set<String> VIETNAMESE_FUNCTION_WORDS = Set.of(
            "của", "và", "là", "được", "trong", "người", "những", "các", "có",
            "không", "cho", "với", "để", "này", "đã", "khi", "một", "đến", "về",
            "như", "từ", "cũng", "thì", "sẽ", "tại", "theo", "đó", "nhiều",
            "năm", "trên", "ở", "vào", "nhưng", "hơn", "phải", "làm", "việc");

    /**
     * Từ chức năng tiếng Anh.
     *
     * <p>Cố ý loại {@code a}, {@code an}, {@code no}, {@code en}, {@code de}
     * — chúng là từ thường gặp của tiếng Pháp/Tây Ban Nha/Indonesia, giữ lại
     * sẽ kéo điểm tiếng Anh của các thứ tiếng đó lên qua ngưỡng.
     */
    private static final Set<String> ENGLISH_FUNCTION_WORDS = Set.of(
            "the", "of", "and", "to", "in", "is", "that", "for", "it", "with",
            "was", "on", "are", "be", "this", "have", "from", "has", "not",
            "but", "they", "which", "said", "will", "would", "about", "more",
            "been", "were", "their", "its", "than", "when", "who", "what",
            "into", "also", "after", "over", "only", "other", "these", "such",
            "there", "his", "her", "our", "you", "he", "she", "we", "at", "by");

    /** Mã ngôn ngữ tiêu biểu cho từng hệ chữ viết, để thống kê đọc được. */
    private static final Map<Character.UnicodeScript, String> SCRIPT_LANGUAGE =
            new EnumMap<>(Map.of(
                    Character.UnicodeScript.HAN, "zh",
                    Character.UnicodeScript.HIRAGANA, "ja",
                    Character.UnicodeScript.KATAKANA, "ja",
                    Character.UnicodeScript.HANGUL, "ko",
                    Character.UnicodeScript.CYRILLIC, "ru",
                    Character.UnicodeScript.ARABIC, "ar",
                    Character.UnicodeScript.THAI, "th",
                    Character.UnicodeScript.DEVANAGARI, "hi",
                    Character.UnicodeScript.HEBREW, "he",
                    Character.UnicodeScript.GREEK, "el"));

    private final AtomicLong acceptedVietnamese = new AtomicLong();
    private final AtomicLong acceptedEnglish = new AtomicLong();
    private final AtomicLong acceptedUndetermined = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /** Đếm riêng từng ngôn ngữ bị loại — số liệu đưa thẳng vào báo cáo. */
    private final Map<String, AtomicLong> rejectedByLanguage = new ConcurrentHashMap<>();

    /**
     * Nhánh quyết định của khối: trang này có được vào corpus không?
     *
     * <p>Luôn <b>ghi mã ngôn ngữ đã nhận diện vào tài liệu</b>, kể cả khi
     * loại — tài liệu được giữ thì mang theo nhãn dùng được về sau (lọc kết
     * quả theo ngôn ngữ, chọn bộ tách từ đúng cho tiếng Anh và tiếng Việt).
     *
     * @return {@code true} nếu trang là tiếng Việt, tiếng Anh, hoặc quá ngắn
     *         để kết luận
     */
    public boolean accept(WebDocument doc) {
        if (doc == null) {
            return false;
        }
        // Ghép tiêu đề với thân bài: trang danh mục có thân bài rất ngắn,
        // tiêu đề khi đó là phần văn bản đáng tin duy nhất.
        String text = (doc.getTitle() == null ? "" : doc.getTitle() + " ")
                + (doc.getBodyText() == null ? "" : doc.getBodyText());
        String language = detect(doc.getLanguage(), text);
        doc.setLanguage(language);

        switch (language) {
            case VIETNAMESE -> acceptedVietnamese.incrementAndGet();
            case ENGLISH -> acceptedEnglish.incrementAndGet();
            case UNDETERMINED -> acceptedUndetermined.incrementAndGet();
            default -> {
                rejected.incrementAndGet();
                rejectedByLanguage.computeIfAbsent(language, k -> new AtomicLong())
                        .incrementAndGet();
                return false;
            }
        }
        return true;
    }

    /**
     * Nhận diện ngôn ngữ của một đoạn văn bản.
     *
     * @param declaredLang giá trị {@code <html lang>} đã chuẩn hoá (có thể
     *                     {@code null}) — chỉ dùng khi bằng chứng nội dung
     *                     không đủ
     * @return {@link #VIETNAMESE}, {@link #ENGLISH}, {@link #UNDETERMINED},
     *         {@link #OTHER_LATIN}, hoặc mã ngôn ngữ của hệ chữ ngoại lai
     */
    public String detect(String declaredLang, String text) {
        String hint = normalizeLanguageTag(declaredLang);
        if (text == null || text.isBlank()) {
            return isViOrEn(hint) ? hint : UNDETERMINED;
        }
        String sample = text.length() > SAMPLE_LIMIT ? text.substring(0, SAMPLE_LIMIT) : text;

        // --- Tầng 1: hệ chữ viết ---
        Map<String, Integer> foreignByLanguage = new HashMap<>();
        int letters = 0;
        int foreignLetters = 0;
        int vietnameseMarks = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if ((c >= 'Ạ' && c <= 'ỹ') || VIETNAMESE_ONLY_CHARS.contains(c)) {
                vietnameseMarks++;
                continue; // chắc chắn là chữ Latinh, khỏi tra bảng script
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            if (script == Character.UnicodeScript.LATIN
                    || script == Character.UnicodeScript.COMMON
                    || script == Character.UnicodeScript.INHERITED) {
                continue;
            }
            foreignLetters++;
            String lang = SCRIPT_LANGUAGE.getOrDefault(
                    script, script.name().toLowerCase(Locale.ROOT));
            foreignByLanguage.merge(lang, 1, Integer::sum);
        }
        if (letters == 0) {
            return isViOrEn(hint) ? hint : UNDETERMINED;
        }
        if ((double) foreignLetters / letters > FOREIGN_SCRIPT_THRESHOLD) {
            return foreignByLanguage.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(OTHER_LATIN);
        }

        // --- Tầng 2: dấu phụ đặc trưng tiếng Việt ---
        if ((double) vietnameseMarks / letters >= VIETNAMESE_DIACRITIC_THRESHOLD) {
            return VIETNAMESE;
        }

        // --- Tầng 3: từ chức năng ---
        String[] tokens = sample.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        int total = 0;
        int viHits = 0;
        int enHits = 0;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            total++;
            if (VIETNAMESE_FUNCTION_WORDS.contains(token)) {
                viHits++;
            } else if (ENGLISH_FUNCTION_WORDS.contains(token)) {
                enHits++;
            }
        }
        if (total < MIN_TOKENS_FOR_CONTENT_EVIDENCE) {
            // Quá ngắn để kết luận: tin tạm <html lang>, không có thì cho qua.
            return isViOrEn(hint) ? hint : UNDETERMINED;
        }
        if ((double) viHits / total >= VIETNAMESE_WORD_THRESHOLD) {
            return VIETNAMESE;
        }
        double englishRatio = (double) enHits / total;
        if (englishRatio >= ENGLISH_WORD_THRESHOLD
                || (ENGLISH.equals(hint) && englishRatio >= ENGLISH_WORD_THRESHOLD_WITH_HINT)) {
            return ENGLISH;
        }
        // Chữ Latinh, đủ dài, mà không có dấu hiệu của cả hai: Pháp, Đức,
        // Indonesia, Tây Ban Nha... — đúng thứ chính sách này loại.
        return OTHER_LATIN;
    }

    /** Rút thẻ ngôn ngữ chính từ {@code <html lang>}: {@code "en-US"} -> {@code "en"}. */
    public static String normalizeLanguageTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String lower = tag.trim().toLowerCase(Locale.ROOT);
        int dash = lower.indexOf('-');
        if (dash > 0) {
            lower = lower.substring(0, dash);
        }
        int underscore = lower.indexOf('_');
        if (underscore > 0) {
            lower = lower.substring(0, underscore);
        }
        return lower;
    }

    private static boolean isViOrEn(String code) {
        return VIETNAMESE.equals(code) || ENGLISH.equals(code);
    }

    public long getAcceptedVietnameseCount() {
        return acceptedVietnamese.get();
    }

    public long getAcceptedEnglishCount() {
        return acceptedEnglish.get();
    }

    /** Số trang được giữ vì quá ngắn để kết luận ngôn ngữ. */
    public long getAcceptedUndeterminedCount() {
        return acceptedUndetermined.get();
    }

    public long getRejectedCount() {
        return rejected.get();
    }

    /** Bảng "ngôn ngữ bị loại -> số trang", sắp giảm dần khi in ra báo cáo. */
    public Map<String, Long> getRejectedByLanguage() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        rejectedByLanguage.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> snapshot.put(e.getKey(), e.getValue().get()));
        return snapshot;
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        LanguageFilter filter = new LanguageFilter();

        String vi = "Đội tuyển Việt Nam giành chiến thắng trong trận đấu tối qua tại sân "
                + "vận động quốc gia, với hai bàn thắng được ghi trong hiệp hai của trận đấu "
                + "mà người hâm mộ cả nước đã chờ đợi từ nhiều tháng nay.";
        String en = "The national team won the match last night at the stadium, with two "
                + "goals that were scored in the second half of a game which fans from all "
                + "over the country had been waiting for over the last few months.";
        String zh = "越南国会常务委员会会议提交国会审议通过设立广宁市和北宁市的决议，"
                + "会议还讨论了其他若干重要议题，并就下一阶段的工作作出安排。";
        String fr = "Le championnat national de football a repris ses droits avec une "
                + "rencontre disputée hier soir dans le stade de la capitale, devant des "
                + "milliers de supporters venus de toutes les régions du pays.";

        System.out.println("Tiếng Việt : " + filter.detect("", vi));
        System.out.println("Tiếng Anh  : " + filter.detect("", en));
        System.out.println("Tiếng Trung: " + filter.detect("", zh) + "  <- bị loại");
        System.out.println("Tiếng Pháp : " + filter.detect("", fr) + "  <- bị loại");
        System.out.println("Trang ngắn : " + filter.detect("en", "Trang chủ") + "  <- theo <html lang>");
    }
}
