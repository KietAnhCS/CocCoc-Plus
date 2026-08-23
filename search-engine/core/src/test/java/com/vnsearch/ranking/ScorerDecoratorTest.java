package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.decorator.PageRankBoostScorer;
import com.vnsearch.ranking.decorator.TitleBoostScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiem thu cac Decorator ket hop tin hieu xep hang. */
class ScorerDecoratorTest {

    private InvertedIndex index;
    private Map<String, Integer> query;

    private static WebDocument doc(int id, String title, String body) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setUrl("https://example.vn/" + id);
        d.setTitle(title);
        d.setBodyText(body);
        return d;
    }

    @BeforeEach
    void setUp() {
        index = new InvertedIndex();
        // doc0 va doc1 GIONG HET nhau ve noi dung -> diem co so BANG nhau,
        // nen moi chenh lech quan sat duoc deu do dung Decorator gay ra.
        index.addDocument(doc(0, "Tin tức", "máy tính rất tốt"));
        index.addDocument(doc(1, "Tin tức", "máy tính rất tốt"));
        index.addDocument(doc(2, "Chủ đề khác", "nấu ăn ngon"));
        // doc3 giong noi dung nhung co tu khoa trong TIEU DE — dung cho title boost.
        index.addDocument(doc(3, "Máy tính giá tốt", "máy tính rất tốt"));
        query = Map.of("máy_tính", 1);
    }

    @Test
    void pageRankBoostRaisesScoreOfMoreAuthoritativeDoc() {
        Map<Integer, Double> pageRank = Map.of(0, 0.0001, 1, 0.0100);
        RelevanceScorer base = new TfIdfScorer();
        RelevanceScorer boosted = new PageRankBoostScorer(base, pageRank, 0.5);

        double baseDoc0 = base.score(query, 0, index);
        double baseDoc1 = base.score(query, 1, index);
        assertEquals(baseDoc0, baseDoc1, 1e-9, "Hai tai lieu co diem co so BANG nhau");

        assertTrue(boosted.score(query, 1, index) > boosted.score(query, 0, index),
                "Tai lieu co PageRank cao hon phai duoc xep tren");
    }

    @Test
    void pageRankBoostIsInvariantToBaseScorerScale() {
        // Day la diem mau chot: phep NHAN bat bien voi thang do cua scorer co so,
        // nen doi TF-IDF sang BM25 KHONG phai chinh lai trong so.
        Map<Integer, Double> pageRank = Map.of(0, 0.0001, 1, 0.0100);

        RelevanceScorer tfidf = new PageRankBoostScorer(new TfIdfScorer(), pageRank, 0.5);
        RelevanceScorer bm25 = new PageRankBoostScorer(new BM25Scorer(), pageRank, 0.5);

        double tfidfRatio = tfidf.score(query, 1, index) / tfidf.score(query, 0, index);
        double bm25Ratio = bm25.score(query, 1, index) / bm25.score(query, 0, index);

        assertEquals(tfidfRatio, bm25Ratio, 1e-9,
                "Ty le tang do PageRank phai GIONG NHAU du thang diem co so khac han");
    }

    @Test
    void zeroBaseScoreStaysZero() {
        Map<Integer, Double> pageRank = Map.of(2, 0.9);
        RelevanceScorer boosted = new PageRankBoostScorer(new TfIdfScorer(), pageRank, 1.0);
        assertEquals(0.0, boosted.score(query, 2, index),
                "Uy tin cao khong duoc cuu tai lieu hoan toan khong lien quan");
    }

    @Test
    void titleBoostRaisesScoreOfMatchingTitle() {
        RelevanceScorer base = new TfIdfScorer();
        RelevanceScorer boosted = new TitleBoostScorer(base, 0.5);

        // doc3 co tu khoa trong TIEU DE, doc0 thi khong.
        double ratioDoc3 = boosted.score(query, 3, index) / base.score(query, 3, index);
        double ratioDoc0 = boosted.score(query, 0, index) / base.score(query, 0, index);

        assertTrue(ratioDoc3 > ratioDoc0,
                "Tai lieu co tu khoa trong tieu de phai duoc thuong nhieu hon: "
                        + ratioDoc3 + " vs " + ratioDoc0);
        assertEquals(1.0, ratioDoc0, 1e-12, "Tieu de khong khop -> khong duoc thuong gi");
    }

    @Test
    void zeroWeightIsIdentity() {
        RelevanceScorer base = new TfIdfScorer();
        assertEquals(base.score(query, 1, index),
                new TitleBoostScorer(base, 0.0).score(query, 1, index), 1e-12);
        assertEquals(base.score(query, 1, index),
                new PageRankBoostScorer(base, Map.of(1, 0.5), 0.0).score(query, 1, index), 1e-12);
    }

    @Test
    void decoratorsComposeAndNamesChain() {
        RelevanceScorer scorer = new TitleBoostScorer(
                new PageRankBoostScorer(new BM25Scorer(), Map.of(1, 0.5), 0.30), 0.10);

        String name = scorer.name();
        assertTrue(name.contains("BM25"), name);
        assertTrue(name.contains("PR"), name);
        assertTrue(name.contains("title"), name);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new TitleBoostScorer(null, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new TitleBoostScorer(new TfIdfScorer(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new PageRankBoostScorer(new TfIdfScorer(), Map.of(), -0.5));
    }

    @Test
    void factoryBuildsConfiguredChain() {
        ScorerFactory factory = new ScorerFactory("bm25", 1.2, 0.75, 0.3, 0.1);
        RelevanceScorer scorer = factory.create(Map.of(1, 0.5));
        assertTrue(scorer.name().startsWith("BM25"), scorer.name());
        assertTrue(scorer.name().contains("PR"), scorer.name());
        assertTrue(scorer.name().contains("title"), scorer.name());
    }

    @Test
    void factoryRejectsUnknownScorerType() {
        ScorerFactory factory = new ScorerFactory("khong-ton-tai", 1.2, 0.75, 0.0, 0.0);
        assertThrows(IllegalArgumentException.class, factory::createBase);
    }
}
