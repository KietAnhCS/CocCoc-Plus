package com.vnsearch.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tokenizer tieng Viet tu cai dat, khong dung bat ky thu vien NLP/tach tu
 * co san nao (vd: VnCoreNLP, UETsegmenter...).
 *
 * <p>Quy trinh xu ly cho moi doan van ban:
 * <ol>
 *   <li>Chuan hoa Unicode ve NFC (tieng Viet co 2 cach go dau: to hop
 *       "NFD" hay dung san "NFC" — chuan hoa ve 1 dang duy nhat de tranh
 *       cung mot tu tao ra 2 chuoi Unicode khac nhau).</li>
 *   <li>Lowercase, thay moi ky tu khong phai chu/so bang khoang trang
 *       (bo dau cau).</li>
 *   <li>Tach theo khoang trang thanh danh sach "tieng" (syllable).</li>
 *   <li>Ghep tu ghep bang <b>quy hoach dong cuc dai trong so</b>
 *       ({@link MaxWeightSegmenter}) tren tu dien co tan suat
 *       ({@link VietnameseWordDictionary}). Truoc day buoc nay la
 *       <b>Longest Matching</b> tham lam tren mot {@code HashSet} 154 muc —
 *       xem muc "Vi sao doi thuat toan" ben duoi.</li>
 *   <li>Loai stopword (chi ap dung cho token 1 tieng, doc tu
 *       {@code vietnamese-stopwords.txt}).</li>
 *   <li>Sinh them ban khong dau cua moi token (Normalizer NFD + regex bo
 *       ky tu combining mark {@code \p{M}}, rieng "đ" xu ly thu cong vi no
 *       la 1 ky tu Latin doc lap, khong phai to hop base+dau) de ho tro
 *       tim khong dau ("may tinh" van tim thay "máy tính").</li>
 * </ol>
 *
 * <h2>Vi sao doi thuat toan ghep tu</h2>
 *
 * <p>Javadoc cua {@link Tokenizer} da chi ra rang bo tach tu la <b>tran chat
 * luong</b> cua ca he thong, va nguyen nhan la tu dien tu ghep chi co 154 muc.
 * Ban nay go bo tran do o ca hai phia:
 *
 * <ol>
 *   <li><b>Tu dien: 154 &rarr; hon 185.000 muc co tan suat</b>, trich tu
 *       {@code coccoc-tokenizer} (LGPL-3.0) — bo tach tu dang chay trong
 *       production cua Coc Coc. Xem {@link VietnameseWordDictionary}.</li>
 *   <li><b>Thuat toan: Longest Matching &rarr; quy hoach dong.</b> Tham lam
 *       chon tu dai nhat tai moi vi tri va khong rut lai duoc quyet dinh do, nen
 *       no tach sai o cau nhap nhang: {@code "nha hang xom"} thanh
 *       {@code [nha_hang][xom]}. Quy hoach dong cham diem ca cau roi chon phuong
 *       an tot nhat, cho ra {@code [nha][hang_xom]}. Xem
 *       {@link MaxWeightSegmenter}.</li>
 * </ol>
 *
 * <p>Hai thay doi nay phu thuoc nhau: quy hoach dong <b>vo nghia</b> tren tu dien
 * nhi phan (khong co trong so thi moi cach tach hop le deu dat diem bang nhau),
 * va tu dien lon <b>lam tham lam te hon</b> (cang nhieu tu ghep thi cang nhieu co
 * hoi chon nham tu dai). Chi doi mot trong hai deu khong an.
 *
 * <p><b>Bat bien phai giu:</b> tang chi muc va tang truy van phai dung cung mot
 * tokenizer VA cung mot tu dien — xem Javadoc {@link Tokenizer}. Vi vay tu dien la
 * mot the hien dung chung, bat bien sau khi nap ({@link #sharedDictionary()}).
 *
 * <p>Do phuc tap thoi gian: goi {@link #tokenize(String)} tren van ban co
 * n tieng la O(n * MAX_SYLLABLES) = O(n) vi MAX_SYLLABLES la hang so nho (4).
 * Do phuc tap khong gian: O(n) cho danh sach token + O(|tu dien|) co dinh.
 */
public class VietnameseTokenizer implements Tokenizer {

    /**
     * Regex duoc BIEN DICH SAN.
     *
     * <p>{@code String.replaceAll}/{@code String.split} goi
     * {@code Pattern.compile} MOI LAN duoc goi — mau regex bi phan tich va dich
     * lai tu dau. Ba mau duoi day nam tren duong nong nhat cua ca he thong:
     * {@link #stripDiacritics} chay cho MOI token cua MOI tai lieu (hon nam
     * trieu lan tren corpus 5.011 trang), va con duoc goi lai o khau boi sang
     * snippet cho tung tu cua tung ket qua. Bien dich mot lan roi dung lai la
     * cung mot ket qua voi it viec hon han.
     */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Mot token da tach, kem ca ban co dau va khong dau, va vi tri thu tu (dung cho phrase search). */
    public record Token(String term, String noDiacriticTerm, int position) {
    }

    /**
     * Tu dien dung chung, nap mot lan cho ca tien trinh.
     *
     * <p>Nap 185.000 muc mat vai tram milli-giay va chiem hang chuc MB. Trong ma
     * nguon hien co, {@code new VietnameseTokenizer()} xuat hien o <b>bay</b> cho
     * ({@code InvertedIndex}, {@code QueryParser}, {@code IndexPersistence},
     * {@code EvaluationHarness}...) — neu moi lan deu nap lai thi vua cham vua ton
     * bo nho gap bay lan cho cung mot du lieu bat bien.
     *
     * <p>Dung <b>lazy holder idiom</b>: JVM bao dam lop long chi duoc khoi tao mot
     * lan, dung luc lan dau ai do doc {@code INSTANCE}, va bao dam nay <b>khong
     * ton khoa</b> o cac lan doc sau. Viet {@code synchronized} tay hay
     * double-checked locking deu vua dai vua de sai hon.
     */
    private static final class DictionaryHolder {
        static final VietnameseWordDictionary INSTANCE = new VietnameseWordDictionary();
    }

    /** Tu dien dung chung; bat bien sau khi nap nen an toan cho nhieu luong. */
    public static VietnameseWordDictionary sharedDictionary() {
        return DictionaryHolder.INSTANCE;
    }

    private final Set<String> stopwords;
    private final VietnameseWordDictionary dictionary;
    private final MaxWeightSegmenter segmenter;

    public VietnameseTokenizer() {
        this(sharedDictionary());
    }

    /**
     * @param dictionary tu dien muon dung — tach ra lam tham so de
     *                   {@code EvaluationRunner} do duoc anh huong cua tu dien
     *                   va cua bang tham so trong so len chat luong tim kiem
     */
    public VietnameseTokenizer(VietnameseWordDictionary dictionary) {
        this.stopwords = loadResourceLines("/vietnamese-stopwords.txt");
        this.dictionary = dictionary;
        this.segmenter = new MaxWeightSegmenter(dictionary);
    }

    private static Set<String> loadResourceLines(String resourcePath) {
        Set<String> lines = new HashSet<>();
        try (InputStream is = VietnameseTokenizer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Khong tim thay resource: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = normalizeForLookup(line.trim());
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Loi doc resource: " + resourcePath, e);
        }
        return lines;
    }

    private static String normalizeForLookup(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.forLanguageTag("vi"));
    }

    /** Buoc 1-3: chuan hoa NFC, lowercase, bo dau cau, tach theo khoang trang. */
    private static String[] splitIntoSyllables(String text) {
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.forLanguageTag("vi"));
        String cleaned = WHITESPACE_RUN.matcher(NON_WORD.matcher(nfc).replaceAll(" "))
                .replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return new String[0];
        }
        return cleaned.split(" ");
    }

    /**
     * Sinh ban khong dau cua mot chuoi: xu ly rieng "đ"/"Đ" (khong phai
     * to hop combining-mark nen NFD khong tach duoc), roi NFD + bo
     * {@code \p{M}} (combining diacritical marks) cho cac nguyen am co dau con lai.
     */
    public static String stripDiacritics(String s) {
        String withoutDd = s.replace('đ', 'd').replace('Đ', 'D');
        String nfd = Normalizer.normalize(withoutDd, Normalizer.Form.NFD);

        // Truoc day dong nay la `nfd.replaceAll("\\p{M}", "")`. Ham nay chay cho
        // MOI token cua MOI tai lieu luc lap chi muc, roi lai chay cho tung tu
        // cua tung ket qua luc boi sang snippet — tren corpus 5.011 trang la
        // hang trieu lan goi. Moi lan, `replaceAll` bien dich lai mau regex,
        // dung Matcher va cap phat chuoi ket qua. Mot luot quet ky tu lam dung
        // viec do voi it cong hon, va truong hop pho bien nhat (chuoi von khong
        // co dau: chu so, tu tieng Anh, tu Viet khong dau) khong cap phat gi.
        int firstMark = indexOfMark(nfd);
        if (firstMark < 0) {
            return nfd;
        }
        StringBuilder stripped = new StringBuilder(nfd.length());
        stripped.append(nfd, 0, firstMark);
        for (int i = firstMark + 1; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (!isCombiningMark(c)) {
                stripped.append(c);
            }
        }
        return stripped.toString();
    }

    /** Vi tri dau tien cua mot dau to hop, hoac -1 neu chuoi khong co dau nao. */
    private static int indexOfMark(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCombiningMark(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Dung mot lop ky tu voi {@code \p{M}} cua regex: ba loai dau to hop Unicode. */
    private static boolean isCombiningMark(char c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /**
     * Tach mot doan van ban thanh danh sach {@link Token}, da loai
     * stopword va ghep tu ghep theo Longest Matching.
     */
    @Override
    public String name() {
        return "VietnameseTokenizer(MaxWeightDP, maxSyllables="
                + VietnameseWordDictionary.MAX_SYLLABLES
                + ", dict=" + dictionary.wordCount()
                + " (" + dictionary.compoundCount() + " tu ghep)"
                + ", stopwords=" + stopwords.size() + ")";
    }

    @Override
    public List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] syllables = splitIntoSyllables(text);
        int[] boundaries = segmenter.segment(syllables);

        int position = 0;
        for (int k = 0; k + 1 < boundaries.length; k++) {
            int from = boundaries[k];
            int to = boundaries[k + 1];

            String term;
            boolean isStopword;
            if (to - from > 1) {
                term = joinWithUnderscore(syllables, from, to);
                // Tu ghep khong bao gio bi coi la stopword: "co the" hay "cho nen"
                // la tu that mang nghia, du tung tieng deu nam trong danh sach
                // stopword. Bo chung se lam hong ca truy van chua cum tu do.
                isStopword = false;
            } else {
                term = syllables[from];
                isStopword = stopwords.contains(term);
            }

            if (!isStopword) {
                tokens.add(new Token(term, stripDiacritics(term), position));
                position++;
            }
        }
        return tokens;
    }

    /** Noi {@code syllables[from..to)} bang dau "_" — mot lan cap phat duy nhat. */
    private static String joinWithUnderscore(String[] syllables, int from, int to) {
        int length = to - from - 1; // so dau "_"
        for (int i = from; i < to; i++) {
            length += syllables[i].length();
        }
        StringBuilder joined = new StringBuilder(length);
        joined.append(syllables[from]);
        for (int i = from + 1; i < to; i++) {
            joined.append('_').append(syllables[i]);
        }
        return joined.toString();
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        VietnameseTokenizer tokenizer = new VietnameseTokenizer();
        System.out.println(tokenizer.name());
        System.out.println();

        String text = "Trình duyệt web và công cụ tìm kiếm là các sản phẩm công nghệ rất quan trọng của một công ty.";
        List<Token> tokens = tokenizer.tokenize(text);
        System.out.println("Van ban: " + text);
        for (Token t : tokens) {
            System.out.println("  [" + t.position() + "] " + t.term() + "  (khong dau: " + t.noDiacriticTerm() + ")");
        }

        // Cac cau NHAP NHANG: ca hai cach tach deu hop le ve tu dien, nen Longest
        // Matching phai doan — va doan sai. Day la phan dang doc nhat cua demo.
        System.out.println("\n--- Cac ca nhap nhang ---");
        String[] ambiguous = {
                "nhà hàng xóm",
                "ông già đi nhanh quá",
                "học sinh học sinh học",
                "tôi đi mua máy tính xách tay mới",
                "cải cách ruộng đất",
        };
        for (String sentence : ambiguous) {
            StringBuilder line = new StringBuilder();
            for (Token t : tokenizer.tokenize(sentence)) {
                line.append('[').append(t.term()).append(']');
            }
            System.out.printf("  %-34s -> %s%n", sentence, line);
        }
    }
}
