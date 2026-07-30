package com.vnsearch.eval;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.query.CandidateResolver;
import com.vnsearch.query.QueryParser;
import com.vnsearch.ranking.RelevanceScorer;
import com.vnsearch.ranking.ResultRanker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chạy một truy vấn qua đúng đường đi tìm kiếm thật (parse truy vấn →
 * phân giải ứng viên → xếp hạng) nhưng với một {@link RankingConfig}
 * <b>thay đổi được</b>, để làm thí nghiệm so sánh.
 *
 * <p>Điểm mấu chốt về tính hợp lệ khoa học: lớp này dùng lại nguyên si
 * {@link QueryParser}, {@link CandidateResolver} và {@link ResultRanker}
 * của hệ thống thật. Nó chỉ thay ĐÚNG MỘT biến số mỗi lần thí nghiệm (mô
 * hình tính điểm, hoặc bộ trọng số) — mọi thứ còn lại giữ nguyên. Nếu
 * dựng một đường đi riêng cho phần đo thì kết luận rút ra sẽ nói về đường
 * đi đó chứ không nói gì về sản phẩm.
 */
public class EvaluationHarness {

    /**
     * Một cấu hình xếp hạng đem ra so sánh.
     *
     * @param label  nhãn hiển thị trong bảng kết quả
     * @param scorer mô hình tính điểm liên quan (TF-IDF hoặc BM25)
     * @param alpha  trọng số điểm liên quan
     * @param beta   trọng số PageRank
     * @param gamma  trọng số khớp tiêu đề
     */
    public record RankingConfig(String label, RelevanceScorer scorer,
                                 double alpha, double beta, double gamma) {
    }

    private final InvertedIndex index;
    private final Map<Integer, Double> pageRankScores;
    private final QueryParser queryParser;

    public EvaluationHarness(InvertedIndex index, Map<Integer, Double> pageRankScores) {
        this.index = index;
        this.pageRankScores = pageRankScores;
        this.queryParser = new QueryParser();
    }

    /**
     * Chạy truy vấn và trả về danh sách URL đã xếp hạng (dài tối đa
     * {@code topN}). Trả về URL thay vì docId để khớp với cách qrels được
     * lưu — docId đổi sau mỗi lần crawl lại, URL thì không.
     */
    public List<String> search(String queryText, RankingConfig config, int topN) {
        QueryParser.ParsedQuery parsed = queryParser.parse(queryText);
        CandidateResolver.ResolvedQuery resolved = CandidateResolver.resolve(index, parsed);
        if (resolved.candidateDocIds().isEmpty()) {
            return List.of();
        }

        ResultRanker ranker = new ResultRanker(config.alpha(), config.beta(), config.gamma());
        List<ResultRanker.RankedResult> ranked = ranker.rank(
                resolved.candidateDocIds(), resolved.queryTermFrequency(),
                index, config.scorer(), pageRankScores, topN);

        List<String> urls = new ArrayList<>(ranked.size());
        for (ResultRanker.RankedResult result : ranked) {
            urls.add(result.document().getUrl());
        }
        return urls;
    }

    /** Số ứng viên khớp truy vấn trước khi cắt top-N — dùng để báo cáo độ bao phủ. */
    public int candidateCount(String queryText) {
        QueryParser.ParsedQuery parsed = queryParser.parse(queryText);
        return CandidateResolver.resolve(index, parsed).candidateDocIds().size();
    }
}
