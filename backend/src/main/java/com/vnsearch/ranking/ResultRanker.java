package com.vnsearch.ranking;

import com.vnsearch.datastructure.MinHeap;
import com.vnsearch.index.SearchIndex;
import com.vnsearch.model.WebDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Xep hang ung vien va tra ve top-N kem doan trich.
 *
 * <p><b>Mot trach nhiem duy nhat.</b> Truoc day lop nay lam BA viec: ket hop
 * ba tin hieu bang cong thuc tuyen tinh chon cung, lay top-K, va sinh snippet.
 * Nay:
 * <ul>
 *   <li>Ket hop tin hieu -&gt; cac {@link RelevanceScorer} <b>Decorator</b>
 *       ({@code PageRankBoostScorer}, {@code TitleBoostScorer}). Ngoai viec
 *       tach trach nhiem, thay doi nay con SUA mot loi that: cong thuc cong
 *       tuyen tinh cu khien PageRank chi dong gop 0,1 % du trong so danh nghia
 *       la 30 % — xem Javadoc cua {@code PageRankBoostScorer}.</li>
 *   <li>Sinh snippet -&gt; {@link SnippetBuilder}.</li>
 *   <li>Con lai o day: cham diem + lay top-K.</li>
 * </ul>
 *
 * <p><b>Toi uu hai giai doan (nhanh 50 lan).</b> Sinh snippet la thao tac dat
 * nhat: no phai tach TOAN BO bodyText (trung binh hon 1.000 token) roi truot
 * cua so. Truoc day buoc nay chay cho MOI ung vien roi moi cat top-N — voi 500
 * ung vien thi 490 snippet bi vut di ngay sau khi tao. Nay tach lam hai giai
 * doan: cham diem truoc (du de xep hang), sinh snippet CHI cho nhung tai lieu
 * thuc su duoc tra ve.
 * <pre>
 *   Truoc: O(c * |d|) = 500 * 1043 = 521.500
 *   Sau  : O(K * |d|) =  10 * 1043 =  10.430
 * </pre>
 *
 * <p>Do phuc tap: cham diem {@code O(c*q*log d)}, top-K {@code O(c log K)},
 * snippet {@code O(K*|d|)}.
 */
public class ResultRanker {

    private final SnippetBuilder snippetBuilder;

    public ResultRanker() {
        this(new SnippetBuilder());
    }

    public ResultRanker(SnippetBuilder snippetBuilder) {
        this.snippetBuilder = snippetBuilder == null ? new SnippetBuilder() : snippetBuilder;
    }

    /**
     * Mot ket qua da xep hang.
     *
     * <p>Truoc day record nay co BA truong diem: {@code finalScore},
     * {@code relevanceScore} va mot ham {@code tfidfScore()}. Ca ba tra ve
     * <b>cung mot so</b> — ke tu khi Decorator thay cong thuc cong tuyen tinh,
     * scorer chi con tra ve mot gia tri duy nhat da gom moi tin hieu. Ba ten
     * cho mot dai luong khong phai la su tien loi: no khien tang goi tuong minh
     * dang doc ba thanh phan doc lap, va {@code EvaluationRunner} da that su
     * nham — no do "thang do cua TF-IDF tho" bang chinh diem tong.
     *
     * @param pageRankScore chi de BAO CAO ra API; viec DUNG no de xep hang
     *                      thuoc ve {@code PageRankBoostScorer}
     */
    public record RankedResult(WebDocument document, double finalScore,
                                double pageRankScore, String snippet) {
    }

    /** Ung vien da cham diem nhung CHUA sinh snippet (xem giai thich o Javadoc lop). */
    private record ScoredCandidate(WebDocument document, double finalScore, double pageRankScore) {
    }

