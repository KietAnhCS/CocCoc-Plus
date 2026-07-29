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
                                    TfIdfScorer scorer,
                                    Map<Integer, Double> pageRankScores,
                                    int topN) {
        Set<String> queryKeywordSyllables = extractSyllables(queryTermFrequency.keySet());

        List<RankedResult> all = new ArrayList<>(candidateDocIds.size());
        for (int docId : candidateDocIds) {
            WebDocument doc = index.getDocument(docId);
            if (doc == null) {
                continue;
            }
            double tfidf = scorer.score(queryTermFrequency, docId, index);
            double pageRank = pageRankScores.getOrDefault(docId, 0.0);
            double titleBonus = titleMatchBonus(queryKeywordSyllables, doc.getTitle());
            double finalScore = alpha * tfidf + beta * pageRank + gamma * titleBonus;
            String snippet = buildSnippet(doc.getBodyText(), queryKeywordSyllables);
            all.add(new RankedResult(doc, finalScore, tfidf, pageRank, snippet));
        }

        return MinHeap.topK(all, topN, Comparator.comparingDouble(RankedResult::finalScore));
    }

    /** Tach cac term truy van (co the ghep boi "_") thanh tap hop tieng rieng le, khong dau, lowercase. */
    private Set<String> extractSyllables(Set<String> terms) {
        Set<String> syllables = new HashSet<>();
        for (String term : terms) {
            for (String syllable : term.split("_")) {
                syllables.add(VietnameseTokenizer.stripDiacritics(syllable).toLowerCase());
            }
        }
        return syllables;
    }

    private double titleMatchBonus(Set<String> queryKeywordSyllables, String title) {
        if (title == null || title.isBlank() || queryKeywordSyllables.isEmpty()) {
            return 0.0;
        }
        String[] titleWords = title.toLowerCase().split("\\s+");
        int matched = 0;
        for (String word : titleWords) {
            String normalized = VietnameseTokenizer.stripDiacritics(stripPunctuation(word));
            if (queryKeywordSyllables.contains(normalized)) {
                matched++;
            }
        }
        return queryKeywordSyllables.isEmpty() ? 0.0 : Math.min(1.0, (double) matched / queryKeywordSyllables.size());
    }

    private String buildSnippet(String bodyText, Set<String> queryKeywordSyllables) {
        if (bodyText == null || bodyText.isBlank()) {
            return "";
        }
        String[] words = bodyText.trim().split("\\s+");
        if (words.length == 0) {
            return "";
        }

        boolean[] isMatch = new boolean[words.length];
        for (int i = 0; i < words.length; i++) {
            String normalized = VietnameseTokenizer.stripDiacritics(stripPunctuation(words[i])).toLowerCase();
            isMatch[i] = queryKeywordSyllables.contains(normalized);
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
