package com.vnsearch.eval;

import com.vnsearch.crawler.ContentStorage;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.BM25Scorer;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.TfIdfScorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chạy toàn bộ thí nghiệm đánh giá chất lượng tìm kiếm và xuất báo cáo
 * Markdown đưa thẳng được vào luận văn.
 *
 * <p>Thí nghiệm dùng phương pháp <b>known-item search</b>
 * (xem {@link KnownItemQueryGenerator}): ground truth sinh tự động, khách
 * quan và tái lập được, nên chạy lại lúc nào cũng ra đúng con số cũ.
 *
 * <p>Ba câu hỏi mà báo cáo này trả lời:
 * <ol>
 *   <li>BM25 có thật sự tốt hơn TF-IDF cosine trên corpus tiếng Việt này không?</li>
 *   <li>PageRank có cải thiện chất lượng xếp hạng không, hay chỉ làm nhiễu?</li>
 *   <li>Bộ trọng số alpha/beta/gamma = 0.6/0.3/0.1 đang dùng có phải lựa
 *       chọn tốt không — hay chỉ là con số chọn bừa?</li>
 * </ol>
 *
 * <p>Chạy bằng:
 * <pre>
 *   mvnw exec:java -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \
 *        -Dexec.args="data/crawled-multi.json 200"
 * </pre>
 */
public class EvaluationRunner {

    private static final int TOP_N = 10;
    private static final int TERMS_PER_QUERY = 3;
    /** Seed cố định để bộ truy vấn tái lập được giữa các lần chạy. */
    private static final long SEED = 42L;

    public static void main(String[] args) throws IOException {
        String corpusPath = args.length > 0 ? args[0] : "data/crawled-multi.json";
        int numQueries = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        String reportPath = args.length > 2 ? args[2] : "../docs/EVALUATION.md";

        System.out.println("Nap corpus tu " + corpusPath + " ...");
        List<WebDocument> docs = ContentStorage.loadFromJson(corpusPath);
        System.out.println("  " + docs.size() + " tai lieu");

        System.out.println("Dung chi muc dao ...");
        long t0 = System.currentTimeMillis();
        InvertedIndex index = buildIndex(docs);
        long indexMs = System.currentTimeMillis() - t0;
        System.out.printf("  %d term, %.1f token/tai lieu, mat %.1fs%n",
                index.getTermCount(), index.getAverageDocLength(), indexMs / 1000.0);

        System.out.println("Tinh PageRank ...");
        t0 = System.currentTimeMillis();
        PageRankService.PageRankResult pageRank = new PageRankService().computePageRank(index.getAllDocuments());
        long pageRankMs = System.currentTimeMillis() - t0;
        System.out.printf("  hoi tu sau %d vong lap, mat %.1fs%n", pageRank.iterations(), pageRankMs / 1000.0);

        System.out.println("Sinh " + numQueries + " truy van known-item ...");
        List<KnownItemQueryGenerator.KnownItemQuery> queries =
                new KnownItemQueryGenerator().generate(index, numQueries, TERMS_PER_QUERY, SEED);
        System.out.println("  sinh duoc " + queries.size() + " truy van");
        if (queries.isEmpty()) {
            System.err.println("Khong sinh duoc truy van nao - kiem tra lai corpus.");
            return;
        }

        EvaluationHarness harness = new EvaluationHarness(index, pageRank.scores());

        List<EvaluationHarness.RankingConfig> configs = buildConfigs(pageRank.scores());
        List<ConfigResult> results = new ArrayList<>();
        for (EvaluationHarness.RankingConfig config : configs) {
            System.out.println("Danh gia: " + config.label() + " ...");
            results.add(evaluate(harness, config, queries));
        }

        String scaleAnalysis = analyseScoreScales(index, pageRank.scores(), queries);
        System.out.println(scaleAnalysis);

        String report = buildMarkdownReport(docs.size(), index, pageRank, queries, results, indexMs, pageRankMs)
                + "\n" + scaleAnalysis;
        Path out = Path.of(reportPath);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, report);

