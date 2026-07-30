package com.vnsearch.query.ast;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.PostingListMerger;

import java.util.List;

/**
 * Nut LA cua cay truy van: mot term don.
 *
 * <p>Ket qua chinh la posting list cua term do — da sap xep tang dan theo
 * docId nho bat bien cua {@link SearchIndex}, nen cac nut cha ghep lai duoc
 * bang two-pointer ma khong phai sort.
 */
public record TermNode(String term) implements QueryNode {

    @Override
    public List<Integer> evaluate(SearchIndex index) {
        return PostingListMerger.docIdsOf(index.getPostings(term));
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        return index.getDocumentFrequency(term); // df chinh la so ket qua, O(1)
    }

    @Override
    public String describe() {
        return term;
    }
}
