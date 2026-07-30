package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Posting;

import java.util.List;
import java.util.Map;

/**
 * BM25 (Okapi BM25) — mô hình xếp hạng chuẩn công nghiệp, dùng làm
 * <b>baseline</b> để đối chiếu với {@link TfIdfScorer} cosine.
 *
 * <p>Công thức:
 * <pre>
 *   score(D,Q) = Σ  IDF(q) · ( f(q,D) · (k1 + 1) )
 *                q            ─────────────────────────────────────
 *                             f(q,D) + k1 · (1 − b + b · |D| / avgdl)
 *
 *   IDF(q) = ln( 1 + (N − df + 0.5) / (df + 0.5) )
 * </pre>
 *
 * <p><b>BM25 hơn TF-IDF cosine ở hai điểm:</b>
 *
 * <p>1. <i>Tần suất bão hoà.</i> Ở TF-IDF, {@code tf = 1 + log10(f)} vẫn
 * tăng vô hạn theo số lần lặp từ. Ở BM25, phân thức
 * {@code f/(f + k1·…)} tiến tới trần {@code k1 + 1} khi f lớn: từ khoá
 * xuất hiện 50 lần gần như không hơn gì xuất hiện 20 lần. Điều này khớp
 * với trực giác — một bài đã nói về "bóng đá" 20 lần thì rõ ràng là nói về
 * bóng đá rồi, lặp thêm 30 lần nữa không làm nó liên quan hơn, mà thường
 * chỉ là dấu hiệu nhồi từ khoá.
 *
 * <p>2. <i>Chuẩn hoá độ dài có tham số điều chỉnh.</i> TF-IDF ở đây chia
 * cứng cho {@code sqrt(docLength)}. BM25 dùng tham số {@code b} để chỉnh
 * mức độ phạt tài liệu dài: {@code b = 0} là không phạt gì, {@code b = 1}
 * là chuẩn hoá hoàn toàn theo {@code |D|/avgdl}. Giá trị mặc định
 * {@code b = 0.75} là dung hoà đã được kiểm chứng qua nhiều thập kỷ thực
 * nghiệm TREC.
 *
 * <p><b>Vì sao IDF của BM25 khác:</b> dạng
 * {@code ln(1 + (N − df + 0.5)/(df + 0.5))} xuất phát từ mô hình xác suất
 * Robertson–Sparck Jones. Các số hạng {@code +0.5} là làm trơn, tránh chia
 * cho 0; bọc trong {@code ln(1 + …)} để IDF không bao giờ âm — khác với
 * {@code log10(N/df)} của TF-IDF vốn hoá âm khi một term xuất hiện ở hơn
 * một nửa số tài liệu, khiến tài liệu chứa nó bị TRỪ điểm một cách vô lý.
 *
 * <p>Độ phức tạp thời gian: {@code O(q log d)} — giống TF-IDF, vì cũng
 * dùng binary search trên posting list đã sắp xếp theo docId.
 */
public class BM25Scorer implements RelevanceScorer {

    /** Điều khiển mức bão hoà tần suất. Chuẩn thực nghiệm: 1.2 - 2.0. */
    public static final double DEFAULT_K1 = 1.2;

    /** Mức chuẩn hoá theo độ dài tài liệu: 0 = không phạt, 1 = chuẩn hoá hoàn toàn. */
    public static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;

    public BM25Scorer() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    public BM25Scorer(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    /** IDF theo mô hình xác suất Robertson–Sparck Jones, luôn không âm. */
    public static double idf(int totalDocs, int documentFrequency) {
        if (documentFrequency <= 0 || totalDocs <= 0) {
            return 0.0;
        }
        return Math.log(1 + ((double) totalDocs - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

    @Override
    public double score(Map<String, Integer> queryTermFrequency, int docId, InvertedIndex index) {
        int totalDocs = index.getTotalDocs();
        if (totalDocs == 0) {
            return 0.0;
        }
        double avgDocLength = index.getAverageDocLength();
        if (avgDocLength <= 0) {
            return 0.0;
        }
        int docLength = index.getDocLength(docId);
        // Hệ số chuẩn hoá độ dài, tính một lần cho cả truy vấn vì không phụ thuộc term.
        double lengthNorm = k1 * (1 - b + b * (docLength / avgDocLength));

        double total = 0.0;
        for (Map.Entry<String, Integer> entry : queryTermFrequency.entrySet()) {
            List<Posting> postings = index.getPostings(entry.getKey());
            int df = postings.size();
            if (df == 0) {
                continue;
            }
            int termFrequency = findTermFrequencyInDoc(postings, docId);
            if (termFrequency == 0) {
                continue;
            }
            double saturated = (termFrequency * (k1 + 1)) / (termFrequency + lengthNorm);
            total += idf(totalDocs, df) * saturated;
        }
        return total;
    }

    @Override
    public String name() {
        return String.format("BM25(k1=%.1f,b=%.2f)", k1, b);
    }

    /**
     * Binary search trên posting list đã sắp xếp theo docId — {@code O(log n)}
     * thay vì quét tuyến tính, tận dụng đúng bất biến mà InvertedIndex đảm bảo.
     */
    private int findTermFrequencyInDoc(List<Posting> postings, int docId) {
        int low = 0, high = postings.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midDocId = postings.get(mid).docId();
            if (midDocId == docId) {
                return postings.get(mid).termFrequency();
            } else if (midDocId < docId) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return 0;
    }
}
