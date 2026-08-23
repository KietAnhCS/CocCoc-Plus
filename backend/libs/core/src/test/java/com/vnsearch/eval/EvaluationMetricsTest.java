package com.vnsearch.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng các độ đo IR bằng cách đối chiếu với kết quả TÍNH TAY.
 *
 * <p>Mọi giá trị kỳ vọng trong file này đều kèm phép tính đầy đủ trong
 * comment, để có thể tự kiểm tra lại bằng máy tính bỏ túi — đây là điều
 * kiện tiên quyết trước khi dùng chúng để kết luận bất cứ điều gì về chất
 * lượng của search engine.
 */
class EvaluationMetricsTest {

    // ---------- Precision@k ----------

    @Test
    void precisionAtKCountsRelevantInTopK() {
        List<String> ranked = List.of("d1", "d2", "d3", "d4", "d5");
        Map<String, Integer> qrels = Map.of("d1", 1, "d3", 1, "d5", 2);

        assertEquals(1.0, EvaluationMetrics.precisionAtK(ranked, qrels, 1), 1e-9);      // 1/1
        assertEquals(2.0 / 3, EvaluationMetrics.precisionAtK(ranked, qrels, 3), 1e-9);  // d1,d3 -> 2/3
        assertEquals(0.6, EvaluationMetrics.precisionAtK(ranked, qrels, 5), 1e-9);      // 3/5
    }

    @Test
    void precisionAtKDividesByKNotByResultCount() {
        // Chỉ trả về 2 kết quả, cả 2 đều đúng. P@5 vẫn phải là 2/5 = 0.4
        // chứ không phải 2/2 = 1.0 — trả về quá ít kết quả là một khiếm khuyết.
        List<String> ranked = List.of("d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 1, "d2", 1);
        assertEquals(0.4, EvaluationMetrics.precisionAtK(ranked, qrels, 5), 1e-9);
    }

    @Test
    void unlabelledDocumentsCountAsIrrelevant() {
        List<String> ranked = List.of("chua-gan-nhan-1", "chua-gan-nhan-2");
        assertEquals(0.0, EvaluationMetrics.precisionAtK(ranked, Map.of(), 2), 1e-9);
    }

    // ---------- Recall@k ----------

    @Test
    void recallAtKDividesByTotalRelevant() {
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 1, "d3", 1, "d5", 1, "d9", 1); // 4 tài liệu liên quan
        // Top 3 lấy được d1, d3 -> 2/4 = 0.5
        assertEquals(0.5, EvaluationMetrics.recallAtK(ranked, qrels, 3), 1e-9);
    }

    @Test
    void recallIsZeroWhenNothingIsLabelledRelevant() {
        assertEquals(0.0, EvaluationMetrics.recallAtK(List.of("d1"), Map.of("d1", 0), 5), 1e-9);
    }

    // ---------- Average Precision ----------

    @Test
    void averagePrecisionRewardsEarlyRelevantResults() {
        // Liên quan tại vị trí 1, 3, 5 (tổng cộng 3 tài liệu liên quan):
        //   vị trí 1 -> P = 1/1 = 1.0
        //   vị trí 3 -> P = 2/3 = 0.666667
        //   vị trí 5 -> P = 3/5 = 0.6
        //   AP = (1.0 + 0.666667 + 0.6) / 3 = 2.266667 / 3 = 0.755556
        List<String> ranked = List.of("d1", "d2", "d3", "d4", "d5");
        Map<String, Integer> qrels = Map.of("d1", 1, "d3", 1, "d5", 1);
        assertEquals(0.7555555555, EvaluationMetrics.averagePrecision(ranked, qrels), 1e-9);
    }

    @Test
    void averagePrecisionIsOneForPerfectRanking() {
        // Cả 3 tài liệu liên quan nằm đúng 3 vị trí đầu:
        //   (1/1 + 2/2 + 3/3) / 3 = 1.0
        List<String> ranked = List.of("d1", "d2", "d3", "d4");
        Map<String, Integer> qrels = Map.of("d1", 1, "d2", 1, "d3", 1);
        assertEquals(1.0, EvaluationMetrics.averagePrecision(ranked, qrels), 1e-9);
    }