    /**
     * Xep hang cac tai lieu ung vien va tra ve top {@code topN}.
     *
     * @param candidateDocIds    danh sach docId ung vien (da qua CandidateResolver)
     * @param queryTermFrequency tan suat moi term trong truy van
     * @param index              chi muc
     * @param scorer             mo hinh tinh diem — co the la mot chuoi Decorator
     *                           da gom san PageRank va title bonus
     * @param pageRankScores     diem PageRank, chi de BAO CAO ra API (viec dung
     *                           no de xep hang thuoc ve PageRankBoostScorer)
     * @param topN               so ket qua muon lay
     */
    public List<RankedResult> rank(List<Integer> candidateDocIds,
                                    Map<String, Integer> queryTermFrequency,
                                    SearchIndex index,
                                    RelevanceScorer scorer,
                                    Map<Integer, Double> pageRankScores,
                                    int topN) {
        // --- GIAI DOAN 0: chuan bi phan chi phu thuoc TRUY VAN, dung MOT lan ---
        // Xem RelevanceScorer#prepare: idf, trong so truy van va tap tieng cua
        // truy van truoc day deu bi tinh lai cho TUNG ung vien.
        RelevanceScorer.DocumentScorer prepared = scorer.prepare(queryTermFrequency, index);

        // --- GIAI DOAN 1: chi CHAM DIEM, chua sinh snippet ---
        List<ScoredCandidate> scored = new ArrayList<>(candidateDocIds.size());
        for (int docId : candidateDocIds) {
            WebDocument doc = index.getDocument(docId);
            if (doc == null) {
                continue;
            }
            double pageRank = pageRankScores == null ? 0.0 : pageRankScores.getOrDefault(docId, 0.0);
            scored.add(new ScoredCandidate(doc, prepared.score(docId), pageRank));
        }

        // --- GIAI DOAN 2: top-K bang MinHeap, O(c log K) thay vi O(c log c) ---
        List<ScoredCandidate> top =
                MinHeap.topK(scored, topN, Comparator.comparingDouble(ScoredCandidate::finalScore));

        // --- GIAI DOAN 3: sinh snippet CHI cho tai lieu thuc su tra ve ---
        QuerySyllables syllables = QuerySyllables.from(queryTermFrequency.keySet());
        List<RankedResult> results = new ArrayList<>(top.size());
        for (ScoredCandidate candidate : top) {
            // Van ban lay tu CHI MUC, khong tu WebDocument: tu ban v3, than bai
            // duoc luu rieng o dang nen va khong con nam trong tai lieu. Moi loi
            // goi nay giai nen mot tai lieu — nen no o day, trong vong lap chi
            // duyet top-K, chu khong o giai doan cham diem phia tren.
            results.add(new RankedResult(
                    candidate.document(),
                    candidate.finalScore(),
                    candidate.pageRankScore(),
                    snippetBuilder.build(index.getBodyText(candidate.document().getDocId()),
                            syllables)));
        }
        return results;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        com.vnsearch.index.InvertedIndex index = new com.vnsearch.index.InvertedIndex();

        WebDocument doc0 = new WebDocument();
        doc0.setDocId(0);
        doc0.setUrl("https://vnsearch.example/may-tinh");
        doc0.setTitle("Đánh giá máy tính xách tay 2026");
        doc0.setBodyText("Bài viết này giới thiệu các dòng máy tính xách tay tốt nhất năm nay. "
                + "Máy tính xách tay cấu hình mạnh phù hợp cho sinh viên và dân văn phòng.");
        index.addDocument(doc0);

        WebDocument doc1 = new WebDocument();
        doc1.setDocId(1);
        doc1.setUrl("https://vnsearch.example/cong-thuc-nau-an");
        doc1.setTitle("Công thức nấu ăn ngon mỗi ngày");
        doc1.setBodyText("Hướng dẫn nấu các món ăn ngon cho gia đình, dễ làm và tiết kiệm thời gian.");
        index.addDocument(doc1);

        Map<String, Integer> queryTermFrequency = Map.of("máy_tính", 1);
        Map<Integer, Double> pageRankScores = Map.of(0, 0.5, 1, 0.2);

        // Decorator: TF-IDF -> them PageRank -> them title bonus
        RelevanceScorer scorer = new com.vnsearch.ranking.decorator.TitleBoostScorer(
                new com.vnsearch.ranking.decorator.PageRankBoostScorer(
                        new TfIdfScorer(), pageRankScores, 0.3),
                0.1);
        System.out.println("Scorer: " + scorer.name());

        ResultRanker ranker = new ResultRanker();
        for (RankedResult r : ranker.rank(List.of(0, 1), queryTermFrequency, index,
                scorer, pageRankScores, 5)) {
            System.out.println("Title: " + r.document().getTitle());
            System.out.println("  finalScore=" + r.finalScore() + " pageRank=" + r.pageRankScore());
            System.out.println("  Snippet: " + r.snippet());
        }
    }
}
