package com.vnsearch.eval;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.index.Tokenizer;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.query.CandidateResolver;
import com.vnsearch.query.QueryParser;
import com.vnsearch.ranking.RelevanceScorer;
import com.vnsearch.ranking.ResultRanker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chay mot truy van qua DUNG duong di tim kiem that (parse truy van -&gt; phan
 * giai ung vien -&gt; xep hang) nhung voi mot {@link RankingConfig} thay doi
 * duoc, de lam thi nghiem so sanh.
 *
 * <p><b>Diem mau chot ve tinh hop le khoa hoc:</b> lop nay dung lai NGUYEN SI
 * {@link QueryParser}, {@link CandidateResolver} va {@link ResultRanker} cua he
 * thong that. No chi thay DUNG MOT bien so moi thi nghiem — moi thu con lai giu
 * nguyen. Neu dung mot duong di rieng cho phan do thi ket luan rut ra se noi ve
 * duong di do chu khong noi gi ve san pham.
 *
 * <p><b>Sau khi chuyen sang Decorator</b>, mot "cau hinh xep hang" don gian la
 * mot chuoi scorer da lap ghep san — khong con ba trong so roi rac. Nho vay
 * {@link RelevanceScorer#name()} tu ghep thanh nhan mo ta day du cho bang ket
 * qua, vi du {@code "BM25(k1=1.2,b=0.75) + PR x0.30 + title x0.10"}.
 */
public class EvaluationHarness {

    /**
     * Mot cau hinh xep hang dem ra so sanh.
     *
     * @param label  nhan hien thi trong bang ket qua
     * @param scorer mo hinh tinh diem, co the la mot chuoi Decorator
     */
    public record RankingConfig(String label, RelevanceScorer scorer) {

        /**
         * Lap ghep mot cau hinh tu scorer co so va hai trong so tin hieu bo sung.
         *
         * <p>Day la <b>Decorator</b> duoc dung dung muc dich cua no: bat/tat mot
         * tin hieu chi la them/bot mot lop boc. Trong so bang 0 thi lop boc
         * tuong ung duoc bo han — khong tra chi phi cho tin hieu bi tat.
         *
         * @param pageRankWeight trong so PageRank (0 = tat)
         * @param titleWeight    trong so khop tieu de (0 = tat)
         */
        public static RankingConfig of(String label, RelevanceScorer base,
                                        Map<Integer, Double> pageRankScores,
                                        double pageRankWeight, double titleWeight) {
            RelevanceScorer scorer = base;
            if (pageRankWeight > 0 && pageRankScores != null && !pageRankScores.isEmpty()) {
                scorer = new com.vnsearch.ranking.decorator.PageRankBoostScorer(
                        scorer, pageRankScores, pageRankWeight);
            }
            if (titleWeight > 0) {
                scorer = new com.vnsearch.ranking.decorator.TitleBoostScorer(scorer, titleWeight);
            }
            return new RankingConfig(label, scorer);
        }
    }

    private final SearchIndex index;
    private final Map<Integer, Double> pageRankScores;
    private final QueryParser queryParser;
    private final ResultRanker ranker = new ResultRanker();

    public EvaluationHarness(SearchIndex index, Map<Integer, Double> pageRankScores) {
        this(index, pageRankScores, new VietnameseTokenizer());
    }

    public EvaluationHarness(SearchIndex index, Map<Integer, Double> pageRankScores, Tokenizer tokenizer) {
        this.index = index;
        this.pageRankScores = pageRankScores;
        // BAT BIEN: phai dung CHINH tokenizer da dung luc index.
        this.queryParser = new QueryParser(tokenizer);
    }

    /**
     * Chay truy van va tra ve danh sach URL da xep hang (dai toi da {@code topN}).
     *
     * <p>Tra ve URL thay vi docId de khop voi cach qrels duoc luu — docId doi
     * sau moi lan crawl lai, URL thi khong.
     */
    public List<String> search(String queryText, RankingConfig config, int topN) {
        QueryParser.ParsedQuery parsed = queryParser.parse(queryText);
        CandidateResolver.ResolvedQuery resolved = CandidateResolver.resolve(index, parsed);
        if (resolved.candidateDocIds().isEmpty()) {
            return List.of();
        }

        List<ResultRanker.RankedResult> ranked = ranker.rank(
                resolved.candidateDocIds(), resolved.queryTermFrequency(),
                index, config.scorer(), pageRankScores, topN);

        List<String> urls = new ArrayList<>(ranked.size());
        for (ResultRanker.RankedResult result : ranked) {
            urls.add(result.document().getUrl());
        }
        return urls;
    }

    /** So ung vien khop truy van truoc khi cat top-N — dung de bao cao do bao phu. */
    public int candidateCount(String queryText) {
        QueryParser.ParsedQuery parsed = queryParser.parse(queryText);
        return CandidateResolver.resolve(index, parsed).candidateDocIds().size();
    }
}
