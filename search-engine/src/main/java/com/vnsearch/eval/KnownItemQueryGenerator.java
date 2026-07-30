package com.vnsearch.eval;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Posting;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.TfIdfScorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Sinh bộ truy vấn <b>known-item search</b> để đánh giá chất lượng xếp
 * hạng một cách TỰ ĐỘNG và KHÁCH QUAN.
 *
 * <p><b>Vấn đề cần giải:</b> muốn đo chất lượng tìm kiếm thì phải có nhãn
 * liên quan (qrels), mà nhãn liên quan thường phải do người gán tay — vừa
 * tốn công vừa chủ quan. Known-item search là phương pháp kinh điển né
 * được điều đó: thay vì hỏi "tài liệu nào liên quan tới truy vấn này",
 * ta lật ngược lại — chọn trước một tài liệu, sinh truy vấn TỪ CHÍNH nó,
 * và ground truth hiển nhiên chính là tài liệu đó. Mô phỏng đúng tình
 * huống người dùng nhớ mang máng một bài báo rồi gõ vài từ khoá để tìm
 * lại. Độ đo phù hợp là MRR và Success@k.
 *
 * <p><b>Chọn từ khoá thế nào cho truy vấn có ý nghĩa:</b> đây là chỗ dễ
 * làm sai nhất. Nếu chọn các term hiếm nhất (df = 1) thì phép giao posting
 * list chỉ còn đúng một tài liệu, hệ thống nào cũng đạt MRR = 1,0 và bài
 * đánh giá trở nên vô nghĩa. Vì vậy ta lọc theo khoảng df:
 * <ul>
 *   <li>{@code df >= MIN_DF}: loại bỏ term quá hiếm khiến truy vấn trở nên
 *       tầm thường, đồng thời loại nhiễu như lỗi chính tả hay mã số.</li>
 *   <li>{@code df <= maxDf} (mặc định 10% số tài liệu): loại term quá phổ
 *       biến vốn gần như không mang thông tin phân biệt.</li>
 * </ul>
 * Trong khoảng đó, chọn các term có điểm TF-IDF cao nhất, và <b>nhân đôi
 * điểm cho term xuất hiện trong tiêu đề</b> vì đó chính là thứ người dùng
 * nhớ và gõ lại.
 *
 * <p><b>Tính tái lập:</b> việc chọn tài liệu mẫu dùng {@link Random} với
 * seed truyền vào, nên chạy lại luôn ra đúng bộ truy vấn cũ — điều kiện
 * bắt buộc để các con số trong báo cáo kiểm chứng được.
 */
public class KnownItemQueryGenerator {

    /** Term phải xuất hiện trong ít nhất ngần này tài liệu mới được dùng. */
    public static final int MIN_DF = 3;

    /** Tỷ lệ tối đa số tài liệu chứa term (so với toàn corpus) để term còn mang tính phân biệt. */
    public static final double MAX_DF_RATIO = 0.10;

    /** Điểm nhân thêm cho term xuất hiện trong tiêu đề. */
    private static final double TITLE_BOOST = 2.0;

    private final VietnameseTokenizer tokenizer;

    public KnownItemQueryGenerator() {
        this(new VietnameseTokenizer());
    }

    public KnownItemQueryGenerator(VietnameseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Một truy vấn known-item: chuỗi truy vấn để đưa vào search engine, và
     * URL của tài liệu đích mà hệ thống lý tưởng phải trả về ở hạng 1.
     */
    public record KnownItemQuery(String queryText, String targetUrl, int targetDocId, List<String> terms) {
    }

    /**
     * Sinh tối đa {@code numQueries} truy vấn known-item từ corpus.
     *
     * @param index         chỉ mục đã dựng xong
     * @param numQueries    số truy vấn mong muốn
     * @param termsPerQuery số từ khoá mỗi truy vấn (2-4 là hợp lý, giống thói quen gõ thật)
     * @param seed          seed ngẫu nhiên, cố định để tái lập được kết quả
     */
    public List<KnownItemQuery> generate(InvertedIndex index, int numQueries, int termsPerQuery, long seed) {
        List<Integer> docIds = new ArrayList<>(index.getAllDocuments().keySet());
        docIds.sort(Integer::compareTo); // sắp xếp trước để seed cho ra cùng kết quả giữa các lần chạy
        java.util.Collections.shuffle(docIds, new Random(seed));

        int totalDocs = index.getTotalDocs();
        int maxDf = Math.max(MIN_DF, (int) Math.round(totalDocs * MAX_DF_RATIO));

        List<KnownItemQuery> queries = new ArrayList<>();
        Set<String> usedQueryTexts = new HashSet<>();

        for (int docId : docIds) {
            if (queries.size() >= numQueries) {
                break;
            }
            WebDocument doc = index.getDocument(docId);
            if (doc == null || doc.getUrl() == null) {
                continue;
            }
            List<String> terms = pickDistinctiveTerms(doc, index, totalDocs, maxDf, termsPerQuery);
            if (terms.size() < termsPerQuery) {
                continue; // tài liệu quá ngắn hoặc không đủ term phân biệt -> bỏ qua
            }
            // Thay "_" bằng khoảng trắng để truy vấn trông tự nhiên như người gõ;
            // QueryParser sẽ tự ghép lại thành đúng token như lúc index.
            String queryText = String.join(" ", terms).replace('_', ' ');
            if (!usedQueryTexts.add(queryText)) {
                continue; // tránh 2 tài liệu sinh ra cùng một truy vấn (ground truth sẽ nhập nhằng)
            }
            queries.add(new KnownItemQuery(queryText, doc.getUrl(), docId, terms));
        }
        return queries;
    }

    /**
     * Chọn {@code limit} term phân biệt nhất của một tài liệu theo TF-IDF,
     * chỉ xét các term có df nằm trong khoảng [MIN_DF, maxDf].
     */
    private List<String> pickDistinctiveTerms(WebDocument doc, InvertedIndex index,
                                               int totalDocs, int maxDf, int limit) {
        Set<String> titleTerms = new HashSet<>();
        for (VietnameseTokenizer.Token token : tokenizer.tokenize(doc.getTitle())) {
            titleTerms.add(token.term());
        }

        String combinedText = String.join(" ",
                doc.getTitle() != null ? doc.getTitle() : "",
                doc.getMetaDescription() != null ? doc.getMetaDescription() : "",
                doc.getBodyText() != null ? doc.getBodyText() : "");

        Map<String, Integer> termFrequency = new LinkedHashMap<>();
        for (VietnameseTokenizer.Token token : tokenizer.tokenize(combinedText)) {
            // Chỉ dùng dạng CÓ DẤU: chỉ mục lưu song song cả bản không dấu,
            // nếu lấy cả hai thì truy vấn sinh ra sẽ lặp cùng một từ hai lần.
            termFrequency.merge(token.term(), 1, Integer::sum);
        }

        record ScoredTerm(String term, double score) {
        }
        List<ScoredTerm> scored = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            String term = entry.getKey();
            List<Posting> postings = index.getPostings(term);
            int df = postings.size();
            if (df < MIN_DF || df > maxDf) {
                continue;
            }
            double score = TfIdfScorer.tf(entry.getValue()) * TfIdfScorer.idf(totalDocs, df);
            if (titleTerms.contains(term)) {
                score *= TITLE_BOOST;
            }
            scored.add(new ScoredTerm(term, score));
        }

        scored.sort(Comparator.comparingDouble(ScoredTerm::score).reversed());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            result.add(scored.get(i).term());
        }
        return result;
    }
}
