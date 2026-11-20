package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BM25ScorerTest {

    private WebDocument doc(int id, String title, String body) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setUrl("https://test.local/" + id);
        d.setTitle(title);
        d.setBodyText(body);
        return d;
    }

    // ---------- IDF ----------

    @Test
    void idfMatchesHandComputedValue() {
        // N=10, df=5 -> ln(1 + (10-5+0.5)/(5+0.5)) = ln(1 + 5.5/5.5) = ln(2) = 0.6931472
        assertEquals(0.6931472, BM25Scorer.idf(10, 5), 1e-7);
    }

    @Test
    void idfStaysPositiveEvenForTermInEveryDocument() {
        // Đây là khác biệt then chốt so với TF-IDF: log10(N/df) = log10(10/10) = 0,
        // còn BM25 cho ln(1 + 0.5/10.5) = ln(1.047619) = 0.0465200 > 0.
        // Nhờ vậy term phổ biến chỉ bị GIẢM trọng số chứ không bị triệt tiêu hoàn toàn.
        double idf = BM25Scorer.idf(10, 10);
        assertEquals(0.0465200, idf, 1e-7);
        assertTrue(idf > 0, "IDF của BM25 không bao giờ được âm hay bằng 0");
    }

    @Test
    void rarerTermsGetHigherIdf() {
        assertTrue(BM25Scorer.idf(1000, 5) > BM25Scorer.idf(1000, 500),
                "Term hiếm phải mang nhiều thông tin phân biệt hơn");
    }

    @Test
    void idfIsZeroForUnknownTerm() {
        assertEquals(0.0, BM25Scorer.idf(100, 0), 1e-9);
    }

    // ---------- Bão hoà tần suất ----------

    @Test
    void termFrequencySaturates() {
        // Cùng một từ khoá, một tài liệu lặp 3 lần, một tài liệu lặp 30 lần.
        // BM25 phải cho tài liệu lặp nhiều điểm CAO HƠN nhưng KHÔNG cao gấp 10 lần —
        // đó chính là tính bão hoà, thứ mà tf = 1 + log10(f) của TF-IDF không có.
        // Dùng "máy tính" vì nó CÓ trong từ điển bigram nên được ghép thành
        // một token "máy_tính"; các cụm chưa có trong từ điển sẽ bị tách rời.
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Máy tính", "máy tính ".repeat(3) + "tin tức hôm nay"));
        index.addDocument(doc(1, "Máy tính", "máy tính ".repeat(30) + "tin tức hôm nay"));
        index.addDocument(doc(2, "Khác", "nấu ăn công thức món ngon gia đình"));

        BM25Scorer scorer = new BM25Scorer();
        Map<String, Integer> query = Map.of("máy_tính", 1);
        double few = scorer.score(query, 0, index);
        double many = scorer.score(query, 1, index);

        assertTrue(many > few, "Lặp nhiều hơn vẫn phải được điểm cao hơn");
        assertTrue(many < few * 3,
                "Lặp gấp 10 lần chỉ được tăng điểm rất hạn chế (bão hoà), thực tế "
                        + few + " -> " + many);
    }

    @Test
    void scoreIsZeroWhenTermAbsentFromDocument() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Bóng đá", "bóng_đá thể thao"));
        index.addDocument(doc(1, "Nấu ăn", "công thức món ngon"));

        assertEquals(0.0, new BM25Scorer().score(Map.of("bóng_đá", 1), 1, index), 1e-9);
    }

    @Test
    void emptyIndexScoresZero() {
        assertEquals(0.0, new BM25Scorer().score(Map.of("abc", 1), 0, new InvertedIndex()), 1e-9);
    }

    // ---------- Chuẩn hoá độ dài ----------

    @Test
    void shorterDocumentWinsWhenTermFrequencyIsEqual() {
        // Cùng số lần xuất hiện từ khoá, tài liệu NGẮN hơn phải được điểm cao hơn:
        // từ khoá chiếm tỷ trọng lớn hơn trong nội dung nên tài liệu tập trung hơn.
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Ngắn", "máy tính rất tốt"));
        index.addDocument(doc(1, "Dài", "máy tính " + "chữ đệm không liên quan gì cả ".repeat(30)));
        index.addDocument(doc(2, "Khác", "nấu ăn công thức"));

        BM25Scorer scorer = new BM25Scorer();
        Map<String, Integer> query = Map.of("máy_tính", 1);
        assertTrue(scorer.score(query, 0, index) > scorer.score(query, 1, index),
                "Tài liệu ngắn hơn với cùng tf phải xếp trên");
    }

    @Test
    void bParameterZeroDisablesLengthNormalisation() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Ngắn", "máy tính rất tốt"));
        index.addDocument(doc(1, "Dài", "máy tính " + "chữ đệm không liên quan gì cả ".repeat(30)));
        index.addDocument(doc(2, "Khác", "nấu ăn công thức"));

        Map<String, Integer> query = Map.of("máy_tính", 1);
        BM25Scorer noNorm = new BM25Scorer(BM25Scorer.DEFAULT_K1, 0.0);
        assertEquals(noNorm.score(query, 0, index), noNorm.score(query, 1, index), 1e-9,
                "Với b=0, độ dài tài liệu không được ảnh hưởng tới điểm");
    }

    // ---------- Tích hợp ----------

    @Test
    void isUsableAsRelevanceScorerAlongsideTfIdf() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Máy tính xách tay", "máy tính xách tay giá rẻ cho sinh viên"));
        index.addDocument(doc(1, "Công thức nấu ăn", "công thức nấu ăn ngon mỗi ngày"));

        Map<String, Integer> query = Map.of("máy_tính", 1);
        for (RelevanceScorer scorer : java.util.List.of(new BM25Scorer(), new TfIdfScorer())) {
            assertTrue(scorer.score(query, 0, index) > scorer.score(query, 1, index),
                    scorer.name() + " phải xếp tài liệu đúng chủ đề lên trên");
        }
    }

    @Test
    void averageDocLengthIsTrackedCorrectly() {
        InvertedIndex index = new InvertedIndex();
        assertEquals(0.0, index.getAverageDocLength(), 1e-9);

        index.addDocument(doc(0, "A", "một hai ba"));
        index.addDocument(doc(1, "B", "bốn năm sáu bảy tám chín"));

        double expected = (index.getDocLength(0) + index.getDocLength(1)) / 2.0;
        assertEquals(expected, index.getAverageDocLength(), 1e-9);
    }
}
