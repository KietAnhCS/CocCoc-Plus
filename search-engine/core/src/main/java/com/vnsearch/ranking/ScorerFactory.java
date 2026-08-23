package com.vnsearch.ranking;

import com.vnsearch.ranking.decorator.PageRankBoostScorer;
import com.vnsearch.ranking.decorator.TitleBoostScorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * <b>Factory pattern</b> — tao {@link RelevanceScorer} tu cau hinh, va lap
 * ghep cac {@link com.vnsearch.ranking.decorator Decorator} tin hieu bo sung.
 *
 * <p><b>Van de that ma lop nay giai.</b> Giao dien {@code RelevanceScorer} da
 * ton tai va hoat dong tot, nhung {@code SearchEngineFacade} lai CHON CUNG cai
 * dat: {@code private final TfIdfScorer tfIdfScorer = new TfIdfScorer();}.
 * Hau qua: do dac cho thay BM25 dat MRR 0,8989 so voi 0,8537 cua TF-IDF —
 * <b>hon 5,3 %</b> — nhung khong co cach nao de nguoi dung that nhan duoc ket
 * qua BM25 ma khong sua ma nguon va bien dich lai. Strategy chi duoc bo danh
 * gia khai thac; san pham thi khong.
 *
 * <p>Nay chi can doi mot dong trong {@code application.properties}:
 * <pre>
 *   app.ranking.scorer=bm25
 *   app.ranking.bm25.k1=1.2
 *   app.ranking.bm25.b=0.75
 *   app.ranking.beta=0.30     # trong so PageRank
 *   app.ranking.gamma=0.10    # trong so khop tieu de
 * </pre>
 *
 * <p><b>Thu tu boc Decorator</b> co y nghia: scorer co so nam trong cung, cac
 * tin hieu bo sung boc dan ra ngoai. Ten cua ket qua tu ghep thanh mo ta day
 * du, vi du {@code "BM25(k1=1.2,b=0.75) + PR x0.30 + title x0.10"}.
 */
@Component
public class ScorerFactory {

    @Value("${app.ranking.scorer:tfidf}")
    private String scorerType = "tfidf";

    @Value("${app.ranking.bm25.k1:1.2}")
    private double k1 = BM25Scorer.DEFAULT_K1;

    @Value("${app.ranking.bm25.b:0.75}")
    private double b = BM25Scorer.DEFAULT_B;

    @Value("${app.ranking.beta:0.30}")
    private double pageRankWeight = 0.30;

    @Value("${app.ranking.gamma:0.10}")
    private double titleWeight = 0.10;

    public ScorerFactory() {
    }

    /** Constructor tuong minh cho test va cho cac runner chay ngoai Spring. */
    public ScorerFactory(String scorerType, double k1, double b,
                          double pageRankWeight, double titleWeight) {
        this.scorerType = scorerType;
        this.k1 = k1;
        this.b = b;
        this.pageRankWeight = pageRankWeight;
        this.titleWeight = titleWeight;
    }

    /** Tao scorer CO SO (chua boc tin hieu bo sung). */
    public RelevanceScorer createBase() {
        String type = scorerType == null ? "tfidf" : scorerType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "bm25" -> new BM25Scorer(k1, b);
            case "tfidf", "tf-idf" -> new TfIdfScorer();
            default -> throw new IllegalArgumentException(
                    "app.ranking.scorer phai la 'tfidf' hoac 'bm25', nhan duoc: " + scorerType);
        };
    }

    /**
     * Tao scorer DAY DU: scorer co so, boc them PageRank va khop tieu de.
     *
     * <p>Trong so bang 0 thi lop boc tuong ung duoc bo han — khong tra chi phi
     * cho mot tin hieu bi tat.
     */
    public RelevanceScorer create(Map<Integer, Double> pageRankScores) {
        RelevanceScorer scorer = createBase();
        if (pageRankWeight > 0 && pageRankScores != null && !pageRankScores.isEmpty()) {
            scorer = new PageRankBoostScorer(scorer, pageRankScores, pageRankWeight);
        }
        if (titleWeight > 0) {
            scorer = new TitleBoostScorer(scorer, titleWeight);
        }
        return scorer;
    }
}
