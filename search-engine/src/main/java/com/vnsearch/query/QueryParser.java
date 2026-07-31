package com.vnsearch.query;

import com.vnsearch.index.Tokenizer;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.query.ast.AndNode;
import com.vnsearch.query.ast.NotNode;
import com.vnsearch.query.ast.OrNode;
import com.vnsearch.query.ast.PhraseNode;
import com.vnsearch.query.ast.QueryNode;
import com.vnsearch.query.ast.TermNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phan tich cau truy van cua nguoi dung thanh cac thanh phan co cau truc, roi
 * dung <b>cay bieu thuc</b> ({@link QueryNode} — Composite pattern).
 *
 * <p>Cu phap ho tro:
 * <table border="1">
 *   <tr><th>Cu phap</th><th>Y nghia</th></tr>
 *   <tr><td>{@code máy tính}</td><td>AND ngam dinh giua cac term</td></tr>
 *   <tr><td>{@code "trình duyệt web"}</td><td>Cum tu phai xuat hien LIEN TIEP</td></tr>
 *   <tr><td>{@code -giá}</td><td>Loai tru mot tieng</td></tr>
 *   <tr><td>{@code laptop OR máy tính}</td><td><b>Hop</b> — MOI</td></tr>
 *   <tr><td>{@code site:vnexpress.net}</td><td><b>Gioi han domain</b> — MOI</td></tr>
 * </table>
 *
 * <p><b>BAT BIEN QUYET DINH.</b> Truy van phai duoc tokenize bang CHINH
 * {@link Tokenizer} da dung luc index. Neu luc index tao ra {@code máy_tính}
 * ma luc truy van tao ra {@code máy} + {@code tính} thi <b>khong bao gio
 * khop</b> — va loi nay IM LANG, khong nem ngoai le nao, chi la ket qua rong
 * mot cach kho hieu. Vi vay lop nay nhan tokenizer qua constructor thay vi tu tao.
 *
 * <p>Do phuc tap thoi gian: O(L) voi L la do dai chuoi truy van.
 */
public class QueryParser {

    private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final String OR_KEYWORD = "OR";
    private static final String SITE_PREFIX = "site:";

    private final Tokenizer tokenizer;

    public QueryParser(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public QueryParser() {
        this(new VietnameseTokenizer());
    }

    /**
     * Truy van da phan tich.
     *
     * @param mustTerms     term don, AND ngam dinh voi nhau
     * @param phrases       cum tu trong ngoac kep, moi cum phai xuat hien LIEN TIEP
     * @param excludedTerms term sau dau {@code -}, tai lieu chua chung bi loai
     * @param orGroups      cac nhom lua chon: MOI nhom la mot OR, cac nhom AND voi nhau
     * @param siteFilter    host phai khop (toan tu {@code site:}), {@code null} neu khong co
     */
    public record ParsedQuery(List<String> mustTerms, List<List<String>> phrases,
                               List<String> excludedTerms, List<List<String>> orGroups,
                               String siteFilter) {

        /** Constructor rut gon giu tuong thich voi ma cu (khong OR, khong site:). */
        public ParsedQuery(List<String> mustTerms, List<List<String>> phrases, List<String> excludedTerms) {
            this(mustTerms, phrases, excludedTerms, List.of(), null);
        }

        public boolean isEmpty() {
            return mustTerms.isEmpty() && phrases.isEmpty() && orGroups.isEmpty();
        }
    }

    public ParsedQuery parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new ParsedQuery(List.of(), List.of(), List.of());
        }

        // --- Buoc 1: cat cac cum trong ngoac kep RA KHOI chuoi ---
        // Giu lai phan NGOAI ngoac vao `remaining`; neu khong, cac tieng cua
        // cum se VUA la phrase VUA la mustTerm, bi dem hai lan trong
        // queryTermFrequency va lam sai trong so truy van.
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

        // --- Buoc 2: tach tu khoa OR, site:, va toan tu loai tru ---
        List<String> mustRaw = new ArrayList<>();
        List<String> excludedRaw = new ArrayList<>();
        List<List<String>> orGroupsRaw = new ArrayList<>();
        String siteFilter = null;

