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
        sb.append("> Sinh tự động bởi `com.vnsearch.storage.GinBaselineRunner`.\n");
        sb.append("> **Đừng sửa tay file này** — hãy sửa phần sinh báo cáo trong\n");
        sb.append("> `storage/GinBaselineRunner.java` rồi chạy lại.\n\n");
        sb.append(WHY_BASELINE);

        sb.append("## 1. Thiết lập thí nghiệm\n\n");
        sb.append("Chạy trên cùng **").append(docCount).append(" tài liệu** và cùng **")
                .append(queryCount).append(" truy vấn known-item** (seed 42) — đúng bộ truy vấn\n");
        sb.append("mà `docs/EVALUATION.md` dùng, sinh bởi cùng một `KnownItemQueryGenerator`.\n\n");
        sb.append(SETUP_DETAILS);

        sb.append("## 2. Kết quả\n\n");
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

        sb.append("## 3. Nhận xét\n\n");
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

        sb.append(NOT_EQUIVALENT);
        sb.append(WHAT_IT_DOES_NOT_PROVE);
        sb.append(HOW_TO_RERUN);
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Các khối giảng giải KHÔNG phụ thuộc số liệu.
    //
    // Giữ ở đây (thay vì sửa tay file Markdown) để docs/GIN-BASELINE.md vẫn
    // được sinh tự động hoàn toàn — nếu sửa tay file .md thì lần chạy lại
    // kế tiếp sẽ xoá mất.
    // ---------------------------------------------------------------------

    private static final String WHY_BASELINE = """
            ## Vì sao đồ án cần một baseline bên ngoài

            Mọi phát biểu kiểu *"chỉ mục tự cài chạy nhanh"* đều chỉ là **tự khẳng
            định** — nhanh so với cái gì? Không có mốc so sánh thì con số 6 ms cũng
            vô nghĩa như con số 600 ms.

            PostgreSQL là mốc so sánh sòng phẳng và **khiêm tốn**, vì:

            - Chỉ mục **GIN** của nó bản chất cũng là một **chỉ mục đảo** — cùng ý
              tưởng cốt lõi với thứ đồ án tự cài, nên so sánh là so cùng loại.
            - Nó đã được tối ưu suốt **hàng chục năm** bởi một cộng đồng lớn.
            - Nó **bất lợi** trong phép đo này (phải đi qua tầng mạng, phân tích SQL,
              đọc trang từ đĩa) mà vẫn là mốc đáng gờm — nên nếu chỉ mục tự cài thắng
              về tốc độ thì phải nói rõ phần lợi thế đó.

            > **Nguyên tắc:** báo cáo cả phần mình **thua** mới là báo cáo đáng tin.

            """;

    private static final String SETUP_DETAILS = """
            ### Hai bên được đo thế nào

            | | Chỉ mục đảo tự cài | PostgreSQL GIN |
            |---|---|---|
            | Tách từ | `VietnameseTokenizer` — Longest Matching, sinh bản không dấu, lọc 91 từ dừng | `to_tsvector('simple', …)` — cắt theo khoảng trắng |
            | Lưu trữ | `LinkedHashMap<String, List<Posting>>` trong RAM | `tsvector` + chỉ mục GIN trên đĩa |
            | Xếp hạng | TF-IDF cosine + PageRank + title bonus (0.6/0.3/0.1) | `ts_rank(tsv, plainto_tsquery(...))` |
            | Truy cập | Gọi phương thức trực tiếp trong cùng tiến trình | JDBC qua TCP tới `localhost:5432` |

            Vì sao dùng cấu hình `simple` chứ không phải `english`: bộ stemmer tiếng Anh
            sẽ cắt gốc từ **sai hoàn toàn** trên tiếng Việt, nên `english` sẽ là một
            baseline bị làm cho yếu đi một cách không công bằng.

            ### Làm nóng JVM — bắt buộc, không phải tuỳ chọn

            Trước khi bấm giờ, **cả hai** phía được chạy 2 vòng đầy đủ qua toàn bộ bộ
            truy vấn:

            ```java
            for (int round = 0; round < 2; round++) {
                for (KnownItemQuery q : queries) {
                    harness.search(q.queryText(), config, TOP_N);   // phía tự cài
                    repo.searchWithGin(q.queryText(), TOP_N);       // phía GIN
                }
            }
            ```

            Lý do: JVM thực thi những lần gọi đầu bằng **trình thông dịch**, chỉ sau
            vài nghìn lượt thì JIT mới biên dịch sang mã máy. Nếu đo ngay từ lần chạy
            đầu, phía chạy **trước** gánh toàn bộ chi phí khởi động còn phía chạy
            **sau** hưởng JVM đã nóng — chênh lệch đo được khi ấy phản ánh **thứ tự
            chạy** chứ không phản ánh cài đặt.

            Bản đầu tiên của phép đo này **không** có bước làm nóng, và cho kết quả
            10,83 ms so với 1,42 ms. Sau khi thêm làm nóng cho cả hai phía, con số phía
            tự cài giảm xuống còn khoảng 6,4 ms — tức **~40%** con số ban đầu chỉ là
            chi phí khởi động JVM. Kết luận cuối cùng không đổi, nhưng mức chênh lệch
            báo cáo sai lệch đáng kể nếu không sửa.

            """;

    private static final String NOT_EQUIVALENT = """
            ## 4. Vì sao hai bên không tương đương về chức năng

            Điều này phải nói rõ, vì nó quyết định cách diễn giải **từng** con số ở
            mục 2:

            | Chỉ mục tự cài CÓ | GIN (cấu hình `simple`) KHÔNG CÓ |
            |---|---|
            | Ghép từ ghép tiếng Việt (Longest Matching) | Chỉ cắt theo khoảng trắng |
            | Chỉ mục kép có dấu / không dấu | Không |
            | Lọc từ dừng tiếng Việt | Không |
            | Lưu vị trí token → tìm theo cụm từ | Có `phraseto_tsquery` nhưng không dùng ở đây |
            | Kết hợp PageRank khi xếp hạng | Chỉ `ts_rank` theo nội dung |

            | GIN CÓ | Chỉ mục tự cài KHÔNG CÓ |
            |---|---|
            | Đa người dùng, đồng thời | Một tiến trình |
            | Giao dịch ACID | Không |
            | Bền vững sau sự cố | Mất khi tắt tiến trình |
            | Cập nhật tăng dần | Reindex toàn phần |
            | Nén chỉ mục | Không |

            **Cách diễn giải đúng:** chênh lệch về **chất lượng** phản ánh mức độ phù
            hợp với **tiếng Việt** (tức là công của khâu xử lý ngôn ngữ, không phải của
            cấu trúc dữ liệu). Chênh lệch về **tốc độ** mới là so sánh gần với việc
            so cài đặt cấu trúc dữ liệu — nhưng vẫn không thuần khiết, vì GIN phải qua
            mạng và SQL còn phía tự cài truy cập thẳng heap.

            """;

    private static final String WHAT_IT_DOES_NOT_PROVE = """
            ## 5. Điều so sánh này KHÔNG chứng minh

            **Rằng cài đặt tự viết tốt hơn PostgreSQL.** GIN chạy đa người dùng, có
            giao dịch ACID, bền vững sau sự cố, và cập nhật tăng dần — chỉ mục tự cài
            trong đồ án này **không có đặc tính nào** trong số đó. So sánh chỉ nhằm cho
            thấy một cài đặt chuyên biệt cho tiếng Việt, chạy hoàn toàn trong bộ nhớ,
            đạt được gì trên đúng bài toán hẹp mà nó được thiết kế.

            **Rằng chỉ mục đảo tự cài đã được tối ưu tốt.** Nếu GIN nhanh hơn dù phải
            qua mạng và tầng SQL, thì đó là dấu hiệu phía tự cài còn **nhiều dư địa**:
            nén posting list (delta encoding, variable-byte), tránh boxing `Integer`
            trong phép giao, chuyển ma trận thưa sang CSR sau khi dựng xong.

            **Rằng chất lượng tiếng Việt của hệ thống đã tốt.** MRR cao ở đây chỉ nói
            hệ thống tìm lại được bài đã biết. Từ điển tách từ chỉ có 154 mục, và độ
            chính xác tách từ **chưa được đo** — xem mục 6.1 của `docs/DSA-REPORT.md`.

            """;

    private static final String HOW_TO_RERUN = """
            ## 6. Cách chạy lại

            ```bash
            # 1. Dựng PostgreSQL (từ thư mục gốc của repo)
            docker compose up -d

            # 2. Nạp corpus vào CSDL (~28 giây cho 5.011 tài liệu)
            cd search-engine
            MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \\
              -Dexec.mainClass=com.vnsearch.storage.PostgresImportRunner \\
              -Dexec.args="data/crawled-multi.json"

            # 3. Chạy phép đối chứng → ghi lại chính file này
            MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \\
              -Dexec.mainClass=com.vnsearch.storage.GinBaselineRunner -Dexec.args="200"
            ```

            > **Về tính tái lập.** Các con số **chất lượng** (MRR, Success@k) tái lập
            > chính xác vì bộ truy vấn dùng seed cố định 42. Các con số **thời gian**
            > dao động vài phần trăm giữa các lần chạy và giữa các máy — đó là bản chất
            > của phép đo thời gian, không phải lỗi.
            """;
}
