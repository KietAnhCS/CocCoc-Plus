package com.vnsearch.query.ast;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.PostingListMerger;

import java.util.ArrayList;
import java.util.List;

/**
 * Nut LA cua cay truy van: mot cum tu phai xuat hien LIEN TIEP dung thu tu.
 *
 * <p><b>Filter-and-refine.</b> Dieu kien "lien tiep" KEO THEO dieu kien "cung
 * co mat", nen ta dung dieu kien yeu hon nhung RE hon (giao posting list) de
 * thu hep tap truoc, roi moi kiem tra "lien tiep" (dat, phai binary search
 * tren danh sach vi tri) tren tap nho con lai.
 *
 * <p>Neu bo buoc loc tho, phai chay {@code matchesPhrase} tren toan bo 5.011
 * tai lieu thay vi vai chuc — cham hon khoang 100 lan.
 */
public record PhraseNode(List<String> terms) implements QueryNode {

    @Override
    public List<Integer> evaluate(SearchIndex index) {
        if (terms.isEmpty()) {
            return List.of();
        }
        List<QueryNode> asTerms = new ArrayList<>(terms.size());
        for (String term : terms) {
            asTerms.add(new TermNode(term));
        }
        List<Integer> rough = new AndNode(asTerms).evaluate(index); // loc tho

        List<Integer> exact = new ArrayList<>(rough.size());
        for (int docId : rough) {
            if (PostingListMerger.matchesPhrase(index, terms, docId)) { // loc chinh xac
                exact.add(docId);
            }
        }
        return exact;
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        int min = Integer.MAX_VALUE;
        for (String term : terms) {
            min = Math.min(min, index.getDocumentFrequency(term));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    @Override
    public String describe() {
        return "\"" + String.join(" ", terms) + "\"";
    }
}