    @Test
    void averagePrecisionPenalisesMissedRelevantDocuments() {
        // Có 4 tài liệu liên quan nhưng hệ thống chỉ lấy được 2 (tại vị trí 1 và 3):
        //   (1/1 + 2/3) / 4 = 1.666667 / 4 = 0.416667
        // Mẫu số là TỔNG số tài liệu liên quan, nên bỏ sót vẫn bị phạt.
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 1, "d3", 1, "d7", 1, "d8", 1);
        assertEquals(0.4166666666, EvaluationMetrics.averagePrecision(ranked, qrels), 1e-9);
    }

    @Test
    void averagePrecisionIsSensitiveToOrderUnlikePrecisionAtK() {
        Map<String, Integer> qrels = Map.of("d1", 1, "d2", 1);
        List<String> good = List.of("d1", "d2", "x", "x2");  // liên quan ở đầu
        List<String> bad = List.of("x", "x2", "d1", "d2");   // liên quan ở cuối

        // P@4 giống hệt nhau (2/4) nhưng AP phải phân biệt được.
        assertEquals(EvaluationMetrics.precisionAtK(good, qrels, 4),
                EvaluationMetrics.precisionAtK(bad, qrels, 4), 1e-9);
        assertTrue(EvaluationMetrics.averagePrecision(good, qrels)
                > EvaluationMetrics.averagePrecision(bad, qrels));
    }

    // ---------- nDCG ----------

    @Test
    void ndcgMatchesHandComputedValue() {
        // ranked = [d1(mức 2), d2(mức 0), d3(mức 1)]
        // độ lợi   = 2^2-1=3,   2^0-1=0,   2^1-1=1
        // chiết khấu = log2(2)=1, log2(3)=1.5849625, log2(4)=2
        // DCG  = 3/1 + 0/1.5849625007 + 1/2 = 3.5
        // Thứ tự lý tưởng của các nhãn [2,1,0]:
        // IDCG = 3/1 + 1/1.5849625007 + 0/2 = 3 + 0.6309297536 = 3.6309297536
        // nDCG = 3.5 / 3.6309297536 = 0.9639404333
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 2, "d2", 0, "d3", 1);
        assertEquals(0.9639404333, EvaluationMetrics.ndcgAtK(ranked, qrels, 3), 1e-9);
    }

    @Test
    void ndcgIsOneForIdealOrdering() {
        List<String> ranked = List.of("d1", "d2", "d3");
        Map<String, Integer> qrels = Map.of("d1", 2, "d2", 1, "d3", 0);
        assertEquals(1.0, EvaluationMetrics.ndcgAtK(ranked, qrels, 3), 1e-9);
    }

    @Test
    void ndcgPrefersHighlyRelevantDocumentFirst() {
        Map<String, Integer> qrels = Map.of("rat-lien-quan", 2, "lien-quan", 1);
        List<String> better = List.of("rat-lien-quan", "lien-quan");
        List<String> worse = List.of("lien-quan", "rat-lien-quan");

        assertTrue(EvaluationMetrics.ndcgAtK(better, qrels, 2)
                > EvaluationMetrics.ndcgAtK(worse, qrels, 2),
                "Đưa tài liệu RẤT liên quan lên trước phải cho nDCG cao hơn");
    }

    @Test
    void ndcgIsZeroWhenNoJudgmentsExist() {
        assertEquals(0.0, EvaluationMetrics.ndcgAtK(List.of("d1"), Map.of(), 10), 1e-9);
    }

    @Test
    void ndcgStaysWithinUnitInterval() {
        List<String> ranked = List.of("d3", "d1", "d2", "d4");
        Map<String, Integer> qrels = Map.of("d1", 2, "d2", 1, "d3", 0, "d4", 2);
        double ndcg = EvaluationMetrics.ndcgAtK(ranked, qrels, 4);
        assertTrue(ndcg >= 0.0 && ndcg <= 1.0, "nDCG phải nằm trong [0,1], thực tế = " + ndcg);
    }

    // ---------- Reciprocal Rank / Success@k ----------

    @Test
    void reciprocalRankUsesFirstRelevantPosition() {
        List<String> ranked = List.of("x", "y", "d3");
        assertEquals(1.0 / 3, EvaluationMetrics.reciprocalRank(ranked, Map.of("d3", 1)), 1e-9);
    }

    @Test
    void reciprocalRankIsOneWhenTargetIsFirst() {
        assertEquals(1.0, EvaluationMetrics.reciprocalRank(List.of("d1", "d2"), "d1"), 1e-9);
    }

    @Test
    void reciprocalRankIsZeroWhenTargetIsAbsent() {
        assertEquals(0.0, EvaluationMetrics.reciprocalRank(List.of("a", "b"), "khong-co"), 1e-9);
    }

    @Test
    void successAtKRespectsCutoff() {
        List<String> ranked = List.of("a", "b", "c", "d", "target", "e");
        assertEquals(1.0, EvaluationMetrics.successAtK(ranked, "target", 5), 1e-9); // hạng 5, lọt top 5
        assertEquals(0.0, EvaluationMetrics.successAtK(ranked, "target", 4), 1e-9); // không lọt top 4
    }

    // ---------- Tổng hợp ----------

    @Test
    void meanAveragesAcrossQueries() {
        assertEquals(0.75, EvaluationMetrics.meanAveragePrecision(List.of(1.0, 0.5)), 1e-9);
        assertEquals(0.5, EvaluationMetrics.meanReciprocalRank(List.of(1.0, 0.5, 0.0)), 1e-9);
        assertEquals(0.0, EvaluationMetrics.mean(List.of()), 1e-9);
    }

    @Test
    void f1CombinesPrecisionAndRecall() {
        // ranked=[d1,d2] cả 2 đều liên quan, tổng có 4 tài liệu liên quan:
        //   P@2 = 2/2 = 1.0 ; R@2 = 2/4 = 0.5
        //   F1  = 2*1.0*0.5 / 1.5 = 0.666667
        List<String> ranked = List.of("d1", "d2");
        Map<String, Integer> qrels = Map.of("d1", 1, "d2", 1, "d3", 1, "d4", 1);
        assertEquals(0.6666666666, EvaluationMetrics.f1AtK(ranked, qrels, 2), 1e-9);
    }
}
