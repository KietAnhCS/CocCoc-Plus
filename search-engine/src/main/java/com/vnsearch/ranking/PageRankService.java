package com.vnsearch.ranking;

import com.vnsearch.datastructure.SparseMatrix;
import com.vnsearch.model.WebDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PageRank tu cai dat bang power iteration tren {@link SparseMatrix}.
 *
 * <p>Cong thuc: {@code PR(j) = (1-d)/N + d * [ sum_{i lien ket toi j} PR(i)/outDegree(i) + danglingMass/N ]},
 * voi {@code d = 0.85} (damping factor).
 *
 * <p>Xay dung ma tran: khac voi dinh nghia toan hoc "M[i][j] = 1/outDegree(i)
 * neu i lien ket toi j" (roi phai nhan M^T * PR), ta luu TRUC TIEP ma tran
 * o dang "hang j = danh sach cac nguon i tro toi j, kem trong so 1/outDegree(i)"
 * — tuc SparseMatrix.set(j, i, 1/outDegree(i)). Nho vay
 * {@link SparseMatrix#multiply} tinh dung {@code result[j] = sum_i M[j][i]*PR[i]}
 * chinh la {@code M^T * PR} ma KHONG can thao tac transpose rieng — chi la
 * cach ta chon "chieu luu" cua ma tran tu dau.
 *
 * <p>Dangling node (trang khong co outlink NAO tro ve mot trang khac
 * TRONG CORPUS da crawl): toan bo "khoi luong" PR cua no duoc phan phoi
 * DEU cho tat ca N trang (thay vi bien mat khoi he thong, vi pham tinh
 * chat tong PR = 1).
 *
 * <p>Dieu kien dung: {@code ||PR_new - PR_old||_1 < 1e-6} HOAC du 100
 * vong lap (tuy dieu kien nao den truoc). So vong lap thuc te duoc log ra
 * console va tra ve trong {@link PageRankResult#iterations()} de dua vao
 * bao cao.
 *
 * <p>Do phuc tap thoi gian: moi vong lap la O(nnz) (nhan ma tran thua) +
 * O(N) (xu ly dangling/teleport) -&gt; tong O(iterations * (nnz + N)).
 */
public class PageRankService {

    private static final Logger log = LoggerFactory.getLogger(PageRankService.class);

    private static final double DAMPING = 0.85;
    private static final double EPSILON = 1e-6;
    private static final int MAX_ITERATIONS = 100;

    public record PageRankResult(Map<Integer, Double> scores, int iterations) {
    }

    public PageRankResult computePageRank(Map<Integer, WebDocument> documents) {
        List<Integer> docIds = new ArrayList<>(documents.keySet());
        docIds.sort(Integer::compareTo);
        int n = docIds.size();
        if (n == 0) {
            return new PageRankResult(Map.of(), 0);
        }

        Map<Integer, Integer> docIdToIndex = new HashMap<>();
        Map<String, Integer> urlToIndex = new HashMap<>();
        for (int idx = 0; idx < n; idx++) {
            int docId = docIds.get(idx);
            docIdToIndex.put(docId, idx);
            urlToIndex.put(documents.get(docId).getUrl(), idx);
        }

        int[] outDegree = new int[n];
        for (int idx = 0; idx < n; idx++) {
            WebDocument doc = documents.get(docIds.get(idx));
            for (String outlink : doc.getOutlinks()) {
                Integer targetIdx = urlToIndex.get(outlink);
                if (targetIdx != null && targetIdx != idx) {
                    outDegree[idx]++;
                }
            }
        }

        SparseMatrix incoming = new SparseMatrix(n, n);
        boolean[] dangling = new boolean[n];
        for (int idx = 0; idx < n; idx++) {
            if (outDegree[idx] == 0) {
                dangling[idx] = true;
                continue;
            }
            double weight = 1.0 / outDegree[idx];
            WebDocument doc = documents.get(docIds.get(idx));
            for (String outlink : doc.getOutlinks()) {
                Integer targetIdx = urlToIndex.get(outlink);
                if (targetIdx != null && targetIdx != idx) {
                    incoming.set(targetIdx, idx, weight);
                }
            }
        }

        // "Dong bang" sang CSR truoc khi lap: power iteration chay hang chuc
        // vong tren CUNG mot ma tran, nen tra chi phi chuyen doi MOT lan de doi
        // lay cuc bo cache tot hon va it bo nho hon cho tat ca cac vong sau.
        incoming.freeze();

        double[] pr = new double[n];
        for (int i = 0; i < n; i++) {
            pr[i] = 1.0 / n;
        }

        int iteration = 0;
        double diff;
        double teleport = (1 - DAMPING) / n;
        do {
            double danglingSum = 0.0;
            for (int i = 0; i < n; i++) {
                if (dangling[i]) {
                    danglingSum += pr[i];
                }
            }
            double danglingContribution = DAMPING * danglingSum / n;

            double[] linkContribution = incoming.multiply(pr);
            double[] newPr = new double[n];
            diff = 0.0;
            for (int j = 0; j < n; j++) {
                newPr[j] = teleport + DAMPING * linkContribution[j] + danglingContribution;
                diff += Math.abs(newPr[j] - pr[j]);
            }
            pr = newPr;
            iteration++;
        } while (diff >= EPSILON && iteration < MAX_ITERATIONS);

        // Logger chu khong phai System.out: dong nay chay trong tien trinh may
        // chu, nen no can dau thoi gian, muc do va loc duoc nhu moi dong log
        // khac. In thang ra stdout thi no khong co gi ca, va o profile prod
        // (log dang JSON) no lot ra ngoai dinh dang.
        log.info("PageRank hoi tu sau {} vong lap (diff cuoi = {}, nnz = {}, do thua = {}%)",
                iteration, String.format("%.2e", diff), incoming.nnz(),
                String.format("%.4f", incoming.density() * 100));

        Map<Integer, Double> result = new HashMap<>();
        for (int idx = 0; idx < n; idx++) {
            result.put(docIds.get(idx), pr[idx]);
        }
        return new PageRankResult(result, iteration);
    }

    /**
     * Demo minh hoa nho tren do thi 6 node tu tao, so voi tinh tay:
     * A -> B, C; B -> C; C -> A; D -> C; E -> (A qua "khong doc lap"),
     * dung cau truc don gian de kiem chung bang tay trong bao cao.
     */
    public static void main(String[] args) {
        Map<Integer, WebDocument> docs = new HashMap<>();
        String[] urls = {"A", "B", "C", "D", "E", "F"};
        int[][] links = {
                {0, 1}, {0, 2}, // A -> B, C
                {1, 2},          // B -> C
                {2, 0},          // C -> A
                {3, 2},          // D -> C
                // E (index 4) khong co outlink -> dangling node
        };
        for (int i = 0; i < urls.length; i++) {
            WebDocument doc = new WebDocument();
            doc.setDocId(i);
            doc.setUrl(urls[i]);
            doc.setOutlinks(new ArrayList<>());
            docs.put(i, doc);
        }
        for (int[] link : links) {
            docs.get(link[0]).getOutlinks().add(urls[link[1]]);
        }
        // F (index 5) chi duoc tro toi, khong tro di dau (dangling) -> khong them outlink

        PageRankService service = new PageRankService();
        PageRankResult result = service.computePageRank(docs);

        System.out.println("So vong lap hoi tu: " + result.iterations());
        double sum = 0;
        for (int i = 0; i < urls.length; i++) {
            double score = result.scores().get(i);
            sum += score;
            System.out.printf("PR(%s) = %.5f%n", urls[i], score);
        }
        System.out.printf("Tong PR (phai xap xi 1.0) = %.5f%n", sum);
    }
}
