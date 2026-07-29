package com.vnsearch.ranking;

import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageRankServiceTest {

    private WebDocument doc(int id, String url, List<String> outlinks) {
        WebDocument d = new WebDocument();
        d.setDocId(id);
        d.setUrl(url);
        d.setOutlinks(outlinks);
        return d;
    }

    @Test
    void emptyCorpusReturnsEmptyResult() {
        PageRankService service = new PageRankService();
        PageRankService.PageRankResult result = service.computePageRank(Map.of());
        assertTrue(result.scores().isEmpty());
    }

    @Test
    void symmetricCycleConvergesToUniformDistribution() {
        // A -> B -> C -> A : chu trinh doi xung hoan toan, ket qua ly thuyet la PR bang nhau = 1/3 moi node.
        Map<Integer, WebDocument> docs = new HashMap<>();
        docs.put(0, doc(0, "A", List.of("B")));
        docs.put(1, doc(1, "B", List.of("C")));
        docs.put(2, doc(2, "C", List.of("A")));

        PageRankService service = new PageRankService();
        PageRankService.PageRankResult result = service.computePageRank(docs);

        for (int i = 0; i < 3; i++) {
            assertEquals(1.0 / 3, result.scores().get(i), 1e-4,
                    "Chu trinh doi xung phai cho PageRank bang nhau tren moi node");
        }
    }

    @Test
    void scoresSumToApproximatelyOne() {
        Map<Integer, WebDocument> docs = new HashMap<>();
        docs.put(0, doc(0, "A", List.of("B", "C")));
        docs.put(1, doc(1, "B", List.of("C")));
        docs.put(2, doc(2, "C", List.of("A")));
        docs.put(3, doc(3, "D", List.of("C"))); // D chi tro di, khong ai tro toi D
        docs.put(4, doc(4, "E", List.of()));    // dangling node

        PageRankService service = new PageRankService();
        PageRankService.PageRankResult result = service.computePageRank(docs);

        double sum = result.scores().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-6, "Tong PageRank phai xap xi 1.0 (bao toan xac suat)");
    }

    @Test
    void danglingNodesGetEqualLowScore() {
        Map<Integer, WebDocument> docs = new HashMap<>();
        docs.put(0, doc(0, "A", List.of("B")));
        docs.put(1, doc(1, "B", List.of("A")));
        docs.put(2, doc(2, "C", List.of())); // dangling, khong ai tro toi
        docs.put(3, doc(3, "D", List.of())); // dangling, khong ai tro toi

        PageRankService service = new PageRankService();
        PageRankService.PageRankResult result = service.computePageRank(docs);

        assertEquals(result.scores().get(2), result.scores().get(3), 1e-9,
                "2 dangling node khong nhan lien ket nao phai co PageRank bang nhau");
    }

    @Test
    void moreIncomingLinksGivesHigherPageRank() {
        // C nhan lien ket tu A, B, D; A chi nhan tu C; B chi nhan tu A.
        Map<Integer, WebDocument> docs = new HashMap<>();
        docs.put(0, doc(0, "A", List.of("B", "C")));
        docs.put(1, doc(1, "B", List.of("C")));
        docs.put(2, doc(2, "C", List.of("A")));
        docs.put(3, doc(3, "D", List.of("C")));

        PageRankService service = new PageRankService();
        PageRankService.PageRankResult result = service.computePageRank(docs);

        assertTrue(result.scores().get(2) > result.scores().get(0),
                "C nhan nhieu lien ket den hon A nen phai co PageRank cao hon");
        assertTrue(result.scores().get(0) > result.scores().get(1),
                "A duoc C (manh) tro toi, B chi duoc A tro toi -> A > B");
    }

    @Test
    void convergesWithinMaxIterations() {
        Map<Integer, WebDocument> docs = new HashMap<>();
        docs.put(0, doc(0, "A", List.of("B")));
        docs.put(1, doc(1, "B", List.of("A")));

        PageRankService service = new PageRankService();
        PageRankService.PageRankResult result = service.computePageRank(docs);
        assertTrue(result.iterations() <= 100);
        assertTrue(result.iterations() > 0);
    }
}
