package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;

import java.util.Map;

/**
 * Giao diện chung cho các mô hình tính điểm liên quan giữa truy vấn và
 * tài liệu, để {@link ResultRanker} hoán đổi được giữa
 * {@link TfIdfScorer} và {@link BM25Scorer} mà không phải sửa gì.
 *
 * <p>Đây là điều kiện cần để làm thí nghiệm ablation trong báo cáo: chạy
 * CÙNG một bộ truy vấn, CÙNG một chỉ mục, chỉ thay đúng mô hình tính điểm,
 * rồi so sánh các độ đo chất lượng. Nếu không tách được ra sau một giao
 * diện thì mọi so sánh đều lẫn thêm biến số khác và mất giá trị khoa học.
 */
public interface RelevanceScorer {

    /**
     * Tính điểm liên quan của {@code docId} đối với truy vấn.
     *
     * @param queryTermFrequency số lần mỗi term xuất hiện trong truy vấn
     * @param docId              tài liệu cần chấm điểm
     * @param index              chỉ mục đảo chứa posting list và thống kê corpus
     */
    double score(Map<String, Integer> queryTermFrequency, int docId, InvertedIndex index);

    /** Tên ngắn gọn của mô hình, dùng làm nhãn trong bảng kết quả đánh giá. */
    String name();
}