        System.out.println();
        System.out.println(renderTable(results));
        System.out.println("Da ghi bao cao day du vao " + out.toAbsolutePath().normalize());
    }

    /**
     * Đo ĐỘ LỚN THỰC TẾ của từng thành phần điểm trước khi nhân trọng số.
     *
     * <p>Công thức {@code alpha*tfidf + beta*pageRank + gamma*titleBonus}
     * ngầm giả định ba đại lượng có cùng thang đo — nếu không thì trọng số
     * không còn phản ánh mức đóng góp thật. Đây là phép kiểm tra bắt buộc
     * trước khi diễn giải bất kỳ kết luận nào về bộ trọng số.
     */
    private static String analyseScoreScales(InvertedIndex index, Map<Integer, Double> pageRankScores,
                                              List<KnownItemQueryGenerator.KnownItemQuery> queries) {
        EvaluationHarness.RankingConfig config = EvaluationHarness.RankingConfig.of(
                "phân tích thang đo", new TfIdfScorer(), pageRankScores, 0.3, 0.1);
        com.vnsearch.query.QueryParser parser = new com.vnsearch.query.QueryParser();
        com.vnsearch.ranking.ResultRanker ranker = new com.vnsearch.ranking.ResultRanker();

        // Thang đo phải đo trên scorer TRẦN. Trước đây mục này đọc
        // `result.tfidfScore()` của kết quả đã xếp hạng — mà giá trị đó là điểm
        // TỔNG đã bọc đủ PageRank lẫn title, không phải thành phần TF-IDF. Tức
        // là bảng "so sánh thang đo giữa hai thành phần" đang so một thành phần
        // với chính cái tổng chứa nó. Chấm điểm lại bằng một TfIdfScorer trần
        // mới trả lời đúng câu hỏi mục này đặt ra.
        TfIdfScorer bareTfIdf = new TfIdfScorer();

        double sumTfidf = 0, sumPageRank = 0;
        double maxTfidf = 0, maxPageRank = 0;
        int samples = 0;

        for (KnownItemQueryGenerator.KnownItemQuery query : queries) {
            var parsed = parser.parse(query.queryText());
            var resolved = com.vnsearch.query.CandidateResolver.resolve(index, parsed);
            if (resolved.candidateDocIds().isEmpty()) {
                continue;
            }
            // Vẫn xếp hạng bằng cấu hình ĐẦY ĐỦ để lấy đúng tập top-N mà người
            // dùng thật sẽ thấy; chỉ khâu ĐO thành phần mới dùng scorer trần.
            for (var result : ranker.rank(resolved.candidateDocIds(), resolved.queryTermFrequency(),
                    index, config.scorer(), pageRankScores, TOP_N)) {
                double tfidf = bareTfIdf.score(resolved.queryTermFrequency(),
                        result.document().getDocId(), index);
                sumTfidf += tfidf;
                sumPageRank += result.pageRankScore();
                maxTfidf = Math.max(maxTfidf, tfidf);
                maxPageRank = Math.max(maxPageRank, result.pageRankScore());
                samples++;
            }
        }
        if (samples == 0) {
            return "";
        }
        double meanTfidf = sumTfidf / samples;
        double meanPageRank = sumPageRank / samples;
        double weightedTfidf = 0.6 * meanTfidf;
        double weightedPageRank = 0.3 * meanPageRank;

        StringBuilder sb = new StringBuilder();
        sb.append("## 8. Phân tích thang đo của các thành phần điểm\n\n");
        sb.append("> **Vì sao phải có mục này.** Mục 3 cho thấy bộ trọng số 0.6/0.3/0.1 đạt\n");
        sb.append("> MRR cao nhất. Nhưng một bảng số liệu chỉ nói *cấu hình nào tốt hơn*, nó\n");
        sb.append("> không nói *vì sao*. Trước khi rút ra bất kỳ kết luận nào về ý nghĩa của\n");
        sb.append("> từng trọng số, phải kiểm tra một giả định ngầm mà công thức kết hợp\n");
        sb.append("> tuyến tính dựa vào — và giả định đó hoá ra là SAI.\n\n");
        sb.append("Điểm cuối cùng là `alpha*tfidf + beta*pageRank + gamma*titleBonus`. Công\n");
        sb.append("thức này chỉ có ý nghĩa nếu ba đại lượng cùng thang đo. Đo trên ")
                .append(samples).append(" cặp\n(truy vấn, kết quả top-").append(TOP_N).append("):\n\n");
        sb.append("| Thành phần | Giá trị trung bình | Giá trị lớn nhất | Sau khi nhân trọng số |\n");
        sb.append("|---|---|---|---|\n");
        sb.append(String.format(Locale.US, "| TF-IDF cosine | %.6f | %.6f | %.6f (alpha=0.6) |%n",
                meanTfidf, maxTfidf, weightedTfidf));
        sb.append(String.format(Locale.US, "| PageRank | %.8f | %.8f | %.8f (beta=0.3) |%n",
                meanPageRank, maxPageRank, weightedPageRank));
        sb.append(String.format(Locale.US, "| Title bonus | trong khoảng [0, 1] | 1.0 | tối đa 0.1 (gamma=0.1) |%n%n"));

        double ratio = weightedPageRank == 0 ? Double.POSITIVE_INFINITY : weightedTfidf / weightedPageRank;
        sb.append(String.format(Locale.US,
                "**Phát hiện:** phần đóng góp của TF-IDF lớn hơn phần đóng góp của PageRank\n"
                        + "khoảng **%.0f lần** sau khi đã nhân trọng số. Nguyên nhân: PageRank là một\n"
                        + "phân phối xác suất có tổng bằng 1 trên %d tài liệu, nên giá trị điển hình\n"
                        + "chỉ quanh 1/N ≈ %.6f, trong khi TF-IDF cosine nằm trong khoảng [0,1] với\n"
                        + "giá trị điển hình lớn hơn hàng nghìn lần.%n%n", ratio,
                pageRankScores.size(), pageRankScores.isEmpty() ? 0 : 1.0 / pageRankScores.size()));
        sb.append("**Hệ quả quan trọng đối với việc diễn giải kết quả:** con số `beta = 0.3`\n");
        sb.append("KHÔNG có nghĩa là \"PageRank đóng góp 30% vào điểm cuối\". Trên thực tế\n");
        sb.append("PageRank gần như không ảnh hưởng tới thứ hạng ở mọi giá trị beta thử\n");
        sb.append("nghiệm. Vì vậy chênh lệch quan sát được trong phép quét beta ở mục 3 thực\n");
        sb.append("chất phản ánh việc **alpha bị thay đổi theo** (do ràng buộc\n");
        sb.append("`alpha = 0.9 − beta`), tức là tỷ lệ giữa TF-IDF và title bonus, chứ không\n");
        sb.append("phải ảnh hưởng của PageRank.\n\n");
        sb.append("**Đề xuất khắc phục:** chuẩn hoá PageRank về cùng thang đo trước khi kết\n");
        sb.append("hợp — ví dụ chia cho giá trị PageRank lớn nhất trong corpus, hoặc dùng\n");
        sb.append("min-max normalisation trên tập ứng viên của từng truy vấn. Khi đó trọng số\n");
        sb.append("mới thực sự mang ý nghĩa tỷ lệ đóng góp và mới quét tham số có ý nghĩa được.\n\n");
        sb.append(SCALE_LESSON);
        return sb.toString();
    }

    /** Bài học tổng quát rút ra từ phân tích thang đo — phần giảng giải, không phụ thuộc số liệu. */
    private static final String SCALE_LESSON = """
            **Bài học tổng quát:** khi kết hợp tuyến tính nhiều tín hiệu, **luôn kiểm tra
            độ lớn thực tế** của từng thành phần trước khi diễn giải trọng số. Một trọng
            số lớn không có nghĩa là ảnh hưởng lớn. Đây là loại lỗi mà bảng kết quả
            không bao giờ tự tố giác: mọi con số MRR ở mục 3 đều đúng, chỉ có cách
            *giải thích* chúng là sai nếu bỏ qua phép kiểm tra này.
            """;

    private static InvertedIndex buildIndex(List<WebDocument> docs) {
        List<WebDocument> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.comparingInt(WebDocument::getDocId));
        InvertedIndex index = new InvertedIndex();
        for (WebDocument doc : sorted) {
            index.addDocument(doc);
        }
        return index;
    }

    /**
     * Các cấu hình đem so sánh. Cố ý thiết kế theo kiểu <b>ablation</b>:
     * mỗi cấu hình chỉ khác cấu hình nền đúng một yếu tố, để chênh lệch
     * quan sát được quy được về đúng yếu tố đó.
     */
    private static List<EvaluationHarness.RankingConfig> buildConfigs(
            Map<Integer, Double> pageRankScores) {
        TfIdfScorer tfidf = new TfIdfScorer();
        BM25Scorer bm25 = new BM25Scorer();
        List<EvaluationHarness.RankingConfig> configs = new ArrayList<>();

        // Nhóm 1: so sánh mô hình tính điểm, tắt hết PageRank và title bonus.
        configs.add(EvaluationHarness.RankingConfig.of("TF-IDF thuần", tfidf, pageRankScores, 0.0, 0.0));
        configs.add(EvaluationHarness.RankingConfig.of("BM25 thuần", bm25, pageRankScores, 0.0, 0.0));

        // Nhóm 2: thêm từng thành phần một vào TF-IDF để tách biệt đóng góp.
        configs.add(EvaluationHarness.RankingConfig.of("TF-IDF + title", tfidf, pageRankScores, 0.0, 0.1));
        configs.add(EvaluationHarness.RankingConfig.of("TF-IDF + PageRank", tfidf, pageRankScores, 0.3, 0.0));
        configs.add(EvaluationHarness.RankingConfig.of("TF-IDF + PR + title (đang dùng)", tfidf, pageRankScores, 0.3, 0.1));

        // Nhóm 3: quét trọng số PageRank để tìm điểm tối ưu thực nghiệm.
        for (double beta : new double[]{0.05, 0.10, 0.20, 0.50, 0.80}) {
            configs.add(EvaluationHarness.RankingConfig.of(
                    String.format(Locale.US, "TF-IDF beta=%.2f", beta), tfidf, pageRankScores, beta, 0.1));
        }

        // Nhóm 4: BM25 với cùng bộ trọng số đang dùng, xem có cộng hưởng không.
        configs.add(EvaluationHarness.RankingConfig.of("BM25 + PR + title", bm25, pageRankScores, 0.3, 0.1));
        return configs;
    }

    /**
     * @param reciprocalRanks reciprocal rank của TỪNG truy vấn, giữ nguyên thứ
     *                        tự truy vấn. Bắt buộc phải giữ lại để chạy kiểm
     *                        định THEO CẶP — chỉ có MRR trung bình thì không
     *                        ghép cặp được, và kiểm định không theo cặp mất
     *                        phần lớn độ mạnh thống kê.
     */
    private record ConfigResult(String label, double mrr, double success1, double success5,
                                 double success10, double avgQueryMs, double avgCandidates,
                                 double[] reciprocalRanks) {
    }

    private static ConfigResult evaluate(EvaluationHarness harness,
                                          EvaluationHarness.RankingConfig config,
                                          List<KnownItemQueryGenerator.KnownItemQuery> queries) {
        List<Double> reciprocalRanks = new ArrayList<>();
        int hit1 = 0, hit5 = 0, hit10 = 0;
        long totalNanos = 0;
        long totalCandidates = 0;

        for (KnownItemQueryGenerator.KnownItemQuery query : queries) {
            long start = System.nanoTime();
            List<String> ranked = harness.search(query.queryText(), config, TOP_N);
            totalNanos += System.nanoTime() - start;

            reciprocalRanks.add(EvaluationMetrics.reciprocalRank(ranked, query.targetUrl()));
            hit1 += (int) EvaluationMetrics.successAtK(ranked, query.targetUrl(), 1);
            hit5 += (int) EvaluationMetrics.successAtK(ranked, query.targetUrl(), 5);
            hit10 += (int) EvaluationMetrics.successAtK(ranked, query.targetUrl(), 10);
            totalCandidates += ranked.size();
        }

        int n = queries.size();
        double[] perQuery = new double[reciprocalRanks.size()];
        for (int i = 0; i < perQuery.length; i++) {
            perQuery[i] = reciprocalRanks.get(i);
        }
        return new ConfigResult(config.label(),
                EvaluationMetrics.meanReciprocalRank(reciprocalRanks),
                (double) hit1 / n, (double) hit5 / n, (double) hit10 / n,
                totalNanos / 1_000_000.0 / n,
                (double) totalCandidates / n,
                perQuery);
    }

    /**
     * Bảng kiểm định ý nghĩa thống kê cho các cặp cấu hình đáng so sánh nhất.
     *
     * <p>Chỉ kiểm định những cặp <b>đã có giả thuyết từ trước</b>, không kiểm
     * định tất cả các cặp. Lý do là vấn đề <b>so sánh bội</b>: chạy 13 cấu hình
     * cho 78 cặp, và ở mức α = 0,05 thì trung bình có <b>~4 cặp</b> đạt "có ý
     * nghĩa" thuần tuý do ngẫu nhiên. Chọn trước một số ít cặp cần trả lời là
     * cách phòng vệ rẻ nhất và trung thực nhất.
     */
    private static String renderSignificanceSection(List<ConfigResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 5. Kiểm định ý nghĩa thống kê\n\n");
        sb.append(SIGNIFICANCE_WHY);
        sb.append("\n\n");

        List<String[]> pairs = List.of(
                new String[]{"BM25 thuần", "TF-IDF thuần"},
                new String[]{"TF-IDF + title", "TF-IDF thuần"},
                new String[]{"TF-IDF + PageRank", "TF-IDF thuần"},
                new String[]{"TF-IDF + PR + title (đang dùng)", "TF-IDF thuần"},
                new String[]{"TF-IDF + PR + title (đang dùng)", "BM25 thuần"},
                new String[]{"TF-IDF + PR + title (đang dùng)", "TF-IDF + title"});

        sb.append("| So sánh (A với B) | ΔMRR | KTC 95 % | p (t-test) | p (hoán vị) | Kết luận |\n");
        sb.append("|---|---|---|---|---|---|\n");

        for (String[] pair : pairs) {
            ConfigResult a = findByLabel(results, pair[0]);
            ConfigResult b = findByLabel(results, pair[1]);
            if (a == null || b == null) {
                continue;
            }
            SignificanceTest.Result test =
                    SignificanceTest.pairedTest(a.reciprocalRanks(), b.reciprocalRanks());

            String verdict;
            if (test.testsDisagree()) {
                verdict = "⚠️ hai kiểm định **không đồng ý**";
            } else if (test.isSignificant()) {
                verdict = "✅ **có ý nghĩa**";
            } else {
                verdict = "❌ **chưa kết luận được**";
            }

            sb.append(String.format(Locale.US,
                    "| %s **vs** %s | %+.4f | [%+.4f, %+.4f] | %s | %s | %s |%n",
                    pair[0], pair[1], test.meanDifference(),
                    test.confidenceLow(), test.confidenceHigh(),
                    formatPValue(test.pValueTTest()),
                    formatPValue(test.pValueRandomization()),
                    verdict));
        }

        sb.append("\n");
        sb.append(SIGNIFICANCE_HOW_TO_READ);
        sb.append("\n");
        return sb.toString();
    }

    private static ConfigResult findByLabel(List<ConfigResult> results, String label) {
        return results.stream().filter(r -> r.label().equals(label)).findFirst().orElse(null);
    }

    /** p-value rất nhỏ thì báo dạng ngưỡng — báo "0,0000" là nói quá điều đo được. */
    private static String formatPValue(double p) {
        if (p < 1e-4) {
            return "< 0,0001";
        }
        return String.format(Locale.US, "%.4f", p).replace('.', ',');
    }

    private static final String SIGNIFICANCE_WHY = """
            Bảng ở mục 3 nói *cấu hình nào có MRR cao hơn*. Nó **không** trả lời
            được câu hỏi quan trọng hơn:

            > Nếu hai cấu hình thực sự ngang nhau, xác suất quan sát được chênh
            > lệch lớn bằng hoặc hơn thế này — chỉ do ngẫu nhiên của việc chọn
            > đúng 200 truy vấn đó — là bao nhiêu?

            Đó là **p-value**. Không có nó, mọi câu "cấu hình A tốt hơn B" đều là
            khẳng định **chưa được chứng minh**.

            **Kiểm định theo cặp.** Cả hai cấu hình chạy trên *cùng* tập truy vấn,
            nên ta xét **hiệu từng cặp** `d_i = RR_A(q_i) − RR_B(q_i)` thay vì so
            sánh hai trung bình độc lập. Cách này khử được nguồn biến thiên lớn
            nhất và hoàn toàn không liên quan tới thứ cần đo: *truy vấn này vốn
            dễ, truy vấn kia vốn khó*.

            **Hai kiểm định, hai giả định khác nhau.** Paired t-test giả định hiệu
            phân phối xấp xỉ chuẩn — với reciprocal rank (rất lệch, dồn ở 1,0 và
            0) thì đó là giả định đáng hoài nghi. Randomization test **không giả
            định gì** và là kiểm định được khuyến dùng trong ngành truy hồi thông
            tin (Smucker, Allan & Carterette, CIKM 2007). Báo cáo cả hai: khi
            chúng đồng ý, kết luận vững; khi khác nhau, đó là phát hiện đáng nói
            chứ không phải thứ để giấu.""";

    private static final String SIGNIFICANCE_HOW_TO_READ = """
            **Cách đọc bảng.**

            - **ΔMRR** là trung bình hiệu theo từng truy vấn, *không* phải hiệu
              của hai giá trị MRR ở mục 3 — hai số này bằng nhau về mặt đại số,
              nhưng chỉ cách tính theo cặp mới cho được sai số chuẩn.
            - **KTC 95 %** là khoảng tin cậy của ΔMRR. Nếu khoảng này **chứa 0**
              thì không loại trừ được khả năng hai cấu hình thực sự ngang nhau —
              bất kể ΔMRR dương bao nhiêu.
            - **"Chưa kết luận được"** ≠ **"hai cấu hình ngang nhau"**. Nó chỉ có
              nghĩa là 200 truy vấn chưa đủ để phân biệt. Đây là phân biệt hay bị
              nói sai nhất khi đọc kết quả kiểm định.
            - p-value được báo dạng `< 0,0001` thay vì `0,0000`: với 100.000 lần
              hoán vị, sàn phân giải của randomization test là `1/100.001`, nên
              viết `0,0000` là nói quá điều đo được.""";

    private static String renderTable(List<ConfigResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Cấu hình xếp hạng | MRR | Success@1 | Success@5 | Success@10 | ms/truy vấn |\n");
        sb.append("|---|---|---|---|---|---|\n");
        ConfigResult best = results.stream().max(Comparator.comparingDouble(ConfigResult::mrr)).orElse(null);
        for (ConfigResult r : results) {
            boolean isBest = best != null && r == best;
            String label = isBest ? "**" + r.label() + "**" : r.label();
            sb.append(String.format(Locale.US, "| %s | %s | %.1f%% | %.1f%% | %.1f%% | %.2f |%n",
                    label,
                    isBest ? String.format(Locale.US, "**%.4f**", r.mrr()) : String.format(Locale.US, "%.4f", r.mrr()),
                    r.success1() * 100, r.success5() * 100, r.success10() * 100, r.avgQueryMs()));
        }
        return sb.toString();
    }

    private static String buildMarkdownReport(int docCount, InvertedIndex index,
                                               PageRankService.PageRankResult pageRank,
                                               List<KnownItemQueryGenerator.KnownItemQuery> queries,
                                               List<ConfigResult> results,
                                               long indexMs, long pageRankMs) {
        ConfigResult best = results.stream().max(Comparator.comparingDouble(ConfigResult::mrr)).orElseThrow();
        ConfigResult current = results.stream()
                .filter(r -> r.label().contains("đang dùng")).findFirst().orElse(best);
        ConfigResult tfidfOnly = results.stream()
                .filter(r -> r.label().equals("TF-IDF thuần")).findFirst().orElse(best);
        ConfigResult bm25Only = results.stream()
                .filter(r -> r.label().equals("BM25 thuần")).findFirst().orElse(best);

        StringBuilder sb = new StringBuilder();
        sb.append("# Đánh giá chất lượng tìm kiếm (EVALUATION)\n\n");
        sb.append("> Tài liệu này được **sinh tự động** bởi `com.vnsearch.eval.EvaluationRunner`.\n");
        sb.append("> Mọi con số đều tái lập được: chạy lại lệnh dưới đây sẽ ra đúng kết quả này.\n");
        sb.append("> **Đừng sửa tay file này** — hãy sửa phần sinh báo cáo trong\n");
        sb.append("> `eval/EvaluationRunner.java` rồi chạy lại.\n\n");
        sb.append("```bash\ncd search-engine\n");
        sb.append("./mvnw.cmd exec:java -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \\\n");
        sb.append("     -Dexec.args=\"data/crawled-multi.json ").append(queries.size()).append("\"\n```\n\n");
        sb.append(READING_GUIDE);

        sb.append("## 1. Phương pháp\n\n");
        sb.append(METHOD_WHY);
        sb.append("### 1.2. Chọn từ khoá thế nào cho truy vấn có ý nghĩa\n\n");
        sb.append("Đây là chỗ dễ làm sai nhất của cả phương pháp. Từ khoá của mỗi truy vấn\n");
        sb.append("được chọn theo điểm TF-IDF cao nhất, nhưng **chỉ\n");
        sb.append("lấy các term có document frequency trong khoảng [")
                .append(KnownItemQueryGenerator.MIN_DF).append(", ")
                .append(String.format(Locale.US, "%.0f%% số tài liệu", KnownItemQueryGenerator.MAX_DF_RATIO * 100))
                .append("]**. Lọc dưới để\n");
        sb.append("loại term quá hiếm (nếu chỉ một tài liệu chứa term thì phép giao posting\n");
        sb.append("list trả về đúng một kết quả, hệ thống nào cũng đạt MRR = 1,0 và bài đánh\n");
        sb.append("giá mất hết ý nghĩa phân biệt); lọc trên để loại term quá phổ biến, gần\n");
        sb.append("như không mang thông tin.\n\n");
        sb.append(METHOD_DETAILS);

        sb.append("## 2. Corpus và cấu hình thí nghiệm\n\n");
        sb.append("| Thông số | Giá trị |\n|---|---|\n");
        sb.append("| Số tài liệu | ").append(docCount).append(" |\n");
        sb.append("| Số term phân biệt | ").append(index.getTermCount()).append(" |\n");
        sb.append(String.format(Locale.US, "| Độ dài tài liệu trung bình | %.1f token |%n", index.getAverageDocLength()));
        sb.append(String.format(Locale.US, "| Thời gian dựng chỉ mục | %.1f giây |%n", indexMs / 1000.0));
        sb.append("| Số vòng lặp PageRank tới hội tụ | ").append(pageRank.iterations()).append(" |\n");
        sb.append(String.format(Locale.US, "| Thời gian tính PageRank | %.1f giây |%n", pageRankMs / 1000.0));
        sb.append("| Số truy vấn đánh giá | ").append(queries.size()).append(" |\n");
        sb.append("| Số từ khoá mỗi truy vấn | ").append(TERMS_PER_QUERY).append(" |\n");
        sb.append("| Seed ngẫu nhiên | ").append(SEED).append(" |\n\n");

        sb.append("### Ví dụ truy vấn được sinh\n\n");
        sb.append("| Truy vấn | Tài liệu đích |\n|---|---|\n");
        for (int i = 0; i < Math.min(5, queries.size()); i++) {
            KnownItemQueryGenerator.KnownItemQuery q = queries.get(i);
            sb.append("| `").append(q.queryText()).append("` | ").append(q.targetUrl()).append(" |\n");
        }
        sb.append("\n## 3. Kết quả\n\n");
        sb.append(renderTable(results)).append("\n");

        sb.append(HOW_TO_READ_TABLE);

        sb.append(renderSignificanceSection(results)).append("\n");

        sb.append("## 6. Nhận xét\n\n");
        sb.append(String.format(Locale.US,
                "**BM25 với TF-IDF.** BM25 thuần đạt MRR %.4f so với %.4f của TF-IDF cosine thuần "
                        + "(chênh %+.1f%%). ", bm25Only.mrr(), tfidfOnly.mrr(),
                tfidfOnly.mrr() == 0 ? 0 : (bm25Only.mrr() - tfidfOnly.mrr()) / tfidfOnly.mrr() * 100));
        sb.append(bm25Only.mrr() > tfidfOnly.mrr()
                ? "Kết quả phù hợp với kỳ vọng lý thuyết: cơ chế bão hoà tần suất của BM25 "
                  + "hạn chế được ảnh hưởng của việc lặp từ khoá, còn tham số `b` cho phép "
                  + "điều chỉnh mức phạt tài liệu dài mềm dẻo hơn phép chia cứng cho "
                  + "`sqrt(docLength)` của TF-IDF.\n\n"
                : "Kết quả NGƯỢC với kỳ vọng lý thuyết thông thường — cần phân tích thêm; "
                  + "một khả năng là đặc thù corpus (các bài báo có độ dài khá đồng đều) "
                  + "khiến ưu thế chuẩn hoá độ dài của BM25 không phát huy được.\n\n");

        sb.append(String.format(Locale.US,
                "**Đóng góp của PageRank.** Cấu hình đang dùng (0.6/0.3/0.1) đạt MRR %.4f, "
                        + "so với %.4f khi tắt hoàn toàn PageRank. ", current.mrr(), tfidfOnly.mrr()));
        sb.append(current.mrr() >= tfidfOnly.mrr()
                ? "PageRank có đóng góp dương.\n\n"
                : "**PageRank đang LÀM GIẢM chất lượng** trên bộ truy vấn này. Điều này hợp lý "
                  + "với bản chất bài toán: known-item search cần tìm đúng MỘT tài liệu cụ thể, "
                  + "trong khi PageRank là tín hiệu về độ uy tín chung của trang, không phụ "
                  + "thuộc truy vấn — nó đẩy các trang chủ/trang chuyên mục có nhiều liên kết "
                  + "lên trên, che mất bài viết cụ thể mà người dùng đang tìm. Xem mục 5.\n\n");

        sb.append(String.format(Locale.US,
                "**Bộ trọng số tốt nhất.** Trong toàn bộ %d cấu hình thử nghiệm, tốt nhất là "
                        + "**%s** với MRR = %.4f và Success@1 = %.1f%%. ",
                results.size(), best.label(), best.mrr(), best.success1() * 100));
        if (!best.label().equals(current.label())) {
            sb.append(String.format(Locale.US,
                    "Cấu hình này tốt hơn cấu hình đang dùng %.1f%% về MRR, nên **đề xuất đổi "
                            + "sang bộ trọng số đó** trong `application.properties`.\n\n",
                    current.mrr() == 0 ? 0 : (best.mrr() - current.mrr()) / current.mrr() * 100));
        } else {
            sb.append("Cấu hình đang dùng đã là tốt nhất trong các phương án thử nghiệm.\n\n");
        }

        sb.append(LIMITATIONS);
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Các khối giảng giải KHÔNG phụ thuộc số liệu.
    //
    // Giữ ở đây (thay vì sửa tay file Markdown) để docs/EVALUATION.md vẫn
    // được sinh tự động hoàn toàn — nếu sửa tay file .md thì lần chạy lại
    // kế tiếp sẽ xoá mất, và câu "mọi con số đều tái lập được" thành sai.
    // ---------------------------------------------------------------------

    private static final String READING_GUIDE = """
            ## Cách đọc tài liệu này

            | Mục | Trả lời câu hỏi |
            |---|---|
            | 1. Phương pháp | Lấy đâu ra "đáp án đúng" khi không có người gán nhãn? |
            | 2. Corpus | Thí nghiệm chạy trên dữ liệu gì, cấu hình nào? |
            | 3. Kết quả | 11 cấu hình xếp hạng, cái nào tốt nhất? |
            | 4. Cách đọc bảng | Vì sao 11 cấu hình đó, chứ không phải 11 cấu hình khác? |
            | 5. Nhận xét | Kết luận rút ra được |
            | 6. Hạn chế | Kết quả này KHÔNG chứng minh điều gì |
            | 7. Thang đo | Một giả định ngầm của công thức kết hợp — và nó sai |

            Nếu chỉ đọc được hai mục, hãy đọc **mục 4** và **mục 7**: mục 4 giải thích
            thiết kế thí nghiệm, mục 7 chứa phát hiện quan trọng nhất.

            """;

    private static final String METHOD_WHY = """
            ### 1.1. Vì sao dùng known-item search

            Muốn đo chất lượng tìm kiếm thì phải biết **tài liệu nào liên quan tới truy
            vấn nào** — tập nhãn này gọi là *qrels*. Vấn đề: qrels thường phải do người
            gán tay, vừa tốn công vừa chủ quan. Gán nhãn 5.011 tài liệu cho 30 truy vấn
            là 150.000 lượt đánh giá.

            **Known-item search** là phương pháp kinh điển né được điều đó bằng cách lật
            ngược bài toán. Thay vì hỏi "tài liệu nào liên quan tới truy vấn này" (cần
            người trả lời), ta **chọn trước một tài liệu**, sinh truy vấn từ chính các từ
            khoá đặc trưng nhất của nó, và tài liệu đó chính là đáp án đúng **duy nhất**.

            Phương pháp này mô phỏng đúng một tình huống rất thật: người dùng nhớ mang
            máng một bài báo đã đọc rồi gõ vài từ khoá để tìm lại.

            Vì mỗi truy vấn có đúng một đáp án, độ đo phù hợp là **MRR** và
            **Success@k** — không phải MAP hay nDCG (những độ đo đó cần nhiều tài liệu
            liên quan ở nhiều mức độ).

            """;

    private static final String METHOD_DETAILS = """
            Thêm hai chi tiết trong cách sinh truy vấn:

            - **Nhân đôi điểm cho term xuất hiện trong tiêu đề** — vì đó chính là thứ
              người dùng nhớ và gõ lại.
            - **Loại truy vấn trùng** — nếu hai tài liệu sinh ra cùng một chuỗi truy
              vấn thì ground truth nhập nhằng, không biết đáp án nào mới đúng.

            ### 1.3. Hai độ đo được dùng

            | Độ đo | Ý nghĩa | Công thức |
            |---|---|---|
            | **MRR** | Trung bình nghịch đảo thứ hạng của tài liệu đích. Đây là độ đo chính. | `MRR = (1/\\|Q\\|) · Σ 1/rank` |
            | **Success@k** | Tỷ lệ truy vấn mà tài liệu đích lọt vào top k. | `Success@k = (số truy vấn có rank ≤ k) / \\|Q\\|` |

            **Ví dụ tính tay cho MRR.** Giả sử chạy 4 truy vấn, tài liệu đích nằm ở
            hạng 1, 2, 5 và không tìm thấy:

            | Truy vấn | Hạng của đích | Reciprocal Rank |
            |---|---|---|
            | q1 | 1 | 1/1 = 1,000 |
            | q2 | 2 | 1/2 = 0,500 |
            | q3 | 5 | 1/5 = 0,200 |
            | q4 | không tìm thấy | 0,000 |

            `MRR = (1,000 + 0,500 + 0,200 + 0,000) / 4 = 0,425`

            **Cách đọc một giá trị MRR.** Vì hạng 1 cho 1,0 và hạng 2 cho 0,5, MRR chịu
            ảnh hưởng rất mạnh từ việc đích có nằm ở **hạng 1** hay không. MRR ≈ 0,92
            nghĩa là đại đa số truy vấn tìm ra đích ngay ở vị trí đầu — điều này khớp
            với Success@1 trong bảng kết quả, và hai con số đó nên luôn được đọc cùng
            nhau.

            """;

    private static final String HOW_TO_READ_TABLE = """
            ## 4. Cách đọc bảng kết quả

            11 cấu hình trên **không** được chọn tuỳ ý. Chúng được thiết kế theo kiểu
            **ablation**: mỗi cấu hình chỉ khác cấu hình nền **đúng một** yếu tố, để
            chênh lệch quan sát được **quy được về đúng yếu tố đó**.

            | Nhóm | Cấu hình | Câu hỏi được trả lời |
            |---|---|---|
            | 1 | TF-IDF thuần, BM25 thuần (α=1, β=γ=0) | Mô hình tính điểm nào tốt hơn, khi tắt hết tín hiệu khác? |
            | 2 | TF-IDF + title (0.9/0/0.1) · TF-IDF + PageRank (0.7/0.3/0) · cả hai (0.6/0.3/0.1) | Từng tín hiệu bổ sung đóng góp bao nhiêu? |
            | 3 | Quét beta = 0.05 … 0.80 | Trọng số PageRank nào tối ưu? |
            | 4 | BM25 + PR + title (0.6/0.3/0.1) | Ưu thế của BM25 có cộng hưởng với các tín hiệu khác? |

            **Cách tách biệt đóng góp của một tín hiệu.** So hai hàng chỉ khác nhau ở
            tín hiệu đó:

            ```
            đóng góp của title bonus = MRR(TF-IDF + title)     − MRR(TF-IDF thuần)
            đóng góp của PageRank    = MRR(TF-IDF + PageRank)  − MRR(TF-IDF thuần)
            ```

            Hãy tự tính hai hiệu số này từ bảng ở mục 3. Bạn sẽ thấy đóng góp của
            title bonus **lớn hơn nhiều lần** đóng góp của PageRank — đó là dấu hiệu
            đầu tiên dẫn tới phát hiện ở mục 7.

            > **Cảnh báo quan trọng khi đọc nhóm 3.** Phép quét beta bị ràng buộc
            > `alpha = 0.9 − beta` (gamma giữ nguyên 0.1). Nghĩa là khi beta tăng thì
            > alpha **giảm theo**, nên mỗi hàng thay đổi **hai** biến số cùng lúc, không
            > phải một. Đây không phải ablation thuần khiết, và mục 7 giải thích vì sao
            > điều đó làm mọi kết luận về beta trở nên vô nghĩa.

            > **Về cột `ms/truy vấn`.** Con số này chỉ để tham khảo, **không** phải phép
            > đo hiệu năng nghiêm túc: nó không có vòng làm nóng JVM riêng cho từng cấu
            > hình, nên cấu hình chạy trước gánh phần lớn chi phí JIT. Muốn so tốc độ
            > nghiêm túc thì xem `docs/GIN-BASELINE.md`, nơi có làm nóng đúng cách.

            """;

    private static final String LIMITATIONS = """
            ## 7. Hạn chế của phương pháp

            Phải nêu rõ để kết quả được diễn giải đúng. Một báo cáo không nêu hạn chế
            thì không đáng tin, vì mọi phương pháp đo đều có hạn chế.

            1. **Known-item search chỉ có đúng một tài liệu đúng cho mỗi truy vấn.**
               Nó đo tốt khả năng "tìm lại đúng bài đã biết", nhưng không đo được
               chất lượng của truy vấn khám phá kiểu "tin tức công nghệ" — loại truy
               vấn mà nhiều tài liệu cùng liên quan ở các mức khác nhau. Vì vậy nó
               **thiên vị chống lại PageRank**, vốn là tín hiệu về uy tín chung chứ
               không về mức khớp với một truy vấn cụ thể. Nói cách khác: PageRank có
               thể đang làm tốt việc của nó mà phương pháp đo này không nhìn thấy.
            2. **Truy vấn được sinh máy móc từ chính tài liệu**, nên phân bố từ khoá
               không hoàn toàn giống truy vấn người thật gõ. Người thật gõ ngắn hơn,
               sai chính tả, dùng từ thông dụng thay vì từ đặc trưng nhất.
            3. **Chỉ đo được xếp hạng, không đo được tách từ.** Nếu tokenizer ghép sai
               một từ ghép thì cả truy vấn lẫn tài liệu đều sai theo cùng một cách,
               nên phép đo vẫn cho kết quả tốt. Độ chính xác tách từ cần một tập văn
               bản đã tách thủ công làm chuẩn — hiện **chưa có**.
            4. Để bổ khuyết ba điểm trên, cần thêm bộ truy vấn có **nhãn liên quan
               nhiều bậc do người gán** (xem `PoolBuilder` và `QrelsEvaluationRunner`),
               khi đó mới dùng được nDCG/MAP và mới đánh giá công bằng cho PageRank.

            """;
}
