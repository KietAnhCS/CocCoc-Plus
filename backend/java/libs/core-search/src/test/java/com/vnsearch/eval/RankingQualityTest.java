package com.vnsearch.eval;

import com.vnsearch.crawler.ContentStorage;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.TfIdfScorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Cổng chặn hồi quy chất lượng xếp hạng.</b>
 *
 * <p><b>Vì sao bài test này tồn tại.</b> 389 bài test còn lại đều kiểm tra từng
 * khối rời rạc: tokenizer tách đúng chưa, VByte giải nén ra đúng số cũ chưa,
 * MinHeap có giữ đúng bất biến không. Không bài nào trong số đó trả lời được
 * câu hỏi mà người dùng thực sự quan tâm: <i>gõ một truy vấn vào thì có ra đúng
 * trang cần tìm không</i>.
 *
 * <p>Khoảng trống đó không phải giả định. Mọi thay đổi trong tokenizer, trong
 * danh sách stopword, trong công thức TF-IDF, trong cách chọn ứng viên — đều có
 * thể làm tụt chất lượng tìm kiếm <b>mà toàn bộ 389 bài test kia vẫn xanh</b>,
 * vì từng khối vẫn hoạt động đúng y như đặc tả của nó. Chỉ có phép đo đầu-cuối
 * mới bắt được loại hỏng này.
 *
 * <p><b>Cách đo.</b> Dùng lại chính bộ máy của {@link EvaluationRunner} —
 * known-item search: chọn trước một tài liệu, sinh truy vấn từ các từ khoá đặc
 * trưng nhất của nó, và tài liệu đó là đáp án đúng duy nhất. Đo bằng <b>MRR</b>
 * (trung bình nghịch đảo thứ hạng). Chi tiết phương pháp: {@code docs/EVALUATION.md}.
 *
 * <p><b>Vì sao chạy trên corpus seed chứ không phải corpus đã crawl.</b> Corpus
 * đã crawl nằm trong {@code .gitignore} — nó không có trên máy CI, và nó đổi
 * mỗi lần crawl lại. Một cổng chặn phải cho kết quả <b>y hệt nhau ở mọi lần
 * chạy</b>, nếu không thì nó là một nguồn báo động giả và sẽ bị tắt trong vòng
 * một tháng. Corpus seed đi kèm repo, cố định, 40 tài liệu.
 *
 * <p><b>Bài test này đo được gì và KHÔNG đo được gì.</b> Trên corpus seed, giá
 * trị đo được hiện tại là <b>MRR = 1,0000</b> trên 34 truy vấn — tức độ đo đã
 * <i>bão hoà</i>. Đó không phải bằng chứng rằng bộ xếp hạng hoàn hảo; đó là hệ
 * quả của corpus nhỏ, đúng như mục 6 của {@code docs/EVALUATION.md} đã cảnh
 * báo: khi rất ít tài liệu chứa một term, phép giao posting list trả về gần như
 * đúng một kết quả và hệ thống nào cũng đạt MRR = 1,0.
 *
 * <p>Nên hãy đọc đúng bản chất của nó: đây là một <b>bộ báo hỏng</b>, không
 * phải một <b>thước đo chất lượng</b>. Nó bắt được các lần gãy thật sự —
 * tokenizer hỏng, bộ chọn ứng viên trả về rỗng, công thức chấm điểm đảo ngược
 * thứ tự — nhưng nó <b>không</b> phân biệt được một cải tiến xếp hạng tốt với
 * một cải tiến tầm thường. Muốn đo điều đó thì phải chạy {@link EvaluationRunner}
 * trên corpus 5.011 trang, và việc đó không chạy được trên CI.
 *
 * <p>Ngưỡng được đặt thấp hơn giá trị đo được một khoảng, để một tinh chỉnh nhỏ
 * không làm đỏ bộ test — nếu ghim chặt bằng đúng 1,0 thì người ta sẽ sửa ngưỡng
 * thay vì sửa lỗi, và cổng chặn mất tác dụng.
 */
class RankingQualityTest {

    /** Corpus mẫu đi kèm repo. Cố định, nên kết quả lặp lại được. */
    /**
     * Corpus mẫu đi kèm repo.
     *
     * <p>Đường dẫn lấy từ property {@code vnsearch.data.dir} do surefire đặt
     * (xem POM cha), KHÔNG phải một chuỗi tương đối. Lý do: sau khi backend
     * tách thành reactor đa module, surefire chạy mỗi module trong thư mục của
     * chính module đó, nên {@code data/...} không còn giải đúng.
     *
     * <p>Lối lùi {@code "../../data"} dành cho lúc chạy bài test thẳng từ IDE,
     * nơi property kia không có mặt.
     */
    private static final String SEED_CORPUS =
            System.getProperty("vnsearch.data.dir", "../../data") + "/seed-documents.json";

