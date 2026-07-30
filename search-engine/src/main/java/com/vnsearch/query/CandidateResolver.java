package com.vnsearch.query;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Posting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Biến một {@link QueryParser.ParsedQuery} thành danh sách docId ứng viên,
 * bằng cách giao posting list của các term bắt buộc, lọc theo cụm từ, rồi
 * loại các tài liệu chứa term bị loại trừ.
 *
 * <p><b>Vì sao tách riêng thành một lớp:</b> logic này trước đây nằm trong
 * {@code SearchEngineFacade} dưới dạng phương thức private, nên bộ đánh giá
 * chất lượng không gọi lại được và buộc phải viết lại một bản sao. Hai bản
 * sao chắc chắn sẽ trôi lệch nhau theo thời gian, và khi đó mọi con số
 * trong báo cáo đánh giá đều mất giá trị vì chúng đo một đường đi khác với
 * đường đi mà hệ thống thật sự phục vụ người dùng. Tách ra đây để tầng REST
 * và bộ đánh giá dùng CHUNG đúng một cài đặt.
 *
 * <p>Độ phức tạp thời gian: chi phối bởi
 * {@link PostingListMerger#intersectAll} — tổng độ dài các posting list
 * tham gia, với tối ưu sắp xếp shortest-first.
 */
public final class CandidateResolver {

    private CandidateResolver() {
    }

    /**
     * Kết quả phân giải: danh sách ứng viên và tần suất term của truy vấn
     * (dùng lại cho khâu tính điểm nên trả về luôn, tránh tính hai lần).
     */
    public record ResolvedQuery(List<Integer> candidateDocIds, Map<String, Integer> queryTermFrequency) {
    }

    public static ResolvedQuery resolve(InvertedIndex index, QueryParser.ParsedQuery parsed) {
        List<String> allRequiredTerms = new ArrayList<>(parsed.mustTerms());
        for (List<String> phrase : parsed.phrases()) {
            allRequiredTerms.addAll(phrase);
        }

        Map<String, Integer> queryTermFrequency = new HashMap<>();
        for (String term : allRequiredTerms) {
            queryTermFrequency.merge(term, 1, Integer::sum);
        }

        if (allRequiredTerms.isEmpty()) {
            return new ResolvedQuery(new ArrayList<>(), queryTermFrequency);
        }

        List<List<Posting>> postingLists = new ArrayList<>();
        for (String term : allRequiredTerms) {
            List<Posting> postings = index.getPostings(term);
            if (postings.isEmpty()) {
                // AND ngầm định: chỉ cần một term không xuất hiện là kết quả rỗng.
                return new ResolvedQuery(new ArrayList<>(), queryTermFrequency);
            }
            postingLists.add(postings);
        }

        List<Integer> candidates = PostingListMerger.intersectAll(postingLists);

        if (!candidates.isEmpty() && !parsed.phrases().isEmpty()) {
            List<Integer> filtered = new ArrayList<>();
            for (int docId : candidates) {
                boolean allPhrasesMatch = true;
                for (List<String> phrase : parsed.phrases()) {
                    if (!PostingListMerger.matchesPhrase(index, phrase, docId)) {
                        allPhrasesMatch = false;
                        break;
                    }
                }
                if (allPhrasesMatch) {
                    filtered.add(docId);
                }
            }
            candidates = filtered;
        }

        if (!candidates.isEmpty() && !parsed.excludedTerms().isEmpty()) {
            Set<Integer> excludedDocIds = new HashSet<>();
            for (String excludedTerm : parsed.excludedTerms()) {
                for (Posting posting : index.getPostings(excludedTerm)) {
                    excludedDocIds.add(posting.docId());
                }
            }
            List<Integer> filtered = new ArrayList<>();
            for (int docId : candidates) {
                if (!excludedDocIds.contains(docId)) {
                    filtered.add(docId);
                }
            }
            candidates = filtered;
        }

        return new ResolvedQuery(candidates, queryTermFrequency);
    }
}
