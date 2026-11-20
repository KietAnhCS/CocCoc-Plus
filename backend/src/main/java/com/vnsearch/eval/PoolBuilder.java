package com.vnsearch.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dựng <b>pool</b> tài liệu cần gán nhãn liên quan, theo đúng phương pháp
 * pooling của TREC.
 *
 * <p><b>Bài toán:</b> muốn tính được nDCG và MAP thì cần nhãn liên quan
 * nhiều bậc do người gán. Nhưng không ai gán nhãn nổi 5.011 tài liệu cho
 * mỗi truy vấn — với 30 truy vấn thì đó là hơn 150.000 lượt đánh giá.
 *
 * <p><b>Cách TREC giải quyết:</b> chỉ gán nhãn phần <i>hợp</i> của top-k
 * kết quả do NHIỀU hệ thống khác nhau trả về. Giả định nền tảng là những
 * tài liệu thực sự liên quan gần như chắc chắn sẽ được ít nhất một trong
 * các hệ thống đưa lên top; tài liệu không hệ thống nào đưa lên top thì
 * coi như không liên quan. Nhờ vậy khối lượng gán nhãn giảm từ hàng trăm
 * nghìn xuống vài trăm, mà thứ tự xếp hạng giữa các hệ thống hầu như không
 * đổi.
 *
 * <p>Ở đây "nhiều hệ thống" chính là các cấu hình xếp hạng khác nhau
 * (TF-IDF, BM25, có/không PageRank), nên pool phản ánh được sự khác biệt
 * giữa đúng những phương án ta muốn so sánh.
 *
 * <p><b>Quy trình sử dụng:</b>
 * <ol>
 *   <li>Chạy lớp này để sinh {@code data/eval/pool-to-label.json}.</li>
 *   <li>Mở file, điền {@code relevance} cho từng mục: 0 = không liên quan,
 *       1 = liên quan, 2 = rất liên quan.</li>
 *   <li>Lưu lại thành {@code data/eval/qrels.json} và chạy
 *       {@link QrelsEvaluationRunner} để tính nDCG/MAP.</li>
 * </ol>
 * Nhãn được khoá theo URL chứ không theo docId, nên vẫn dùng lại được sau
 * khi crawl lại corpus.
 */
public class PoolBuilder {

    /** Số kết quả đầu lấy từ mỗi cấu hình để đưa vào pool. */
    public static final int POOL_DEPTH = 10;

    /** Một mục cần người gán nhãn. */
    public static class PoolEntry {
        public String url;
        public String title;
        public String snippet;
        /** Điền tay: 0 = không liên quan, 1 = liên quan, 2 = rất liên quan. */
        public Integer relevance;
        /** Các cấu hình đã đưa tài liệu này vào top — chỉ để tham khảo. */
        public List<String> foundBy = new ArrayList<>();
    }

    /** Toàn bộ pool của một truy vấn. */
    public static class QueryPool {
        public String query;
        public List<PoolEntry> documents = new ArrayList<>();
    }

    private final InvertedIndex index;
    private final EvaluationHarness harness;

    public PoolBuilder(InvertedIndex index, Map<Integer, Double> pageRankScores) {
        this.index = index;
        this.harness = new EvaluationHarness(index, pageRankScores);
    }

    /**
     * Dựng pool cho một danh sách truy vấn, gộp top-{@link #POOL_DEPTH} của
     * mọi cấu hình truyền vào.
     */
    public List<QueryPool> buildPools(List<String> queries,
                                       List<EvaluationHarness.RankingConfig> configs) {
        Map<String, WebDocument> byUrl = new LinkedHashMap<>();
        for (WebDocument doc : index.getAllDocuments().values()) {
            byUrl.put(doc.getUrl(), doc);
        }

        List<QueryPool> pools = new ArrayList<>();
        for (String query : queries) {
            QueryPool pool = new QueryPool();
            pool.query = query;

            // LinkedHashSet giữ thứ tự xuất hiện, nên tài liệu được cấu hình
            // đầu tiên xếp cao sẽ nằm đầu danh sách cần gán nhãn — người gán
            // gặp các ứng viên khả năng liên quan cao nhất trước.
            Map<String, PoolEntry> entries = new LinkedHashMap<>();
            for (EvaluationHarness.RankingConfig config : configs) {
                for (String url : harness.search(query, config, POOL_DEPTH)) {
                    PoolEntry entry = entries.computeIfAbsent(url, u -> {
                        PoolEntry e = new PoolEntry();
                        e.url = u;
                        WebDocument doc = byUrl.get(u);
                        e.title = doc != null ? doc.getTitle() : "";
                        e.snippet = doc != null ? shorten(doc.getBodyText()) : "";
                        e.relevance = null; // để trống cho người gán
                        return e;
                    });
                    entry.foundBy.add(config.label());
                }
            }
            pool.documents.addAll(entries.values());
            pools.add(pool);
        }
        return pools;
    }

    private static String shorten(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    public static void writePools(List<QueryPool> pools, String path) throws IOException {
        Path filePath = Path.of(path);
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(new File(path), pools);
    }

    /**
     * Đọc file nhãn đã gán tay thành cấu trúc {@code truy vấn -> (URL -> mức độ)}
     * dùng được ngay cho {@link EvaluationMetrics}. Mục chưa gán nhãn
     * ({@code relevance = null}) được coi là không liên quan, đúng quy ước TREC.
     */
    public static Map<String, Map<String, Integer>> loadQrels(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        QueryPool[] pools = mapper.readValue(new File(path), QueryPool[].class);
        Map<String, Map<String, Integer>> qrels = new LinkedHashMap<>();
        for (QueryPool pool : pools) {
            Map<String, Integer> judgments = new LinkedHashMap<>();
            for (PoolEntry entry : pool.documents) {
                judgments.put(entry.url, entry.relevance == null ? 0 : entry.relevance);
            }
            qrels.put(pool.query, judgments);
        }
        return qrels;
    }

    /** Thống kê nhanh về pool, để biết khối lượng gán nhãn thực tế. */
    public static String summarise(List<QueryPool> pools) {
        int total = 0;
        int max = 0;
        Set<String> uniqueUrls = new LinkedHashSet<>();
        for (QueryPool pool : pools) {
            total += pool.documents.size();
            max = Math.max(max, pool.documents.size());
            for (PoolEntry entry : pool.documents) {
                uniqueUrls.add(entry.url);
            }
        }
        return String.format(
                "Pool: %d truy van, %d muc can gan nhan (trung binh %.1f/truy van, nhieu nhat %d), "
                        + "%d URL phan biet.",
                pools.size(), total, pools.isEmpty() ? 0 : (double) total / pools.size(), max,
                uniqueUrls.size());
    }
}