        String[] words = remaining.toString().trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) {
                continue;
            }

            if (word.toLowerCase(Locale.ROOT).startsWith(SITE_PREFIX)) {
                String host = word.substring(SITE_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
                if (!host.isEmpty()) {
                    siteFilter = host;
                }
                continue;
            }

            // "A OR B": tu khoa OR noi tu TRUOC voi tu SAU thanh mot nhom lua chon.
            if (OR_KEYWORD.equals(word) && !mustRaw.isEmpty() && i + 1 < words.length) {
                String left = mustRaw.remove(mustRaw.size() - 1);
                List<String> group = new ArrayList<>();
                group.add(left);
                // Gom day OR lien tiep: "a OR b OR c" thanh MOT nhom 3 phan tu.
                while (i + 1 < words.length) {
                    group.add(words[i + 1]);
                    i++;
                    if (i + 1 < words.length && OR_KEYWORD.equals(words[i + 1])) {
                        i++; // bo qua tu khoa OR tiep theo
                    } else {
                        break;
                    }
                }
                orGroupsRaw.add(group);
                continue;
            }

            if (word.startsWith("-") && word.length() > 1) {
                excludedRaw.add(word.substring(1));
            } else if (!word.equals("-")) {
                mustRaw.add(word);
            }
        }

        // --- Buoc 3: tokenize tung phan ---
        // Cum trong ngoac kep duoc tokenize RIENG (moi cum la mot don vi doc
        // lap); phan ngoai ngoac duoc noi lai roi tokenize CHUNG de Longest
        // Matching co du ngu canh ghep tu ghep.
        List<String> mustTerms = tokenizeToTerms(String.join(" ", mustRaw));
        List<String> excludedTerms = tokenizeToTerms(String.join(" ", excludedRaw));

        List<List<String>> phrases = new ArrayList<>();
        for (String phraseRaw : phrasesRaw) {
            List<String> phraseTerms = tokenizeToTerms(phraseRaw);
            if (!phraseTerms.isEmpty()) {
                phrases.add(phraseTerms);
            }
        }

        List<List<String>> orGroups = new ArrayList<>();
        for (List<String> groupRaw : orGroupsRaw) {
            List<String> alternatives = new ArrayList<>();
            for (String alternative : groupRaw) {
                alternatives.addAll(tokenizeToTerms(alternative));
            }
            if (alternatives.size() > 1) {
                orGroups.add(alternatives);
            } else if (alternatives.size() == 1) {
                mustTerms = new ArrayList<>(mustTerms);
                mustTerms.add(alternatives.get(0)); // OR mot ve thi thanh AND
            }
        }

        return new ParsedQuery(mustTerms, phrases, excludedTerms, orGroups, siteFilter);
    }

    /**
     * Dung <b>cay bieu thuc</b> tu truy van da phan tich (Composite pattern).
     *
     * <p>Cau truc cay:
     * <pre>
     *   AndNode
     *     ├── TermNode   (moi mustTerm)
     *     ├── PhraseNode (moi cum trong ngoac kep)
     *     ├── OrNode     (moi nhom "a OR b")
     *     └── NotNode    (moi term bi loai tru)
     * </pre>
     *
     * @return {@code null} neu truy van khong co menh de khang dinh nao
     */
    public QueryNode buildAst(ParsedQuery parsed) {
        List<QueryNode> children = new ArrayList<>();

        for (String term : parsed.mustTerms()) {
            children.add(new TermNode(term));
        }
        for (List<String> phrase : parsed.phrases()) {
            children.add(new PhraseNode(phrase));
        }
        for (List<String> group : parsed.orGroups()) {
            List<QueryNode> alternatives = new ArrayList<>(group.size());
            for (String alternative : group) {
                alternatives.add(new TermNode(alternative));
            }
            children.add(new OrNode(alternatives));
        }
        if (children.isEmpty()) {
            return null; // khong co menh de khang dinh -> khong truy hoi duoc
        }
        for (String excluded : parsed.excludedTerms()) {
            children.add(new NotNode(new TermNode(excluded)));
        }
        return new AndNode(children);
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
        String[] queries = {
                "\"trình duyệt web\" máy tính -giá",
                "laptop OR máy tính giá rẻ",
                "công nghệ site:vnexpress.net"
        };
        for (String query : queries) {
            ParsedQuery parsed = parser.parse(query);
            System.out.println("Query: " + query);
            System.out.println("  mustTerms     = " + parsed.mustTerms());
            System.out.println("  phrases       = " + parsed.phrases());
            System.out.println("  excludedTerms = " + parsed.excludedTerms());
            System.out.println("  orGroups      = " + parsed.orGroups());
            System.out.println("  siteFilter    = " + parsed.siteFilter());
            QueryNode ast = parser.buildAst(parsed);
            System.out.println("  AST           = " + (ast == null ? "(rong)" : ast.describe()));
            System.out.println();
        }
    }
}
