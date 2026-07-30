package com.vnsearch.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Các độ đo chuẩn của ngành Information Retrieval, tự cài đặt để đánh giá
 * CHẤT LƯỢNG kết quả tìm kiếm (khác với các số đo hiệu năng như thời gian
 * truy vấn hay cache hit rate vốn chỉ nói lên tốc độ, không nói lên độ
 * đúng).
 *
 * <p><b>Quy ước chung:</b>
 * <ul>
 *   <li>Danh sách kết quả được biểu diễn bằng {@code List<String>} các URL
 *       đã xếp hạng giảm dần theo điểm. Dùng URL làm định danh thay vì
 *       {@code docId} vì docId được gán lại mỗi lần crawl — nhãn liên quan
 *       gán tay sẽ hỏng hết sau lần crawl kế tiếp, còn URL thì ổn định.</li>
 *   <li>Nhãn liên quan (qrels) là {@code Map<URL, mức độ>} với mức độ
 *       0 = không liên quan, 1 = liên quan, 2 = rất liên quan. URL không có
 *       trong map được coi là mức 0 (giả định "chưa gán nhãn tức là không
 *       liên quan" — đúng chuẩn TREC khi dùng phương pháp pooling).</li>
 *   <li>Với các độ đo nhị phân (P, R, MAP) thì mức &ge; 1 được coi là liên
 *       quan; chỉ nDCG mới dùng đến mức độ chi tiết.</li>
 * </ul>
 */
public final class EvaluationMetrics {

    private EvaluationMetrics() {
    }

    /** Ngưỡng để một mức độ được coi là "liên quan" trong các độ đo nhị phân. */
    public static final int RELEVANT_THRESHOLD = 1;

    private static int gradeOf(Map<String, Integer> qrels, String url) {
        Integer grade = qrels.get(url);
        return grade == null ? 0 : grade;
    }

    private static boolean isRelevant(Map<String, Integer> qrels, String url) {
        return gradeOf(qrels, url) >= RELEVANT_THRESHOLD;
    }

    /**
     * Precision@k — trong {@code k} kết quả đầu, bao nhiêu phần là liên quan.
     *
     * <p>Mẫu số luôn là {@code k} chứ không phải {@code min(k, số kết quả
     * trả về)}, theo đúng quy ước TREC. Lý do: một hệ thống trả về 3 kết quả
     * đúng cả 3 KHÔNG nên được chấm P@10 = 1,0 ngang với hệ thống trả đủ 10
     * kết quả đúng cả 10 — việc trả về quá ít kết quả tự nó đã là một khiếm
     * khuyết và phải bị phạt.
     */
    public static double precisionAtK(List<String> ranked, Map<String, Integer> qrels, int k) {
        if (k <= 0) {
            return 0.0;
        }
        int hits = 0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (isRelevant(qrels, ranked.get(i))) {
                hits++;
            }
        }
        return (double) hits / k;
    }

    /**
     * Recall@k — trong toàn bộ tài liệu liên quan đã biết, hệ thống lấy được
     * bao nhiêu phần trong {@code k} kết quả đầu. Trả về 0 nếu không có tài
     * liệu liên quan nào được gán nhãn (không xác định được).
     */
    public static double recallAtK(List<String> ranked, Map<String, Integer> qrels, int k) {
        long totalRelevant = countRelevant(qrels);
        if (totalRelevant == 0 || k <= 0) {
            return 0.0;
        }
        int hits = 0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (isRelevant(qrels, ranked.get(i))) {
                hits++;
            }
        }
        return (double) hits / totalRelevant;
    }

    /** Số tài liệu được gán nhãn là liên quan cho một truy vấn. */
    public static long countRelevant(Map<String, Integer> qrels) {
        return qrels.values().stream().filter(g -> g >= RELEVANT_THRESHOLD).count();
    }

    /**
     * Average Precision — trung bình của Precision@i tại MỌI vị trí i có tài
     * liệu liên quan, chia cho tổng số tài liệu liên quan.
     *
     * <p>Đây là độ đo nhạy với THỨ TỰ: đưa tài liệu đúng lên vị trí 1 được
     * điểm cao hơn hẳn đưa nó xuống vị trí 10, trong khi P@10 thì không phân
     * biệt. Chia cho tổng số tài liệu liên quan (chứ không phải số tài liệu
     * liên quan tìm được) để hệ thống bỏ sót vẫn bị phạt.
     */
    public static double averagePrecision(List<String> ranked, Map<String, Integer> qrels) {
        long totalRelevant = countRelevant(qrels);
        if (totalRelevant == 0) {
            return 0.0;
        }
        double sumPrecision = 0.0;
        int hits = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (isRelevant(qrels, ranked.get(i))) {
                hits++;
                sumPrecision += (double) hits / (i + 1); // Precision tại đúng vị trí này
            }
        }
        return sumPrecision / totalRelevant;
    }

    /** Mean Average Precision — trung bình cộng của AP trên toàn bộ bộ truy vấn. */
    public static double meanAveragePrecision(Collection<Double> averagePrecisions) {
        return mean(averagePrecisions);
    }

    /**
     * nDCG@k — Normalized Discounted Cumulative Gain, độ đo duy nhất ở đây
     * tận dụng được mức độ liên quan nhiều bậc (0/1/2) thay vì chỉ nhị phân.
     *
     * <p>{@code DCG@k = Σ (2^rel_i − 1) / log2(i + 1)}, chuẩn hoá bằng cách
     * chia cho {@code IDCG@k} là DCG của thứ tự lý tưởng (sắp mọi nhãn giảm
     * dần). Kết quả luôn nằm trong [0, 1] nên so sánh được giữa các truy vấn
     * có số tài liệu liên quan khác nhau.
     *
     * <p>Dùng độ lợi HÀM MŨ {@code 2^rel − 1} thay vì tuyến tính {@code rel}:
     * với thang 0/1/2 thì tài liệu "rất liên quan" được 3 điểm còn "liên
     * quan" được 1 điểm, tức nhấn mạnh gấp ba. Cách tuyến tính chỉ cho tỷ lệ
     * 2:1, không phản ánh đúng thực tế là người dùng quan tâm kết quả xuất
     * sắc hơn nhiều so với kết quả tạm được. Mẫu số {@code log2(i + 1)} là
     * hệ số chiết khấu theo vị trí: càng xuống dưới càng ít người nhìn tới.
     */
    public static double ndcgAtK(List<String> ranked, Map<String, Integer> qrels, int k) {
        if (k <= 0) {
            return 0.0;
        }
        double dcg = 0.0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            dcg += gain(gradeOf(qrels, ranked.get(i))) / discount(i);
        }

        List<Integer> idealGrades = new ArrayList<>(qrels.values());
        idealGrades.sort((a, b) -> Integer.compare(b, a)); // giảm dần
        double idcg = 0.0;
        int idealLimit = Math.min(k, idealGrades.size());
        for (int i = 0; i < idealLimit; i++) {
            idcg += gain(idealGrades.get(i)) / discount(i);
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double gain(int grade) {
        return Math.pow(2, grade) - 1;
    }

    /** Hệ số chiết khấu theo vị trí (i tính từ 0): log2(i + 2) = log2(hạng + 1). */
    private static double discount(int zeroBasedIndex) {
        return Math.log(zeroBasedIndex + 2) / Math.log(2);
    }

    /**
     * Reciprocal Rank — nghịch đảo thứ hạng của tài liệu liên quan ĐẦU TIÊN,
     * trả về 0 nếu không có tài liệu liên quan nào trong danh sách.
     *
     * <p>Đây là độ đo phù hợp nhất cho bài toán known-item search (tìm đúng
     * một tài liệu đã biết trước): người dùng chỉ cần một kết quả đúng, và
     * điều duy nhất quan trọng là nó nằm ở hạng bao nhiêu.
     */
    public static double reciprocalRank(List<String> ranked, Map<String, Integer> qrels) {
        for (int i = 0; i < ranked.size(); i++) {
            if (isRelevant(qrels, ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Reciprocal Rank cho trường hợp chỉ có duy nhất một tài liệu đích. */
    public static double reciprocalRank(List<String> ranked, String targetUrl) {
        int rank = ranked.indexOf(targetUrl);
        return rank < 0 ? 0.0 : 1.0 / (rank + 1);
    }

    /** Mean Reciprocal Rank — trung bình cộng RR trên toàn bộ bộ truy vấn. */
    public static double meanReciprocalRank(Collection<Double> reciprocalRanks) {
        return mean(reciprocalRanks);
    }

    /**
     * Success@k — tài liệu đích có nằm trong {@code k} kết quả đầu không
     * (1 hoặc 0). Trung bình trên bộ truy vấn cho biết tỷ lệ truy vấn mà
     * người dùng tìm thấy thứ mình cần ngay trang đầu.
     */
    public static double successAtK(List<String> ranked, String targetUrl, int k) {
        int rank = ranked.indexOf(targetUrl);
        return rank >= 0 && rank < k ? 1.0 : 0.0;
    }

    /** F1@k — trung bình điều hoà của Precision@k và Recall@k. */
    public static double f1AtK(List<String> ranked, Map<String, Integer> qrels, int k) {
        double p = precisionAtK(ranked, qrels, k);
        double r = recallAtK(ranked, qrels, k);
        return (p + r) == 0.0 ? 0.0 : 2 * p * r / (p + r);
    }

    /** Trung bình cộng, trả về 0 cho tập rỗng. */
    public static double mean(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }
}
