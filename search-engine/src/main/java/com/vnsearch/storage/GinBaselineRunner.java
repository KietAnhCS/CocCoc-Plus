package com.vnsearch.storage;

import com.vnsearch.eval.EvaluationHarness;
import com.vnsearch.eval.EvaluationMetrics;
import com.vnsearch.eval.KnownItemQueryGenerator;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.TfIdfScorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * So sánh <b>chỉ mục đảo tự cài</b> với <b>chỉ mục GIN của PostgreSQL</b>
 * trên cùng một corpus và cùng một bộ truy vấn.
 *
 * <p>Đây là baseline mà một đồ án nghiêm túc cần có. Không có nó, mọi phát
 * biểu kiểu "chỉ mục tự cài chạy nhanh" đều chỉ là tự khẳng định — nhanh so
 * với cái gì? PostgreSQL là một hệ quản trị CSDL trưởng thành, chỉ mục GIN
 * của nó bản chất cũng là một chỉ mục đảo nhưng được tối ưu suốt hàng chục
 * năm, nên đó là mốc so sánh sòng phẳng và khiêm tốn.
 *
 * <p><b>Lưu ý khi diễn giải:</b> hai bên không hoàn toàn tương đương về
 * chức năng. Chỉ mục tự cài có tách từ tiếng Việt bằng Longest Matching,
 * lưu vị trí token để tìm theo cụm từ, và kết hợp PageRank khi xếp hạng.
 * Cấu hình {@code simple} của PostgreSQL chỉ tách theo khoảng trắng và xếp
 * hạng bằng {@code ts_rank}. Vì vậy chênh lệch về chất lượng phản ánh mức
 * độ phù hợp với tiếng Việt, còn chênh lệch về tốc độ mới là so sánh thuần
 * tuý về cài đặt cấu trúc dữ liệu.
 */
public class GinBaselineRunner {

    private static final int TOP_N = 10;

