package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TfIdfScorerTest {

    @Test
    void tfIsZeroForZeroFrequency() {
        assertEquals(0.0, TfIdfScorer.tf(0));
    }

    @Test
    void tfIsLogNormalized() {
        assertEquals(1.0, TfIdfScorer.tf(1), 1e-9);
        assertEquals(1 + Math.log10(4), TfIdfScorer.tf(4), 1e-9);
    }

    @Test
    void idfIsZeroWhenTermInEveryDocument() {
        assertEquals(0.0, TfIdfScorer.idf(10, 10), 1e-9);
    }

    @Test
    void idfIsHigherForRarerTerms() {
        double idfRare = TfIdfScorer.idf(100, 1);
        double idfCommon = TfIdfScorer.idf(100, 50);
        assertTrue(idfRare > idfCommon);
    }

    @Test
    void idfWithZeroDocumentFrequencyIsZero() {
        assertEquals(0.0, TfIdfScorer.idf(100, 0));
    }

    private WebDocument doc(int id, String title, String body) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setTitle(title);
        d.setBodyText(body);
        return d;
    }

    @Test
    void docContainingTermScoresHigherThanDocWithout() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "máy tính", "máy tính giá rẻ chất lượng tốt"));
        index.addDocument(doc(1, "nấu ăn", "công thức nấu ăn ngon mỗi ngày"));

        TfIdfScorer scorer = new TfIdfScorer();
        Map<String, Integer> query = Map.of("máy_tính", 1);

        double scoreWithTerm = scorer.score(query, 0, index);
        double scoreWithoutTerm = scorer.score(query, 1, index);

        assertTrue(scoreWithTerm > 0);
        assertEquals(0.0, scoreWithoutTerm);
    }

    @Test
    void nonExistentTermProducesZeroScore() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "test", "nội dung bất kỳ"));

        TfIdfScorer scorer = new TfIdfScorer();
        Map<String, Integer> query = Map.of("khong_ton_tai", 1);
        assertEquals(0.0, scorer.score(query, 0, index));
    }

    @Test
    void higherTermFrequencyInDocGivesHigherScore() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(doc(0, "", "internet internet internet mạng"));
        index.addDocument(doc(1, "", "internet mạng"));
        index.addDocument(doc(2, "", "chủ đề khác không liên quan"));

        TfIdfScorer scorer = new TfIdfScorer();
        Map<String, Integer> query = Map.of("internet", 1);

        double score0 = scorer.score(query, 0, index);
        double score1 = scorer.score(query, 1, index);
        assertTrue(score0 > score1, "Tai lieu chua term nhieu lan hon phai co diem cao hon");
    }
}
