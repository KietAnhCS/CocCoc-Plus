package com.vnsearch.query;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử cơ chế <b>lui dần về AND-của-tập-con</b>.
 *
 * <p>Corpus dùng từ đơn (không phải từ ghép) để bài kiểm thử không phụ thuộc
 * vào nội dung từ điển bigram — nếu ai đó thêm/bớt một mục trong
 * {@code vietnamese-bigrams.txt}, các test này vẫn nói đúng điều chúng định nói.
 *
 * <pre>
 *   df(sách) = 3   df(xe) = 1   df(hoa) = 1
 * </pre>
 */
class CandidateResolverTest {

    private InvertedIndex index;
    private final QueryParser parser = new QueryParser();

    private static WebDocument document(int docId, String body) {
        WebDocument doc = new WebDocument();
        doc.setDocId(docId);
        doc.setUrl("http://x.vn/" + docId);
        doc.setTitle("Tài liệu " + docId);
        doc.setBodyText(body);
        return doc;
    }

    @BeforeEach
    void setUp() {
        index = new InvertedIndex();
        index.addDocument(document(0, "sách xe"));
        index.addDocument(document(1, "sách"));
        index.addDocument(document(2, "sách"));
        index.addDocument(document(3, "hoa"));
    }

    private CandidateResolver.ResolvedQuery resolve(String query) {
        return CandidateResolver.resolve(index, parser.parse(query));
    }

    @Test
    @DisplayName("Corpus dựng đúng như giả định của các test bên dưới")
    void corpusAssumptions() {
        assertEquals(3, index.getDocumentFrequency("sách"));
        assertEquals(1, index.getDocumentFrequency("xe"));
        assertEquals(1, index.getDocumentFrequency("hoa"));
        assertEquals(0, index.getDocumentFrequency("khongtontai"));
    }

    @Test
    @DisplayName("Truy vấn khớp đầy đủ thì KHÔNG nới lỏng gì")
    void fullMatchIsNotRelaxed() {
        CandidateResolver.ResolvedQuery resolved = resolve("sách xe");

        assertEquals(List.of(0), resolved.candidateDocIds());
        assertFalse(resolved.wasRelaxed());
        assertTrue(resolved.droppedTerms().isEmpty());
    }

    @Test
    @DisplayName("Term không có trong corpus bị bỏ, phần còn lại vẫn cho kết quả")
    void unknownTermIsDropped() {
        CandidateResolver.ResolvedQuery resolved = resolve("sách khongtontai");

        assertEquals(List.of(0, 1, 2), resolved.candidateDocIds());
        assertTrue(resolved.wasRelaxed());
        assertEquals(List.of("khongtontai"), resolved.droppedTerms());
    }

    @Test
    @DisplayName("Nhiều term lạ cùng lúc bị bỏ trong MỘT bước")
    void allUnknownTermsDroppedAtOnce() {
        CandidateResolver.ResolvedQuery resolved = resolve("sách khongtontai lalala");

        assertEquals(List.of(0, 1, 2), resolved.candidateDocIds());
        assertEquals(2, resolved.droppedTerms().size());
    }

    @Test
    @DisplayName("Giao rỗng: bỏ term PHỔ BIẾN nhất trước, giữ term hiếm mang nhiều thông tin hơn")
    void dropsMostCommonTermFirst() {
        // Không tài liệu nào chứa cả "sách" lẫn "hoa" -> giao rỗng.
        CandidateResolver.ResolvedQuery resolved = resolve("sách hoa");

        // df(sách)=3 > df(hoa)=1 nên "sách" bị bỏ, "hoa" được giữ -> doc 3.
        assertEquals(List.of(3), resolved.candidateDocIds());
        assertEquals(List.of("sách"), resolved.droppedTerms());
    }

    @Test
    @DisplayName("Việc CHẤM ĐIỂM vẫn dùng nguyên tần suất term của truy vấn GỐC")
    void scoringKeepsOriginalQueryTerms() {
        CandidateResolver.ResolvedQuery resolved = resolve("sách hoa");

        // "sách" đã bị bỏ khỏi khâu TRUY HỒI nhưng phải còn trong khâu CHẤM ĐIỂM:
        // đó chính là thứ phân biệt tài liệu khớp 2/2 term với tài liệu khớp 1/2.
        assertTrue(resolved.queryTermFrequency().containsKey("sách"));
        assertTrue(resolved.queryTermFrequency().containsKey("hoa"));
    }

    @Test
    @DisplayName("Một term duy nhất không khớp thì không có gì để nới lỏng")
    void singleUnmatchedTermStaysEmpty() {
        CandidateResolver.ResolvedQuery resolved = resolve("khongtontai");

        assertTrue(resolved.candidateDocIds().isEmpty());
        assertFalse(resolved.wasRelaxed());
    }

    @Test
    @DisplayName("Cụm từ trong ngoặc kép KHÔNG bao giờ bị bỏ — đó là ý định tường minh")
    void quotedPhraseIsNeverDropped() {
        CandidateResolver.ResolvedQuery resolved = resolve("\"khongtontai\" sách");

        // Cụm không thể khớp -> rỗng, và hệ thống không được tự ý bỏ cụm đi
        // để trả về tài liệu chứa "sách".
        assertTrue(resolved.candidateDocIds().isEmpty());
        assertTrue(resolved.droppedTerms().isEmpty());
    }

    @Test
    @DisplayName("Term bị loại trừ vẫn được tôn trọng sau khi nới lỏng")
    void exclusionSurvivesRelaxation() {
        // "sách hoa" giao rỗng -> bỏ "sách" -> còn "hoa" (doc 3); "-hoa" loại doc 3.
        CandidateResolver.ResolvedQuery resolved = resolve("sách hoa -hoa");

        assertTrue(resolved.candidateDocIds().isEmpty(),
                "bỏ một mệnh đề NOT sẽ THÊM vào đúng thứ người dùng nói rõ là không muốn");
    }

    @Test
    @DisplayName("Nhóm OR mà mọi vế đều không tồn tại thì thoát ngay, không thử vô ích")
    void unmatchableOrGroupBailsOut() {
        CandidateResolver.ResolvedQuery resolved = resolve("sách khonga OR khongb");

        assertTrue(resolved.candidateDocIds().isEmpty());
        assertTrue(resolved.droppedTerms().isEmpty());
    }

    @Test
    @DisplayName("Ứng viên sau khi nới lỏng vẫn sắp xếp tăng dần theo docId")
    void relaxedCandidatesStaySorted() {
        List<Integer> candidates = resolve("sách khongtontai").candidateDocIds();

        for (int i = 1; i < candidates.size(); i++) {
            assertTrue(candidates.get(i) > candidates.get(i - 1),
                    "bất biến 'sắp xếp tăng dần' phải giữ trên CẢ đường nới lỏng");
        }
    }

    @Test
    @DisplayName("Truy vấn rỗng cho kết quả rỗng, không ném ngoại lệ")
    void emptyQuery() {
        assertTrue(resolve("").candidateDocIds().isEmpty());
        assertTrue(resolve("   ").candidateDocIds().isEmpty());
    }
}
