package com.vnsearch.query;

import com.vnsearch.index.Posting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostingListMergerTest {

    private Posting p(int docId) {
        return new Posting(docId, 1, List.of(0));
    }

    @Test
    void intersectWithEmptyListsIsEmpty() {
        assertTrue(PostingListMerger.intersect(List.of(), List.of()).isEmpty());
        assertTrue(PostingListMerger.intersect(List.of(1, 2), List.of()).isEmpty());
    }

    @Test
    void intersectFindsCommonDocIds() {
        List<Integer> a = List.of(1, 3, 5, 7);
        List<Integer> b = List.of(2, 3, 5, 8);
        assertEquals(List.of(3, 5), PostingListMerger.intersect(a, b));
    }

    @Test
    void intersectWithNoOverlapIsEmpty() {
        List<Integer> a = List.of(1, 2, 3);
        List<Integer> b = List.of(4, 5, 6);
        assertTrue(PostingListMerger.intersect(a, b).isEmpty());
    }

    @Test
    void unionCombinesAndDeduplicates() {
        List<Integer> a = List.of(1, 3, 5);
        List<Integer> b = List.of(2, 3, 5, 8);
        assertEquals(List.of(1, 2, 3, 5, 8), PostingListMerger.union(a, b));
    }

    @Test
    void unionWithEmptyListReturnsTheOther() {
        List<Integer> a = List.of(1, 2, 3);
        assertEquals(a, PostingListMerger.union(a, List.of()));
        assertEquals(a, PostingListMerger.union(List.of(), a));
    }

    @Test
    void docIdsOfExtractsInOrder() {
        List<Posting> postings = List.of(p(1), p(4), p(9));
        assertEquals(List.of(1, 4, 9), PostingListMerger.docIdsOf(postings));
    }

    @Test
    void intersectAllOfThreeTermsFindsCommonDocs() {
        // term A: doc 1,2,3,4,5   term B: doc 2,3,5   term C: doc 3,5,9
        List<Posting> a = List.of(p(1), p(2), p(3), p(4), p(5));
        List<Posting> b = List.of(p(2), p(3), p(5));
        List<Posting> c = List.of(p(3), p(5), p(9));

        List<Integer> result = PostingListMerger.intersectAll(List.of(a, b, c));
        assertEquals(List.of(3, 5), result);
    }

    @Test
    void intersectAllWithEmptyInputReturnsEmpty() {
        assertTrue(PostingListMerger.intersectAll(List.of()).isEmpty());
    }

    @Test
    void intersectAllShortCircuitsWhenOneListIsEmpty() {
        List<Posting> a = List.of(p(1), p(2));
        List<Posting> empty = List.of();
        assertTrue(PostingListMerger.intersectAll(List.of(a, empty)).isEmpty());
    }
}
