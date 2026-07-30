package com.vnsearch.eval;

import com.vnsearch.crawler.CrawlerService;
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
        List<WebDocument> docs = CrawlerService.loadFromJson(corpusPath);
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

        List<EvaluationHarness.RankingConfig> configs = buildConfigs();
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
        EvaluationHarness.RankingConfig config = new EvaluationHarness.RankingConfig(
                "phân tích thang đo", new TfIdfScorer(), 0.6, 0.3, 0.1);
        com.vnsearch.query.QueryParser parser = new com.vnsearch.query.QueryParser();
        com.vnsearch.ranking.ResultRanker ranker = new com.vnsearch.ranking.ResultRanker(
                config.alpha(), config.beta(), config.gamma());

        double sumTfidf = 0, sumPageRank = 0;
        double maxTfidf = 0, maxPageRank = 0;
        int samples = 0;

        for (KnownItemQueryGenerator.KnownItemQuery query : queries) {
            var parsed = parser.parse(query.queryText());
            var resolved = com.vnsearch.query.CandidateResolver.resolve(index, parsed);
            if (resolved.candidateDocIds().isEmpty()) {
                continue;
            }
            for (var result : ranker.rank(resolved.candidateDocIds(), resolved.queryTermFrequency(),
                    index, config.scorer(), pageRankScores, TOP_N)) {
                sumTfidf += result.tfidfScore();
                sumPageRank += result.pageRankScore();
                maxTfidf = Math.max(maxTfidf, result.tfidfScore());
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
        sb.append("## 6. Phân tích thang đo của các thành phần điểm\n\n");
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
        sb.append("mới thực sự mang ý nghĩa tỷ lệ đóng góp và mới quét tham số có ý nghĩa được.\n");
        return sb.toString();
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

    /**
     * Các cấu hình đem so sánh. Cố ý thiết kế theo kiểu <b>ablation</b>:
     * mỗi cấu hình chỉ khác cấu hình nền đúng một yếu tố, để chênh lệch
     * quan sát được quy được về đúng yếu tố đó.
     */
    private static List<EvaluationHarness.RankingConfig> buildConfigs() {
        TfIdfScorer tfidf = new TfIdfScorer();
        BM25Scorer bm25 = new BM25Scorer();
        List<EvaluationHarness.RankingConfig> configs = new ArrayList<>();

        // Nhóm 1: so sánh mô hình tính điểm, tắt hết PageRank và title bonus.
        configs.add(new EvaluationHarness.RankingConfig("TF-IDF thuần", tfidf, 1.0, 0.0, 0.0));
        configs.add(new EvaluationHarness.RankingConfig("BM25 thuần", bm25, 1.0, 0.0, 0.0));

        // Nhóm 2: thêm từng thành phần một vào TF-IDF để tách biệt đóng góp.
        configs.add(new EvaluationHarness.RankingConfig("TF-IDF + title", tfidf, 0.9, 0.0, 0.1));
        configs.add(new EvaluationHarness.RankingConfig("TF-IDF + PageRank", tfidf, 0.7, 0.3, 0.0));
        configs.add(new EvaluationHarness.RankingConfig("TF-IDF + PR + title (đang dùng)", tfidf, 0.6, 0.3, 0.1));

        // Nhóm 3: quét trọng số PageRank để tìm điểm tối ưu thực nghiệm.
        for (double beta : new double[]{0.05, 0.10, 0.20, 0.50, 0.80}) {
            double alpha = 1.0 - beta - 0.1;
            configs.add(new EvaluationHarness.RankingConfig(
                    String.format(Locale.US, "TF-IDF beta=%.2f", beta), tfidf, alpha, beta, 0.1));
        }

        // Nhóm 4: BM25 với cùng bộ trọng số đang dùng, xem có cộng hưởng không.
        configs.add(new EvaluationHarness.RankingConfig("BM25 + PR + title", bm25, 0.6, 0.3, 0.1));
        return configs;
    }

    private record ConfigResult(String label, double mrr, double success1, double success5,
                                 double success10, double avgQueryMs, double avgCandidates) {
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
        return new ConfigResult(config.label(),
                EvaluationMetrics.meanReciprocalRank(reciprocalRanks),
                (double) hit1 / n, (double) hit5 / n, (double) hit10 / n,
                totalNanos / 1_000_000.0 / n,
                (double) totalCandidates / n);
    }

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
        sb.append("> Mọi con số đều tái lập được: chạy lại lệnh dưới đây sẽ ra đúng kết quả này.\n\n");
        sb.append("```bash\ncd search-engine\n");
        sb.append("./mvnw.cmd exec:java -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \\\n");
        sb.append("     -Dexec.args=\"data/crawled-multi.json ").append(queries.size()).append("\"\n```\n\n");

        sb.append("## 1. Phương pháp\n\n");
        sb.append("Dùng **known-item search** — phương pháp đánh giá kinh điển khi không có\n");
        sb.append("sẵn bộ nhãn liên quan do người gán. Thay vì hỏi \"tài liệu nào liên quan\n");
        sb.append("tới truy vấn này\" (cần người trả lời), ta lật ngược: chọn trước một tài\n");
        sb.append("liệu, sinh truy vấn từ chính các từ khoá đặc trưng nhất của nó, và tài\n");
        sb.append("liệu đó chính là đáp án đúng duy nhất. Mô phỏng đúng tình huống người\n");
        sb.append("dùng nhớ mang máng một bài báo rồi gõ vài từ khoá tìm lại.\n\n");
        sb.append("Từ khoá của mỗi truy vấn được chọn theo điểm TF-IDF cao nhất, nhưng **chỉ\n");
        sb.append("lấy các term có document frequency trong khoảng [")
                .append(KnownItemQueryGenerator.MIN_DF).append(", ")
                .append(String.format(Locale.US, "%.0f%% số tài liệu", KnownItemQueryGenerator.MAX_DF_RATIO * 100))
                .append("]**. Lọc dưới để\n");
        sb.append("loại term quá hiếm (nếu chỉ một tài liệu chứa term thì phép giao posting\n");
        sb.append("list trả về đúng một kết quả, hệ thống nào cũng đạt MRR = 1,0 và bài đánh\n");
        sb.append("giá mất hết ý nghĩa phân biệt); lọc trên để loại term quá phổ biến, gần\n");
        sb.append("như không mang thông tin.\n\n");

        sb.append("### Các độ đo\n\n");
        sb.append("| Độ đo | Ý nghĩa |\n|---|---|\n");
        sb.append("| **MRR** | Trung bình nghịch đảo thứ hạng của tài liệu đích. Đích ở hạng 1 được 1,0; hạng 2 được 0,5; hạng 10 được 0,1. Đây là độ đo chính. |\n");
        sb.append("| **Success@k** | Tỷ lệ truy vấn mà tài liệu đích lọt vào top k. |\n\n");

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

        sb.append("## 4. Nhận xét\n\n");
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

        sb.append("## 5. Hạn chế của phương pháp\n\n");
        sb.append("Phải nêu rõ để kết quả được diễn giải đúng:\n\n");
        sb.append("1. **Known-item search chỉ có đúng một tài liệu đúng cho mỗi truy vấn.**\n");
        sb.append("   Nó đo tốt khả năng \"tìm lại đúng bài đã biết\", nhưng không đo được\n");
        sb.append("   chất lượng của truy vấn khám phá kiểu \"tin tức công nghệ\" — loại truy\n");
        sb.append("   vấn mà nhiều tài liệu cùng liên quan ở các mức khác nhau. Vì vậy nó\n");
        sb.append("   **thiên vị chống lại PageRank**, vốn là tín hiệu về uy tín chung chứ\n");
        sb.append("   không về mức khớp với một truy vấn cụ thể.\n");
        sb.append("2. **Truy vấn được sinh máy móc từ chính tài liệu**, nên phân bố từ khoá\n");
        sb.append("   không hoàn toàn giống truy vấn người thật gõ.\n");
        sb.append("3. Để bổ khuyết cả hai điểm trên, cần thêm bộ truy vấn có **nhãn liên quan\n");
        sb.append("   nhiều bậc do người gán** (xem `PoolBuilder`), khi đó mới dùng được\n");
        sb.append("   nDCG/MAP và mới đánh giá công bằng cho PageRank.\n");
        return sb.toString();
    }
}
