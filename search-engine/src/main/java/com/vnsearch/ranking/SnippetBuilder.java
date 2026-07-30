package com.vnsearch.ranking;

/**
 * Sinh doan trich (snippet) co boi sang tu khoa, bang <b>cua so truot</b>.
 *
 * <p>Tach rieng khoi {@code ResultRanker} vi do la mot trach nhiem hoan toan
 * khac: xep hang lam viec voi DIEM SO, con sinh snippet lam viec voi VAN BAN.
 * Tach ra cho phep test rieng, va cho phep thay chien luoc sinh snippet (mot
 * cua so, nhieu doan roi rac, tom tat bang mo hinh) ma khong dung toi xep hang.
 *
 * <p><b>Cua so truot.</b> Bai toan: trong tai lieu {@code n} tu, tim cua so
 * {@code w} tu lien tiep chua nhieu tu khoa nhat.
 * <pre>
 *   Ngay tho     : moi vi tri dem lai tu dau -> O(n*w) = 1043*25 = 26.075
 *   Cua so truot : moi buoc chi 2 phep cap nhat -> O(n) = 1.068
 * </pre>
 * Nhanh hon dung {@code w} lan. Bat bien vong lap: {@code currentMatches} luon
 * bang so tu khop trong {@code isMatch[start .. start+w-1]}; khi cua so dich
 * mot buoc, chi co MOT phan tu roi khoi ben trai va MOT phan tu vao ben phai.
 */
public final class SnippetBuilder {

    /** So tu cua doan trich. */
    public static final int DEFAULT_WINDOW_SIZE = 25;

    private final int windowSize;

    public SnippetBuilder() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public SnippetBuilder(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize phai > 0, nhan duoc: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    /**
     * Sinh snippet HTML voi tu khoa duoc boc trong {@code <mark>}.
     *
     * @param bodyText  noi dung tai lieu
     * @param syllables tap tieng truy van (quyet dinh khop chinh xac hay long)
     */
    public String build(String bodyText, QuerySyllables syllables) {
        if (bodyText == null || bodyText.isBlank()) {
            return "";
        }
        String[] words = bodyText.trim().split("\\s+");
        if (words.length == 0) {
            return "";
        }

        boolean[] isMatch = new boolean[words.length];
        for (int i = 0; i < words.length; i++) {
            // Truyen tu con NGUYEN DAU vao matches(): chinh no quyet dinh khop
            // chinh xac hay khop long; bo dau o day se lam hong quy tac do.
            isMatch[i] = syllables.matches(QuerySyllables.stripPunctuation(words[i]));
        }

        int window = Math.min(windowSize, words.length);
        int bestStart = findBestWindow(isMatch, window);
        return render(words, isMatch, bestStart, window);
    }

    /** Cua so truot: O(n) thay vi O(n*w). */
    private int findBestWindow(boolean[] isMatch, int window) {
        int currentMatches = 0;
        for (int i = 0; i < window; i++) {
            if (isMatch[i]) {
                currentMatches++;
            }
        }
        int bestStart = 0;
        int bestMatches = currentMatches;

        for (int start = 1; start + window <= isMatch.length; start++) {
            if (isMatch[start - 1]) {
                currentMatches--; // phan tu roi khoi ben TRAI
            }
            if (isMatch[start + window - 1]) {
                currentMatches++; // phan tu vao ben PHAI
            }
            if (currentMatches > bestMatches) {
                bestMatches = currentMatches;
                bestStart = start;
            }
        }
        return bestStart;
    }

    private String render(String[] words, boolean[] isMatch, int bestStart, int window) {
        StringBuilder snippet = new StringBuilder();
        for (int i = bestStart; i < bestStart + window; i++) {
            if (i > bestStart) {
                snippet.append(' ');
            }
            if (isMatch[i]) {
                snippet.append("<mark>").append(escapeHtml(words[i])).append("</mark>");
            } else {
                snippet.append(escapeHtml(words[i]));
            }
        }
        // Chi them "..." khi cua so THUC SU khong o dau/cuoi tai lieu.
        if (bestStart > 0) {
            snippet.insert(0, "... ");
        }
        if (bestStart + window < words.length) {
            snippet.append(" ...");
        }
        return snippet.toString();
    }

    /**
     * Thoat ky tu HTML truoc khi ghep vao chuoi co the.
     *
     * <p>Truoc day noi dung tai lieu duoc chen THANG vao chuoi HTML. HtmlExtractor
     * da loai the {@code <script>} khoi DOM nen ma script khong lot vao
     * {@code bodyText}, nhung mot bai viet co VAN BAN
     * {@code <script>alert(1)</script>} (vi du bai ve XSS) thi van lot — va
     * client render bang {@code innerHTML} se thuc thi no. Day la mot lo hong
     * XSS phan chieu that su, khong phai gia dinh.
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
