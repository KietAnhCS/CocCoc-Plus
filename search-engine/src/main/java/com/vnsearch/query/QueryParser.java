package com.vnsearch.query;

import com.vnsearch.index.VietnameseTokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phan tich cau truy van cua nguoi dung thanh 3 thanh phan:
 * <ul>
 *   <li>{@code mustTerms}: cac tu don, ngam dinh AND voi nhau (phai xuat
 *       hien trong tai lieu).</li>
 *   <li>{@code phrases}: cac cum tu trong dau ngoac kep ("phrase search"),
 *       moi cum la mot danh sach term PHAI xuat hien LIEN TIEP dung thu
 *       tu trong tai lieu.</li>
 *   <li>{@code excludedTerms}: tu dung sau dau {@code -} — tai lieu chua
 *       tu nay se bi loai khoi ket qua.</li>
 * </ul>
 *
 * <p><b>Gioi han da biet:</b> dau {@code -} chi loai tru MOT tieng ngay
 * sau no (giong toan tu {@code -word} cua Google), khong tu dong loai tru
 * ca mot tu ghep nhieu tieng. Vi du {@code -quảng cáo} chi loai tru
 * "quảng", con "cáo" van la mustTerm rieng. De loai tru ca cum tu ghep,
 * nguoi dung can viet {@code -"quảng cáo"} (chua ho tro dau {@code -}
 * truoc phrase trong ban PHASE 4 nay).
 *
 * <p>Dung CHINH {@link VietnameseTokenizer} de tokenize moi phan (giong
 * het tokenizer dung luc index) — dam bao term sinh ra tu query khop
 * chinh xac voi khoa trong {@link com.vnsearch.index.InvertedIndex}
 * (vi du tu ghep "máy tính" trong query cung duoc gop thanh "máy_tính"
 * y het luc index).
 *
 * <p>Do phuc tap thoi gian: O(L) voi L la do dai chuoi truy van (mot lan
 * quet regex tim cum tu, mot lan tokenize).
 */
public class QueryParser {

    private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]*)\"");

    private final VietnameseTokenizer tokenizer;

    public QueryParser(VietnameseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public QueryParser() {
        this(new VietnameseTokenizer());
    }

    public record ParsedQuery(List<String> mustTerms, List<List<String>> phrases, List<String> excludedTerms) {
    }

    public ParsedQuery parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new ParsedQuery(List.of(), List.of(), List.of());
        }

        List<String> phrasesRaw = new ArrayList<>();
        Matcher matcher = PHRASE_PATTERN.matcher(rawQuery);
        StringBuilder remaining = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            remaining.append(rawQuery, lastEnd, matcher.start());
            if (!matcher.group(1).isBlank()) {
                phrasesRaw.add(matcher.group(1));
            }
            lastEnd = matcher.end();
        }
        remaining.append(rawQuery.substring(lastEnd));

        List<String> mustRaw = new ArrayList<>();
        List<String> excludedRaw = new ArrayList<>();
        for (String word : remaining.toString().trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.startsWith("-") && word.length() > 1) {
                excludedRaw.add(word.substring(1));
            } else if (!word.equals("-")) {
                mustRaw.add(word);
            }
        }

        List<String> mustTerms = tokenizeToTerms(String.join(" ", mustRaw));
        List<String> excludedTerms = tokenizeToTerms(String.join(" ", excludedRaw));
        List<List<String>> phrases = new ArrayList<>();
        for (String phraseRaw : phrasesRaw) {
            List<String> phraseTerms = tokenizeToTerms(phraseRaw);
            if (!phraseTerms.isEmpty()) {
                phrases.add(phraseTerms);
            }
        }

        return new ParsedQuery(mustTerms, phrases, excludedTerms);
    }

    private List<String> tokenizeToTerms(String text) {
        List<String> terms = new ArrayList<>();
        for (VietnameseTokenizer.Token token : tokenizer.tokenize(text)) {
            terms.add(token.term());
        }
        return terms;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        QueryParser parser = new QueryParser();
        String query = "\"trình duyệt web\" máy tính -giá";
        ParsedQuery parsed = parser.parse(query);
        System.out.println("Query: " + query);
        System.out.println("  mustTerms   = " + parsed.mustTerms());
        System.out.println("  phrases     = " + parsed.phrases());
        System.out.println("  excludedTerms = " + parsed.excludedTerms());
    }
}
