package com.vnsearch.index;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 *   <li>Ghep tu ghep bang thuat toan <b>Longest Matching</b>: tai moi vi
 *       tri, thu ghep toi da {@code MAX_COMPOUND_LENGTH} tieng lien tiep,
 *       giam dan do dai, kiem tra co trong tu dien
 *       {@code vietnamese-bigrams.txt} khong; neu co thi gop thanh 1 token
 *       (noi bang dau "_"), neu khong thi lui ve token 1 tieng.</li>
 *   <li>Loai stopword (chi ap dung cho token 1 tieng, doc tu
 *       {@code vietnamese-stopwords.txt}).</li>
 *   <li>Sinh them ban khong dau cua moi token (Normalizer NFD + regex bo
 *       ky tu combining mark {@code \p{M}}, rieng "đ" xu ly thu cong vi no
 *       la 1 ky tu Latin doc lap, khong phai to hop base+dau) de ho tro
 *       tim khong dau ("may tinh" van tim thay "máy tính").</li>
 * </ol>
 *
 * <p>Do phuc tap thoi gian: goi {@link #tokenize(String)} tren van ban co
 * n tieng la O(n * MAX_COMPOUND_LENGTH) = O(n) vi MAX_COMPOUND_LENGTH la
 * hang so nho (4). Do phuc tap khong gian: O(n) cho danh sach token +
 * O(|tu dien|) co dinh cho stopword/bigram set.
 */
public class VietnameseTokenizer {

    private static final int MAX_COMPOUND_LENGTH = 4;

    /** Mot token da tach, kem ca ban co dau va khong dau, va vi tri thu tu (dung cho phrase search). */
    public record Token(String term, String noDiacriticTerm, int position) {
    }

    private final Set<String> stopwords;
    private final Set<String> bigramDictionary;

    public VietnameseTokenizer() {
        this.stopwords = loadResourceLines("/vietnamese-stopwords.txt");
        this.bigramDictionary = loadResourceLines("/vietnamese-bigrams.txt");
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
        String cleaned = nfc.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
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
        return nfd.replaceAll("\\p{M}", "");
    }

    /**
     * Tach mot doan van ban thanh danh sach {@link Token}, da loai
     * stopword va ghep tu ghep theo Longest Matching.
     */
    public List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] syllables = splitIntoSyllables(text);
        int i = 0;
        int position = 0;
        while (i < syllables.length) {
            int matchedLen = 1;
            int maxLen = Math.min(MAX_COMPOUND_LENGTH, syllables.length - i);
            for (int len = maxLen; len >= 2; len--) {
                String candidate = String.join(" ", Arrays.copyOfRange(syllables, i, i + len));
                if (bigramDictionary.contains(candidate)) {
                    matchedLen = len;
                    break;
                }
            }

            String term;
            boolean isStopword;
            if (matchedLen > 1) {
                term = String.join("_", Arrays.copyOfRange(syllables, i, i + matchedLen));
                isStopword = false;
            } else {
                term = syllables[i];
                isStopword = stopwords.contains(term);
            }

            if (!isStopword) {
                tokens.add(new Token(term, stripDiacritics(term), position));
                position++;
            }
            i += matchedLen;
        }
        return tokens;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        VietnameseTokenizer tokenizer = new VietnameseTokenizer();
        String text = "Trình duyệt web và công cụ tìm kiếm là các sản phẩm công nghệ rất quan trọng của một công ty.";
        List<Token> tokens = tokenizer.tokenize(text);
        System.out.println("Van ban: " + text);
        for (Token t : tokens) {
            System.out.println("  [" + t.position() + "] " + t.term() + "  (khong dau: " + t.noDiacriticTerm() + ")");
        }
    }
}