    /** Seed ngẫu nhiên cố định: cùng một bộ truy vấn ở mọi lần chạy. */
    private static final long RANDOM_SEED = 42L;

    private static final int NUM_QUERIES = 40;
    private static final int TERMS_PER_QUERY = 3;
    private static final int TOP_N = 10;

    /**
     * Ngưỡng tối thiểu cho MRR.
     *
     * <p>Nếu bài test này đỏ sau một thay đổi, câu hỏi đúng KHÔNG phải "hạ
     * ngưỡng xuống bao nhiêu" mà là "thay đổi vừa rồi làm hỏng cái gì".
     */
    private static final double MIN_MRR = 0.90;

    /** Ngưỡng tối thiểu cho tỷ lệ tài liệu đích lọt vào top 5. */
    private static final double MIN_SUCCESS_AT_5 = 0.95;

    @Test
    @DisplayName("Chất lượng xếp hạng không tụt dưới ngưỡng đã chốt")
    void rankingQualityStaysAboveThreshold() throws IOException {
        if (!Files.exists(Path.of(SEED_CORPUS))) {
            throw new IllegalStateException(
                    "Không tìm thấy " + SEED_CORPUS + ". Bài test này chạy với thư mục làm việc"
                            + " là search-engine/ — kiểm tra lại cấu hình surefire.");
        }

        List<WebDocument> docs = ContentStorage.loadFromJson(SEED_CORPUS);
        assertFalse(docs.isEmpty(), "Corpus seed rỗng");

        InvertedIndex index = buildIndex(docs);
        Map<Integer, Double> pageRank =
                new PageRankService().computePageRank(index.getAllDocuments()).scores();

        List<KnownItemQueryGenerator.KnownItemQuery> queries =
                new KnownItemQueryGenerator().generate(index, NUM_QUERIES, TERMS_PER_QUERY, RANDOM_SEED);
        assertFalse(queries.isEmpty(),
                "Không sinh được truy vấn known-item nào từ corpus seed — bộ sinh truy vấn hỏng,"
                        + " hoặc corpus không còn term nào thoả điều kiện document frequency.");

        EvaluationHarness harness = new EvaluationHarness(index, pageRank);
        // Dùng ĐÚNG cấu hình mặc định của ứng dụng (xem application.properties:
        // app.ranking.scorer=tfidf, beta=0.30, gamma=0.10). Đo một cấu hình khác
        // với cấu hình đang phục vụ thì con số đo được không nói lên điều gì.
        EvaluationHarness.RankingConfig config = EvaluationHarness.RankingConfig.of(
                "TF-IDF + PageRank + title (mặc định)", new TfIdfScorer(), pageRank, 0.30, 0.10);

        List<Double> reciprocalRanks = new ArrayList<>(queries.size());
        double hitsAtFive = 0;
        for (KnownItemQueryGenerator.KnownItemQuery query : queries) {
            List<String> ranked = harness.search(query.queryText(), config, TOP_N);
            reciprocalRanks.add(EvaluationMetrics.reciprocalRank(ranked, query.targetUrl()));
            hitsAtFive += EvaluationMetrics.successAtK(ranked, query.targetUrl(), 5);
        }

        double mrr = EvaluationMetrics.meanReciprocalRank(reciprocalRanks);
        double successAtFive = hitsAtFive / queries.size();

        // In ra để đọc được trong log CI ngay cả khi test XANH: một con số tụt
        // dần qua nhiều lần commit mà vẫn trên ngưỡng là thứ chỉ nhìn lịch sử
        // mới thấy.
        System.out.printf("[chất lượng xếp hạng] %d truy vấn trên %d tài liệu"
                        + " — MRR = %.4f (ngưỡng %.2f), Success@5 = %.4f (ngưỡng %.2f)%n",
                queries.size(), docs.size(), mrr, MIN_MRR, successAtFive, MIN_SUCCESS_AT_5);

        assertTrue(mrr >= MIN_MRR, String.format(
                "MRR tụt xuống %.4f, dưới ngưỡng %.2f. Thay đổi vừa rồi làm giảm chất lượng"
                        + " tìm kiếm — đừng hạ ngưỡng, hãy tìm xem nó làm hỏng cái gì.", mrr, MIN_MRR));
        assertTrue(successAtFive >= MIN_SUCCESS_AT_5, String.format(
                "Success@5 tụt xuống %.4f, dưới ngưỡng %.2f.", successAtFive, MIN_SUCCESS_AT_5));
    }

    /** Giống hệt cách {@link EvaluationRunner} dựng chỉ mục, để hai bên đo cùng một thứ. */
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