    public static void main(String[] args) throws Exception {
        int numQueries = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        String reportPath = args.length > 1 ? args[1] : "../docs/GIN-BASELINE.md";

        try (DocumentRepository repo = DocumentRepository.connectDefault()) {
            System.out.println("Doc corpus tu PostgreSQL ...");
            List<WebDocument> docs = repo.findAll();
            System.out.println("  " + docs.size() + " tai lieu");
            if (docs.isEmpty()) {
                System.err.println("CSDL rong - chay PostgresImportRunner truoc.");
                return;
            }

            System.out.println("Dung chi muc dao tu cai ...");
            long t0 = System.currentTimeMillis();
            InvertedIndex index = buildIndex(docs);
            long buildMs = System.currentTimeMillis() - t0;

            PageRankService.PageRankResult pageRank =
                    new PageRankService().computePageRank(index.getAllDocuments());

            List<KnownItemQueryGenerator.KnownItemQuery> queries =
                    new KnownItemQueryGenerator().generate(index, numQueries, 3, 42L);
            System.out.println("  " + queries.size() + " truy van known-item");

            EvaluationHarness harness = new EvaluationHarness(index, pageRank.scores());
            EvaluationHarness.RankingConfig config = new EvaluationHarness.RankingConfig(
                    "tu cai", new TfIdfScorer(), 0.6, 0.3, 0.1);

            // --- Làm nóng JVM trước khi đo ---
            //
            // Bắt buộc với mọi phép đo hiệu năng trên JVM: những lần gọi đầu
            // tiên chạy bằng trình thông dịch, chỉ sau vài nghìn lượt thì JIT
            // mới biên dịch sang mã máy và tối ưu. Nếu đo ngay từ lần chạy đầu,
            // phía chạy TRƯỚC sẽ gánh toàn bộ chi phí khởi động đó còn phía
            // chạy SAU được hưởng JVM đã nóng — chênh lệch đo được khi ấy phản
            // ánh thứ tự chạy chứ không phản ánh cài đặt.
            System.out.println("Lam nong JVM ...");
            for (int round = 0; round < 2; round++) {
                for (KnownItemQueryGenerator.KnownItemQuery q : queries) {
                    harness.search(q.queryText(), config, TOP_N);
                    repo.searchWithGin(q.queryText(), TOP_N);
                }
            }

            // --- Chỉ mục tự cài ---
            List<Double> ownRr = new ArrayList<>();
            int ownHit1 = 0, ownHit10 = 0;
            long ownNanos = 0;
            for (KnownItemQueryGenerator.KnownItemQuery q : queries) {
                long s = System.nanoTime();
                List<String> ranked = harness.search(q.queryText(), config, TOP_N);
                ownNanos += System.nanoTime() - s;
                ownRr.add(EvaluationMetrics.reciprocalRank(ranked, q.targetUrl()));
                ownHit1 += (int) EvaluationMetrics.successAtK(ranked, q.targetUrl(), 1);
                ownHit10 += (int) EvaluationMetrics.successAtK(ranked, q.targetUrl(), 10);
            }

            // --- Chỉ mục GIN của PostgreSQL ---
            List<Double> ginRr = new ArrayList<>();
            int ginHit1 = 0, ginHit10 = 0;
            long ginNanos = 0;
            for (KnownItemQueryGenerator.KnownItemQuery q : queries) {
                long s = System.nanoTime();
                List<String> ranked = repo.searchWithGin(q.queryText(), TOP_N);
                ginNanos += System.nanoTime() - s;
                ginRr.add(EvaluationMetrics.reciprocalRank(ranked, q.targetUrl()));
                ginHit1 += (int) EvaluationMetrics.successAtK(ranked, q.targetUrl(), 1);
                ginHit10 += (int) EvaluationMetrics.successAtK(ranked, q.targetUrl(), 10);
            }

            int n = queries.size();
            double ownMrr = EvaluationMetrics.meanReciprocalRank(ownRr);
            double ginMrr = EvaluationMetrics.meanReciprocalRank(ginRr);
            double ownMs = ownNanos / 1_000_000.0 / n;
            double ginMs = ginNanos / 1_000_000.0 / n;
            long ginBytes = repo.indexSizeBytes("idx_documents_tsv");
            long jsonBytes = Files.exists(Path.of("data/index.json"))
                    ? Files.size(Path.of("data/index.json")) : 0;

            String report = buildReport(docs.size(), n, index, buildMs,
                    ownMrr, ownHit1, ownHit10, ownMs,
                    ginMrr, ginHit1, ginHit10, ginMs, ginBytes, jsonBytes);

            Path out = Path.of(reportPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, report);
            System.out.println();
            System.out.println(report);
            System.out.println("Da ghi bao cao vao " + out.toAbsolutePath().normalize());
        }
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

    private static String buildReport(int docCount, int queryCount, InvertedIndex index, long buildMs,
                                       double ownMrr, int ownHit1, int ownHit10, double ownMs,
                                       double ginMrr, int ginHit1, int ginHit10, double ginMs,
                                       long ginBytes, long jsonBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Đối chứng: chỉ mục đảo tự cài với chỉ mục GIN của PostgreSQL\n\n");
        sb.append("> Sinh tự động bởi `com.vnsearch.storage.GinBaselineRunner`.\n\n");
        sb.append("Chạy trên cùng **").append(docCount).append(" tài liệu** và cùng **")
                .append(queryCount).append(" truy vấn known-item** (seed 42).\n\n");

        sb.append("| Tiêu chí | Chỉ mục đảo tự cài | PostgreSQL GIN |\n");
        sb.append("|---|---|---|\n");
        sb.append(String.format(Locale.US, "| MRR | %.4f | %.4f |%n", ownMrr, ginMrr));
        sb.append(String.format(Locale.US, "| Success@1 | %.1f%% | %.1f%% |%n",
                ownHit1 * 100.0 / queryCount, ginHit1 * 100.0 / queryCount));
        sb.append(String.format(Locale.US, "| Success@10 | %.1f%% | %.1f%% |%n",
                ownHit10 * 100.0 / queryCount, ginHit10 * 100.0 / queryCount));
        sb.append(String.format(Locale.US, "| Thời gian truy vấn trung bình | %.2f ms | %.2f ms |%n", ownMs, ginMs));
        sb.append(String.format(Locale.US, "| Kích thước chỉ mục | %s | %.1f MB |%n",
                jsonBytes > 0 ? String.format(Locale.US, "%.1f MB (JSON)", jsonBytes / 1048576.0) : "n/a",
                ginBytes / 1048576.0));
        sb.append(String.format(Locale.US, "| Thời gian dựng chỉ mục | %.1f giây | (nền, tăng dần) |%n",
                buildMs / 1000.0));
        sb.append("| Số term phân biệt | ").append(index.getTermCount()).append(" | (nội bộ) |\n\n");

        sb.append("## Nhận xét\n\n");
        sb.append(ownMrr > ginMrr
                ? String.format(Locale.US,
                    "**Về chất lượng**, chỉ mục tự cài đạt MRR cao hơn (%.4f so với %.4f, hơn %.1f%%). "
                    + "Nguyên nhân chính không nằm ở cấu trúc dữ liệu mà ở khâu XỬ LÝ NGÔN NGỮ: "
                    + "chỉ mục tự cài ghép từ ghép tiếng Việt bằng thuật toán Longest Matching, "
                    + "sinh thêm bản không dấu, và loại từ dừng tiếng Việt; trong khi cấu hình "
                    + "`simple` của PostgreSQL chỉ cắt theo khoảng trắng nên \"máy tính\" bị tách "
                    + "thành hai token rời rạc.%n%n", ownMrr, ginMrr,
                    ginMrr == 0 ? 0 : (ownMrr - ginMrr) / ginMrr * 100)
                : String.format(Locale.US,
                    "**Về chất lượng**, PostgreSQL GIN đạt MRR cao hơn (%.4f so với %.4f). "
                    + "Cần phân tích thêm nguyên nhân.%n%n", ginMrr, ownMrr));

        sb.append(ownMs < ginMs
                ? String.format(Locale.US,
                    "**Về tốc độ**, chỉ mục trong bộ nhớ nhanh hơn %.1f lần (%.2f ms so với %.2f ms). "
                    + "Khác biệt này phần lớn đến từ việc GIN phải đi qua tầng giao thức mạng, "
                    + "phân tích câu lệnh SQL và đọc trang từ đĩa, còn chỉ mục tự cài truy cập "
                    + "thẳng cấu trúc dữ liệu trong heap. Đây là so sánh có lợi cho phía tự cài "
                    + "và cần nói rõ điều đó khi diễn giải.%n%n", ginMs / ownMs, ownMs, ginMs)
                : String.format(Locale.US,
                    "**Về tốc độ**, PostgreSQL GIN nhanh hơn (%.2f ms so với %.2f ms) dù phải qua "
                    + "mạng và tầng SQL — một kết quả đáng chú ý cho thấy chỉ mục tự cài còn "
                    + "nhiều dư địa tối ưu.%n%n", ginMs, ownMs));

        sb.append("**Điều so sánh này KHÔNG chứng minh:** rằng cài đặt tự viết tốt hơn PostgreSQL. ");
        sb.append("GIN chạy đa người dùng, có giao dịch ACID, bền vững sau sự cố, và cập nhật ");
        sb.append("tăng dần — chỉ mục tự cài trong đồ án này không có đặc tính nào trong số đó. ");
        sb.append("So sánh chỉ nhằm cho thấy một cài đặt chuyên biệt cho tiếng Việt, chạy hoàn ");
        sb.append("toàn trong bộ nhớ, đạt được gì trên đúng bài toán hẹp mà nó được thiết kế.\n");
        return sb.toString();
    }
}
