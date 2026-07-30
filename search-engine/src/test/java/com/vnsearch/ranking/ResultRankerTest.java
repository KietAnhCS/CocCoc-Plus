package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultRankerTest {

    private WebDocument doc(int id, String title, String body) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setTitle(title);
        d.setBodyText(body);
        return d;
    }

    @Test
    void higherPageRankWinsWhenTfIdfIsEqual() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Trang A", "máy tính giá rẻ"));
        index.addDocument(doc(1, "Trang B", "máy tính giá rẻ"));

        Map<String, Integer> query = Map.of("máy_tính", 1);
        Map<Integer, Double> pageRank = Map.of(0, 0.1, 1, 0.9);

        ResultRanker ranker = new ResultRanker();
        List<ResultRanker.RankedResult> results = ranker.rank(
                List.of(0, 1), query, index, new TfIdfScorer(), pageRank, 10);

        assertEquals(1, results.get(0).document().getDocId(), "Doc voi PageRank cao hon phai xep truoc");
    }

    @Test
    void topNLimitsResultCount() {
        InvertedIndex index = new InvertedIndex();
        for (int i = 0; i < 5; i++) {
            index.addDocument(doc(i, "Tieu de " + i, "noi dung chung chung " + i));
        }
        Map<String, Integer> query = Map.of("noi_dung", 1);
        Map<Integer, Double> pageRank = Map.of();

        ResultRanker ranker = new ResultRanker();
        List<ResultRanker.RankedResult> results = ranker.rank(
                List.of(0, 1, 2, 3, 4), query, index, new TfIdfScorer(), pageRank, 3);

        assertEquals(3, results.size());
    }

    @Test
    void resultsAreSortedDescendingByFinalScore() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "A", "internet mạng máy tính"));
        index.addDocument(doc(1, "B", "internet"));
        index.addDocument(doc(2, "C", "không liên quan gì cả"));

        Map<String, Integer> query = Map.of("internet", 1);
        Map<Integer, Double> pageRank = Map.of(0, 0.5, 1, 0.3, 2, 0.9);

        ResultRanker ranker = new ResultRanker();
        List<ResultRanker.RankedResult> results = ranker.rank(
                List.of(0, 1, 2), query, index, new TfIdfScorer(), pageRank, 10);

        for (int i = 0; i + 1 < results.size(); i++) {
            assertTrue(results.get(i).finalScore() >= results.get(i + 1).finalScore());
        }
    }

    @Test
    void snippetHighlightsMatchingKeywords() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "Tieu de", "Day la mot bai viet ve internet va cong nghe hien dai"));

        Map<String, Integer> query = Map.of("internet", 1);
        ResultRanker ranker = new ResultRanker();
        List<ResultRanker.RankedResult> results = ranker.rank(List.of(0), query, index, new TfIdfScorer(), Map.of(), 1);

        String snippet = results.get(0).snippet();
        assertTrue(snippet.contains("<mark>internet</mark>"));
    }

    @Test
    void emptyBodyProducesEmptySnippet() {
        InvertedIndex index = new InvertedIndex();
        WebDocument d = doc(0, "Tieu de", "");
        index.addDocument(d);

        ResultRanker ranker = new ResultRanker();
        List<ResultRanker.RankedResult> results = ranker.rank(
                List.of(0), Map.of("x", 1), index, new TfIdfScorer(), Map.of(), 1);
        assertEquals("", results.get(0).snippet());
    }

    @Test
    void snippetDoesNotHighlightWordsDifferingOnlyByDiacritics() {
        // Lỗi đã gặp thật khi demo: tìm "ngân hàng" thì chữ "ngàn" trong
        // "cắt giảm cả ngàn nhân sự" cũng bị bôi sáng, vì cả "ngân" lẫn
        // "ngàn" khi bỏ dấu đều thành "ngan".
        com.vnsearch.index.InvertedIndex index = new com.vnsearch.index.InvertedIndex();
        com.vnsearch.model.WebDocument doc = new com.vnsearch.model.WebDocument();
        doc.setDocId(0);
        doc.setUrl("https://test.local/0");
        doc.setTitle("Tin ngân hàng");
        doc.setBodyText("Nhiều ngân hàng cắt giảm cả ngàn nhân sự trong năm nay");
        index.addDocument(doc);

        String snippet = new ResultRanker().rank(
                List.of(0), Map.of("ngân", 1, "hàng", 1), index, new TfIdfScorer(), Map.of(), 1)
                .get(0).snippet();

        assertTrue(snippet.contains("<mark>ngân</mark>"), "Phải bôi sáng đúng từ 'ngân'");
        assertFalse(snippet.contains("<mark>ngàn</mark>"),
                "KHÔNG được bôi sáng 'ngàn' khi truy vấn là 'ngân': " + snippet);
    }

    @Test
    void snippetStillHighlightsWhenUserTypesWithoutDiacritics() {
        // Chiều ngược lại phải giữ nguyên: người gõ không dấu vẫn phải thấy
        // từ có dấu được bôi sáng, nếu không thì bản sửa trên đã đi quá đà.
        com.vnsearch.index.InvertedIndex index = new com.vnsearch.index.InvertedIndex();
        com.vnsearch.model.WebDocument doc = new com.vnsearch.model.WebDocument();
        doc.setDocId(0);
        doc.setUrl("https://test.local/0");
        doc.setTitle("Máy tính");
        doc.setBodyText("Chiếc máy tính này rất tốt cho sinh viên và dân văn phòng");
        index.addDocument(doc);

        String snippet = new ResultRanker().rank(
                List.of(0), Map.of("may_tinh", 1), index, new TfIdfScorer(), Map.of(), 1)
                .get(0).snippet();

        assertTrue(snippet.contains("<mark>máy</mark>"),
                "Gõ không dấu vẫn phải bôi sáng được từ có dấu: " + snippet);
    }
}
