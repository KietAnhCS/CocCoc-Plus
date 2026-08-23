package com.vnsearch.index;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostingCursorTest {

    private static List<Posting> postings(int... docIds) {
        List<Posting> list = new ArrayList<>(docIds.length);
        for (int docId : docIds) {
            list.add(new Posting(docId, 1, new int[]{0}));
        }
        return list;
    }

    @Test
    void emptyCursorIsImmediatelyExhausted() {
        PostingCursor cursor = PostingCursor.of(List.of());
        assertEquals(PostingCursor.NO_MORE, cursor.docId());
        assertFalse(cursor.next());
        assertFalse(cursor.skipTo(0));
    }

    @Test
    void iteratesInAscendingOrder() {
        PostingCursor cursor = PostingCursor.of(postings(1, 3, 5, 7));
        assertEquals(1, cursor.docId());
        assertTrue(cursor.next());
        assertEquals(3, cursor.docId());
        assertTrue(cursor.next());
        assertEquals(5, cursor.docId());
        assertTrue(cursor.next());
        assertEquals(7, cursor.docId());
        assertFalse(cursor.next());
        assertEquals(PostingCursor.NO_MORE, cursor.docId());
    }

    @Test
    void skipToLandsOnExactMatch() {
        PostingCursor cursor = PostingCursor.of(postings(1, 3, 5, 7, 9));
        assertTrue(cursor.skipTo(5));
        assertEquals(5, cursor.docId());
    }

    @Test
    void skipToLandsOnFirstGreaterWhenNoExactMatch() {
        PostingCursor cursor = PostingCursor.of(postings(1, 3, 5, 7, 9));
        assertTrue(cursor.skipTo(4));
        assertEquals(5, cursor.docId(), "Phai dung o phan tu DAU TIEN >= muc tieu");
    }

    @Test
    void skipToBeyondEndExhaustsCursor() {
        PostingCursor cursor = PostingCursor.of(postings(1, 3, 5));
        assertFalse(cursor.skipTo(100));
        assertEquals(PostingCursor.NO_MORE, cursor.docId());
    }

    @Test
    void skipToBackwardsIsNoOp() {
        PostingCursor cursor = PostingCursor.of(postings(1, 3, 5, 7));
        cursor.skipTo(5);
        assertTrue(cursor.skipTo(2), "Nhay lui phai giu nguyen vi tri, khong lui lai");
        assertEquals(5, cursor.docId());
    }

    @Test
    void gallopingFindsEveryTargetInLargeList() {
        int size = 10_000;
        int[] docIds = new int[size];
        for (int i = 0; i < size; i++) {
            docIds[i] = i * 3; // 0, 3, 6, ...
        }
        for (int target : new int[]{0, 3, 1500, 14_997, 29_997}) {
            PostingCursor cursor = PostingCursor.of(postings(docIds));
            assertTrue(cursor.skipTo(target));
            assertEquals(target, cursor.docId());
        }
    }

    @Test
    void gallopingMatchesLinearScanOnEveryPosition() {
        int[] docIds = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
        for (int target = 0; target <= 1100; target++) {
            PostingCursor cursor = PostingCursor.of(postings(docIds));
            boolean found = cursor.skipTo(target);

            // Doi chieu voi quet tuyen tinh — nguon su that don gian.
            int expected = PostingCursor.NO_MORE;
            for (int docId : docIds) {
                if (docId >= target) {
                    expected = docId;
                    break;
                }
            }
            assertEquals(expected != PostingCursor.NO_MORE, found, "target=" + target);
            assertEquals(expected, cursor.docId(), "target=" + target);
        }
    }

    @Test
    void exposesTermFrequencyAndPositions() {
        List<Posting> list = List.of(new Posting(7, 3, new int[]{1, 5, 9}));
        PostingCursor cursor = PostingCursor.of(list);
        assertEquals(7, cursor.docId());
        assertEquals(3, cursor.termFrequency());
        assertArrayEquals(new int[]{1, 5, 9}, cursor.positions());
        assertEquals(1, cursor.size());
    }
}
