package com.vnsearch.ranking;

import com.vnsearch.index.VietnameseTokenizer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tap tieng cua truy van, giu CA HAI dang de so khop cho dung — dung cho boi
 * sang snippet va cho diem khop tieu de.
 *
 * <p><b>Loi da sua.</b> Truoc day moi tieng deu bi bo dau truoc khi so khop,
 * khien snippet boi sang nham: truy van {@code ngân hàng} lam sang ca chu
 * {@code ngàn} trong "cắt giảm cả ngàn nhân sự", vi ca {@code ngân} lan
 * {@code ngàn} deu bo dau thanh {@code ngan}.
 *
 * <p><b>Nguyen nhan goc.</b> Bo dau la mot anh xa NHIEU-MOT:
 * <pre>
 *   ngân -> ngan
 *   ngàn -> ngan
 *   ngắn -> ngan
 * </pre>
 * So khop tren ANH cua anh xa nay thi mat kha nang phan biet cac nghich anh.
 *
 * <p><b>Vi sao van can bo dau o khau TRA CUU.</b> O do ta KHONG biet nguoi
 * dung se go kieu nao, nen phai index ca hai dang de bat duoc ca hai. Bo dau
 * la CAN THIET o tra cuu — nhung o khau <i>hien thi</i> thi thua va gay sai,
 * vi luc nay da biet chinh xac nguoi dung go gi.
 *
 * <p><b>Quy tac moi:</b>
 * <table border="1">
 *   <tr><th>Nguoi dung go</th><th>Che do khop</th><th>Vi du</th></tr>
 *   <tr><td>{@code ngân} (CO dau)</td><td>Chi khop chinh xac</td>
 *       <td>chi sang {@code ngân}</td></tr>
 *   <tr><td>{@code ngan} (KHONG dau)</td><td>Khop long (bo dau)</td>
 *       <td>sang ca {@code ngân}, {@code ngàn}</td></tr>
 * </table>
 *
 * <p>Cach kiem tra "tieng nay co dau khong" dung <b>diem bat dong</b> cua phep
 * bo dau: {@code stripDiacritics(s) == s} khi va chi khi {@code s} khong co dau.
 *
 * @param exact tieng phai khop CHINH XAC
 * @param loose tieng cho phep khop long theo dang bo dau
 */
public record QuerySyllables(Set<String> exact, Set<String> loose) {

    /** Tach tap term truy van (co the la tu ghep noi bang "_") thanh cac tieng. */
    public static QuerySyllables from(Set<String> terms) {
        Set<String> exact = new HashSet<>();
        Set<String> loose = new HashSet<>();
        for (String term : terms) {
            for (String syllable : term.split("_")) {
                String lower = syllable.toLowerCase(Locale.ROOT);
                if (lower.isEmpty()) {
                    continue;
                }
                exact.add(lower);
                // Chi mo khop long khi CHINH tieng trong truy van khong co dau.
                if (VietnameseTokenizer.stripDiacritics(lower).equalsIgnoreCase(lower)) {
                    loose.add(lower);
                }
            }
        }
        return new QuerySyllables(exact, loose);
    }

    /** Tu nay co khop truy van khong (theo quy tac chinh xac / long o tren). */
    public boolean matches(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (exact.contains(lower)) {
            return true;
        }
        return !loose.isEmpty()
                && loose.contains(VietnameseTokenizer.stripDiacritics(lower).toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return exact.isEmpty();
    }

    /**
     * Ty le tieng cua tieu de khop truy van, KEP trong {@code [0, 1]}.
     *
     * <p>Phai kep vi tu so dem SO LAN xuat hien con mau so la so tieng PHAN
     * BIET cua truy van: mot tieu de nhoi tu khoa ("Máy tính và máy tính bảng"
     * voi truy van "máy tính") cho ty so {@code 4/2 = 2}. Khong kep thi tieu de
     * nhoi tu khoa duoc thuong tuy y.
     */
    public double titleMatchRatio(String title) {
        if (title == null || title.isBlank() || exact.isEmpty()) {
            return 0.0;
        }
        String[] words = title.toLowerCase(Locale.ROOT).split("\\s+");
        int matched = 0;
        for (String word : words) {
            if (matches(stripPunctuation(word))) {
                matched++;
            }
        }
        return Math.min(1.0, (double) matched / exact.size());
    }

    /** Bo dau cau, giu chu va so (dung lop Unicode nen khong xoa dau tieng Viet). */
    public static String stripPunctuation(String word) {
        return word == null ? "" : word.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
