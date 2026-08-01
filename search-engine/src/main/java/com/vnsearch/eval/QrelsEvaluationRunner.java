package com.vnsearch.eval;

import com.vnsearch.crawler.ContentStorage;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.BM25Scorer;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.TfIdfScorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bổ sung cho {@link EvaluationRunner}: đánh giá bằng <b>nhãn liên quan do
 * người gán</b> thay vì ground truth sinh tự động.
 *
 * <p>Cần cả hai vì mỗi cách bù khuyết điểm của cách kia. Known-item search
 * cho số liệu khách quan và tái lập được nhưng chỉ có đúng một tài liệu
 * đúng cho mỗi truy vấn, nên thiên vị chống lại PageRank và không đo được
 * truy vấn khám phá. Nhãn người gán cho phép dùng nDCG và MAP với mức độ
 * liên quan nhiều bậc trên các truy vấn tự nhiên, đổi lại tốn công và mang
 * tính chủ quan.
 *
 * <p>Hai chế độ:
 * <pre>
 *   # 1. Sinh pool cần gán nhãn
 *   mvnw compile exec:java -Dexec.mainClass=com.vnsearch.eval.QrelsEvaluationRunner \
 *        -Dexec.args="pool data/crawled-multi.json"
 *
 *   # 2. Sau khi đã điền nhãn vào data/eval/qrels.json
 *   mvnw compile exec:java -Dexec.mainClass=com.vnsearch.eval.QrelsEvaluationRunner \
 *        -Dexec.args="eval data/crawled-multi.json"
 * </pre>
 */
public class QrelsEvaluationRunner {

    private static final String POOL_PATH = "data/eval/pool-to-label.json";
    private static final String QRELS_PATH = "data/eval/qrels.json";
    private static final int TOP_N = 10;

    /**
     * Bộ truy vấn tự nhiên, mô phỏng cách người Việt thật gõ khi tìm tin
     * tức. Cố ý trộn cả truy vấn một từ rộng ("thể thao"), truy vấn nhiều
     * từ hẹp hơn ("giá vàng hôm nay"), và cả dạng gõ không dấu — vốn rất
     * phổ biến trên bàn phím quốc tế và là thứ chỉ mục kép có dấu/không dấu
     * được thiết kế để xử lý.
     */
    private static final List<String> NATURAL_QUERIES = List.of(
            "công nghệ", "thể thao", "kinh tế", "giáo dục", "y tế",
            "giao thông", "du lịch", "chứng khoán", "bất động sản", "thời tiết",
            "ô tô", "điện thoại", "trí tuệ nhân tạo", "sức khỏe", "âm nhạc",
            "bóng đá Việt Nam", "giá vàng hôm nay", "tuyển sinh đại học",
            "dịch bệnh", "môi trường", "năng lượng", "xuất khẩu",
            "cong nghe", "the thao", "kinh te", "giao duc",
            "học sinh", "doanh nghiệp", "ngân hàng", "phim ảnh");

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "pool";
        String corpusPath = args.length > 1 ? args[1] : "data/crawled-multi.json";

        System.out.println("Nap corpus " + corpusPath + " ...");
        List<WebDocument> docs = ContentStorage.loadFromJson(corpusPath);
        InvertedIndex index = buildIndex(docs);
        Map<Integer, Double> pageRank =
                new PageRankService().computePageRank(index.getAllDocuments()).scores();
        System.out.printf("  %d tai lieu, %d term%n", docs.size(), index.getTermCount());

        List<EvaluationHarness.RankingConfig> configs = poolConfigs(pageRank);

        if ("pool".equalsIgnoreCase(mode)) {
            PoolBuilder builder = new PoolBuilder(index, pageRank);
            List<PoolBuilder.QueryPool> pools = builder.buildPools(NATURAL_QUERIES, configs);
            PoolBuilder.writePools(pools, POOL_PATH);

            System.out.println();
            System.out.println(PoolBuilder.summarise(pools));
            System.out.println("Da ghi pool vao " + Path.of(POOL_PATH).toAbsolutePath().normalize());
            System.out.println();
            System.out.println("BUOC TIEP THEO:");
            System.out.println("  1. Mo file tren, dien truong \"relevance\" cho tung muc:");
            System.out.println("       0 = khong lien quan, 1 = lien quan, 2 = rat lien quan");
            System.out.println("  2. Luu lai thanh " + QRELS_PATH);
            System.out.println("  3. Chay lai lop nay voi tham so \"eval\"");
            return;
        }

