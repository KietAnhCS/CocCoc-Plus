package com.vnsearch.ranking;

import com.vnsearch.datastructure.MinHeap;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.model.WebDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tong hop diem cuoi cung tu TF-IDF, PageRank va do khop tieu de, roi lay
 * top-N ket qua kem sinh snippet co highlight.
 *
 * <p>{@code finalScore = alpha*tfidfScore + beta*pageRankScore + gamma*titleMatchBonus},
 * mac dinh alpha=0.6, beta=0.3, gamma=0.1 (co the chinh qua constructor —
 * o tang controller se doc tu {@code application.properties}).
 *
 * <p>Dung {@link MinHeap#topK} de lay top-N ma KHONG sort toan bo danh
 * sach ung vien — quan trong khi so luong ung vien (sau khi loc bang
 * PostingListMerger) van con lon.
 *
 * <p>Sinh snippet bang cua so truot (sliding window) tren cac TIENG
 * (khong phai token da ghep) cua bodyText: voi moi vi tri cua so kich
 * thuoc co dinh, dem so tieng khop tu khoa truy van (so sanh khong phan
 * biet hoa/thuong va khong dau), chon cua so co so khop nhieu nhat. Tu
 * khoa ghep (co dau "_") duoc tach lai thanh cac tieng rieng le de so
 * khop voi van ban tho (chua ghep) trong bodyText.
 */
public class ResultRanker {

    private static final int SNIPPET_WINDOW_SIZE = 25;

    private final double alpha;
    private final double beta;
    private final double gamma;

    public ResultRanker() {
        this(0.6, 0.3, 0.1);
    }

    public ResultRanker(double alpha, double beta, double gamma) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    public record RankedResult(WebDocument document, double finalScore, double tfidfScore,
                                double pageRankScore, String snippet) {
    }

    /**
     * Xep hang cac tai lieu ung vien va tra ve top {@code topN}.
     *
     * @param candidateDocIds   danh sach docId ung vien (da qua PostingListMerger)
     * @param queryTermFrequency tan suat moi term trong truy van (dung cho TfIdfScorer)
     * @param index             inverted index
     * @param scorer            TF-IDF scorer
     * @param pageRankScores    diem PageRank da tinh san cho toan corpus
     * @param topN              so ket qua muon lay
     */
    public List<RankedResult> rank(List<Integer> candidateDocIds,
                                    Map<String, Integer> queryTermFrequency,
                                    InvertedIndex index,
                                    RelevanceScorer scorer,
                                    Map<Integer, Double> pageRankScores,
                                    int topN) {
        QuerySyllables queryKeywordSyllables = extractSyllables(queryTermFrequency.keySet());

        // BUOC 1 - chi CHAM DIEM moi ung vien, chua sinh snippet.
        //
        // Sinh snippet la thao tac dat nhat trong ca ham: no phai tach TOAN BO
        // bodyText (trung binh hon 1.000 token moi tai lieu) roi truot cua so
        // qua tung tu. Truoc day buoc nay chay cho MOI ung vien roi moi cat
        // top-N, nghia la voi 500 ung vien thi 490 snippet bi vut di ngay sau
        // khi tao ra - chi phi O(so ung vien * do dai tai lieu) hoan toan lang phi.
        // Do do tach lam hai buoc: chi diem la du de xep hang.
        List<ScoredCandidate> scored = new ArrayList<>(candidateDocIds.size());
        for (int docId : candidateDocIds) {
            WebDocument doc = index.getDocument(docId);
            if (doc == null) {
                continue;
            }
            double relevance = scorer.score(queryTermFrequency, docId, index);
            double pageRank = pageRankScores.getOrDefault(docId, 0.0);
            double titleBonus = titleMatchBonus(queryKeywordSyllables, doc.getTitle());
            double finalScore = alpha * relevance + beta * pageRank + gamma * titleBonus;
            scored.add(new ScoredCandidate(doc, finalScore, relevance, pageRank));
        }

        // BUOC 2 - lay top-N bang MinHeap, O(n log topN).
        List<ScoredCandidate> top =
                MinHeap.topK(scored, topN, Comparator.comparingDouble(ScoredCandidate::finalScore));

        // BUOC 3 - chi sinh snippet cho dung nhung tai lieu thuc su duoc tra ve.
        List<RankedResult> results = new ArrayList<>(top.size());
        for (ScoredCandidate candidate : top) {
            results.add(new RankedResult(
                    candidate.document(), candidate.finalScore(), candidate.relevanceScore(),
                    candidate.pageRankScore(),
                    buildSnippet(candidate.document().getBodyText(), queryKeywordSyllables)));
        }
        return results;
    }

    /** Ung vien da cham diem nhung CHUA sinh snippet (xem giai thich trong {@link #rank}). */
    private record ScoredCandidate(WebDocument document, double finalScore,
                                    double relevanceScore, double pageRankScore) {
    }

    /**
     * Tap tieng cua truy van, giu CA hai dang de so khop cho dung.
     *
     * <p>Truoc day moi tieng deu bi bo dau truoc khi so khop, khien snippet
     * boi sang nham: truy van "ngan hang" lam sang ca chu "ngan" trong
     * "cat giam ca ngan nhan su", vi ca "ngan" lan "ngan" deu bo dau thanh
     * "ngan". Bo dau la CAN THIET o khau tra cuu chi muc (de go "may tinh"
     * tim duoc "may tinh"), nhung o khau boi sang thi thua va gay sai, vi
     * luc nay da biet chinh xac nguoi dung go gi.
     *
     * <p>Quy tac moi: neu tieng trong truy van CO dau thi chi khop chinh xac
     * theo dang co dau; neu nguoi dung von go KHONG dau thi moi cho phep
     * khop long theo dang bo dau. Nho vay ca hai kieu go deu duoc phuc vu ma
     * khong danh doi do chinh xac.
     */
    private record QuerySyllables(Set<String> exact, Set<String> loose) {

        boolean matches(String word) {
            String lower = word.toLowerCase();
            if (exact.contains(lower)) {
                return true;
            }
            return !loose.isEmpty()
                    && loose.contains(VietnameseTokenizer.stripDiacritics(lower).toLowerCase());
        }
    }

    private QuerySyllables extractSyllables(Set<String> terms) {
        Set<String> exact = new HashSet<>();
        Set<String> loose = new HashSet<>();
        for (String term : terms) {
            for (String syllable : term.split("_")) {
                String lower = syllable.toLowerCase();
                exact.add(lower);
                // Chi mo khop long khi CHINH tieng trong truy van khong co dau.
                if (VietnameseTokenizer.stripDiacritics(lower).equalsIgnoreCase(lower)) {
                    loose.add(lower);
                }
            }
        }
        return new QuerySyllables(exact, loose);
    }

    private double titleMatchBonus(QuerySyllables queryKeywordSyllables, String title) {
        if (title == null || title.isBlank() || queryKeywordSyllables.exact().isEmpty()) {
            return 0.0;
        }
        String[] titleWords = title.toLowerCase().split("\\s+");
        int matched = 0;
        for (String word : titleWords) {
            if (queryKeywordSyllables.matches(stripPunctuation(word))) {
                matched++;
            }
        }
        return Math.min(1.0, (double) matched / queryKeywordSyllables.exact().size());
    }

    private String buildSnippet(String bodyText, QuerySyllables queryKeywordSyllables) {
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
            // chinh xac hay khop long, bo dau o day se lam hong quy tac do.
            isMatch[i] = queryKeywordSyllables.matches(stripPunctuation(words[i]));
        }

        int windowSize = Math.min(SNIPPET_WINDOW_SIZE, words.length);

        // Cua so truot: tinh so khop cua cua so dau tien, sau do moi buoc chi
        // O(1) cap nhat (tru phan tu ra khoi cua so, cong phan tu moi vao) ->
        // tong the O(n) thay vi O(n * windowSize) neu dem lai tu dau moi lan.
        int currentMatches = 0;
        for (int i = 0; i < windowSize; i++) {
            if (isMatch[i]) {
                currentMatches++;
            }
        }
        int bestStart = 0;
        int bestMatches = currentMatches;
        for (int start = 1; start + windowSize <= words.length; start++) {
            if (isMatch[start - 1]) {
                currentMatches--;
            }
            if (isMatch[start + windowSize - 1]) {
                currentMatches++;
            }
            if (currentMatches > bestMatches) {
                bestMatches = currentMatches;
                bestStart = start;
            }
        }

        StringBuilder snippet = new StringBuilder();
        for (int i = bestStart; i < bestStart + windowSize; i++) {
            if (i > bestStart) {
                snippet.append(' ');
            }
            if (isMatch[i]) {
                snippet.append("<mark>").append(words[i]).append("</mark>");
            } else {
                snippet.append(words[i]);
            }
        }
        if (bestStart > 0) {
            snippet.insert(0, "... ");
        }
        if (bestStart + windowSize < words.length) {
            snippet.append(" ...");
        }
        return snippet.toString();
    }

    private String stripPunctuation(String word) {
        return word.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        InvertedIndex index = new InvertedIndex();

        WebDocument doc0 = new WebDocument();
        doc0.setDocId(0);
        doc0.setUrl("https://vnsearch.example/may-tinh");
        doc0.setTitle("Đánh giá máy tính xách tay 2026");
        doc0.setBodyText("Bài viết này giới thiệu các dòng máy tính xách tay tốt nhất năm nay. "
                + "Máy tính xách tay cấu hình mạnh phù hợp cho sinh viên và dân văn phòng. "
                + "Giá máy tính dao động tùy cấu hình và thương hiệu.");
        index.addDocument(doc0);

        WebDocument doc1 = new WebDocument();
        doc1.setDocId(1);
        doc1.setUrl("https://vnsearch.example/cong-thuc-nau-an");
        doc1.setTitle("Công thức nấu ăn ngon mỗi ngày");
        doc1.setBodyText("Hướng dẫn nấu các món ăn ngon cho gia đình, dễ làm và tiết kiệm thời gian.");
        index.addDocument(doc1);

        Map<String, Integer> queryTermFrequency = Map.of("máy_tính", 1);
        Map<Integer, Double> pageRankScores = Map.of(0, 0.5, 1, 0.2);

        ResultRanker ranker = new ResultRanker();
        List<RankedResult> results = ranker.rank(List.of(0, 1), queryTermFrequency, index,
                new TfIdfScorer(), pageRankScores, 5);

        for (RankedResult r : results) {
            System.out.println("Title: " + r.document().getTitle());
            System.out.println("finalScore=" + r.finalScore() + " tfidf=" + r.tfidfScore() + " pageRank=" + r.pageRankScore());
            System.out.println("Snippet: " + r.snippet());
        }
    }
}
