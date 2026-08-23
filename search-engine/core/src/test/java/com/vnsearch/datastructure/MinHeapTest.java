package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinHeapTest {

    @Test
    void peekAndExtractOnEmptyHeapThrows() {
        MinHeap<Integer> heap = new MinHeap<Integer>(Comparator.naturalOrder());
        assertTrue(heap.isEmpty());
        assertThrows(NoSuchElementException.class, heap::peek);
        assertThrows(NoSuchElementException.class, heap::extractMin);
    }

    @Test
    void singleElement() {
        MinHeap<Integer> heap = new MinHeap<Integer>(Comparator.naturalOrder());
        heap.insert(42);
        assertEquals(42, heap.peek());
        assertEquals(42, heap.extractMin());
        assertTrue(heap.isEmpty());
    }

    @Test
    void extractsInAscendingOrder() {
        MinHeap<Integer> heap = new MinHeap<Integer>(Comparator.naturalOrder());
        int[] values = {5, 3, 8, 1, 9, 2, 7, 1};
        for (int v : values) {
            heap.insert(v);
        }
        int prev = Integer.MIN_VALUE;
        while (!heap.isEmpty()) {
            int next = heap.extractMin();
            assertTrue(next >= prev, "Phai tra ve theo thu tu tang dan");
            prev = next;
        }
    }

    @Test
    void duplicateValuesHandledCorrectly() {
        MinHeap<Integer> heap = new MinHeap<Integer>(Comparator.naturalOrder());
        heap.insert(5);
        heap.insert(5);
        heap.insert(5);
        assertEquals(3, heap.size());
        assertEquals(5, heap.extractMin());
        assertEquals(5, heap.extractMin());
        assertEquals(5, heap.extractMin());
        assertTrue(heap.isEmpty());
    }

    @Test
    void topKReturnsDescendingLargest() {
        List<Integer> values = List.of(5, 3, 8, 1, 9, 2, 7);
        List<Integer> top3 = MinHeap.topK(values, 3, Comparator.naturalOrder());
        assertEquals(List.of(9, 8, 7), top3);
    }

    @Test
    void topKWithEmptyCollectionReturnsEmpty() {
        List<Integer> top = MinHeap.topK(List.<Integer>of(), 3, Comparator.naturalOrder());
        assertTrue(top.isEmpty());
    }

    @Test
    void topKWithKGreaterThanSizeReturnsAllSorted() {
        List<Integer> values = List.of(3, 1, 2);
        List<Integer> top = MinHeap.topK(values, 10, Comparator.naturalOrder());
        assertEquals(List.of(3, 2, 1), top);
    }

    @Test
    void topKWithKZeroReturnsEmpty() {
        List<Integer> top = MinHeap.topK(List.of(1, 2, 3), 0, Comparator.naturalOrder());
        assertTrue(top.isEmpty());
    }
}