        Path qrelsPath = Path.of(QRELS_PATH);
        if (!Files.exists(qrelsPath)) {
            System.err.println("Chua co " + QRELS_PATH + " - hay chay che do \"pool\" va gan nhan truoc.");
            return;
        }

        Map<String, Map<String, Integer>> qrels = PoolBuilder.loadQrels(QRELS_PATH);
        EvaluationHarness harness = new EvaluationHarness(index, pageRank);

        StringBuilder report = new StringBuilder();
        report.append("# Đánh giá bằng nhãn liên quan do người gán\n\n");
        report.append("> Sinh tự động bởi `com.vnsearch.eval.QrelsEvaluationRunner`.\n\n");
        report.append("Bổ sung cho `EVALUATION.md` (dùng known-item search). Nhãn được gán tay\n");
        report.append("theo phương pháp pooling của TREC: chỉ gán nhãn phần hợp của top-")
                .append(PoolBuilder.POOL_DEPTH).append(" kết quả\n");
        report.append("từ nhiều cấu hình xếp hạng khác nhau, thay vì gán nhãn toàn bộ corpus.\n\n");
        report.append("Số truy vấn có nhãn: **").append(qrels.size()).append("**\n\n");
        report.append("| Cấu hình | MAP | nDCG@10 | P@5 | P@10 | MRR |\n");
        report.append("|---|---|---|---|---|---|\n");

        for (EvaluationHarness.RankingConfig config : configs) {
            List<Double> aps = new ArrayList<>();
            List<Double> ndcgs = new ArrayList<>();
            List<Double> p5s = new ArrayList<>();
            List<Double> p10s = new ArrayList<>();
            List<Double> rrs = new ArrayList<>();

            for (Map.Entry<String, Map<String, Integer>> entry : qrels.entrySet()) {
                List<String> ranked = harness.search(entry.getKey(), config, TOP_N);
                Map<String, Integer> judgments = entry.getValue();
                aps.add(EvaluationMetrics.averagePrecision(ranked, judgments));
                ndcgs.add(EvaluationMetrics.ndcgAtK(ranked, judgments, 10));
                p5s.add(EvaluationMetrics.precisionAtK(ranked, judgments, 5));
                p10s.add(EvaluationMetrics.precisionAtK(ranked, judgments, 10));
                rrs.add(EvaluationMetrics.reciprocalRank(ranked, judgments));
            }
            report.append(String.format(Locale.US, "| %s | %.4f | %.4f | %.4f | %.4f | %.4f |%n",
                    config.label(),
                    EvaluationMetrics.meanAveragePrecision(aps),
                    EvaluationMetrics.mean(ndcgs),
                    EvaluationMetrics.mean(p5s),
                    EvaluationMetrics.mean(p10s),
                    EvaluationMetrics.meanReciprocalRank(rrs)));
        }

        report.append("\n> Lưu ý: nDCG và MAP ở đây tính trên pool đã gán nhãn. Tài liệu nằm\n");
        report.append("> ngoài pool mặc định coi là không liên quan — đúng quy ước TREC, nhưng\n");
        report.append("> nghĩa là recall tuyệt đối không đo được, chỉ so sánh tương đối giữa\n");
        report.append("> các cấu hình mới có ý nghĩa.\n");

        Path out = Path.of("../docs/EVALUATION-QRELS.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println();
        System.out.println(report);
        System.out.println("Da ghi bao cao vao " + out.toAbsolutePath().normalize());
    }

    private static List<EvaluationHarness.RankingConfig> poolConfigs(
            Map<Integer, Double> pageRankScores) {
        TfIdfScorer tfidf = new TfIdfScorer();
        BM25Scorer bm25 = new BM25Scorer();
        return List.of(
                EvaluationHarness.RankingConfig.of("TF-IDF + PR + title (đang dùng)", tfidf, pageRankScores, 0.3, 0.1),
                EvaluationHarness.RankingConfig.of("TF-IDF thuần", tfidf, pageRankScores, 0.0, 0.0),
                EvaluationHarness.RankingConfig.of("BM25 thuần", bm25, pageRankScores, 0.0, 0.0),
                EvaluationHarness.RankingConfig.of("BM25 + PR + title", bm25, pageRankScores, 0.3, 0.1));
    }

    private static InvertedIndex buildIndex(List<WebDocument> docs) {
        List<WebDocument> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.comparingInt(WebDocument::getDocId));
        InvertedIndex index = new InvertedIndex();
        for (WebDocument doc : sorted) {
            index.addDocument(doc);
        }
        return index;
    }
}
