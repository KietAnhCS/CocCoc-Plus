package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiem thu Floyd heapify O(n) va viec dong bang SparseMatrix sang CSR. */
class HeapifyAndFreezeTest {

    // --- MinHeap: Floyd heapify ---

    @Test
    void heapifyProducesValidHeapOrder() {
        List<Integer> values = List.of(5, 3, 8, 1, 9, 2, 7, 4, 6);
        MinHeap<Integer> heap = new MinHeap<Integer>(values, Comparator.naturalOrder());

        List<Integer> extracted = new ArrayList<>();
        while (!heap.isEmpty()) {
            extracted.add(heap.extractMin());
        }
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), extracted);
    }

    @Test
    void heapifyOnEmptyCollectionWorks() {
        MinHeap<Integer> heap = new MinHeap<Integer>(List.<Integer>of(), Comparator.naturalOrder());
        assertTrue(heap.isEmpty());
        assertEquals(0, heap.size());
    }

    @Test
    void heapifyMatchesRepeatedInsertOnRandomData() {
        Random random = new Random(42);
        for (int trial = 0; trial < 30; trial++) {
            List<Integer> values = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                values.add(random.nextInt(1000));
            }

            MinHeap<Integer> viaHeapify = new MinHeap<Integer>(values, Comparator.naturalOrder());
            MinHeap<Integer> viaInsert = new MinHeap<Integer>(Comparator.naturalOrder());
            values.forEach(viaInsert::insert);

            while (!viaHeapify.isEmpty()) {
                assertEquals(viaInsert.extractMin(), viaHeapify.extractMin());
            }
            assertTrue(viaInsert.isEmpty());
        }
    }

    @Test
    void heapMaintainsMinimumAfterMixedOperations() {
        MinHeap<Integer> heap = new MinHeap<Integer>(List.of(10, 20, 30), Comparator.naturalOrder());
        heap.insert(5);
        assertEquals(5, heap.peek());
        heap.extractMin();
        assertEquals(10, heap.peek());
        heap.insert(1);
        assertEquals(1, heap.peek());
    }

    @Test
    void topKStillCorrectAfterHoleOptimisation() {
        List<Integer> values = List.of(5, 3, 8, 1, 9, 2, 7);
        assertEquals(List.of(9, 8, 7), MinHeap.topK(values, 3, Comparator.naturalOrder()));
        assertEquals(List.of(9, 8, 7, 5, 3, 2, 1),
                MinHeap.topK(values, 100, Comparator.naturalOrder()));
    }

    // --- SparseMatrix: dong bang sang CSR ---

    @Test
    void frozenMatrixProducesSameResultAsAdjacencyList() {
        SparseMatrix m = new SparseMatrix(3, 3);
        m.set(0, 1, 0.5);
        m.set(1, 2, 1.0);
        m.set(2, 0, 0.5);
        m.set(2, 1, 0.5);

        double[] vector = {1.0, 2.0, 3.0};
        double[] beforeFreeze = m.multiply(vector);

        assertFalse(m.isFrozen());
        m.freeze();
        assertTrue(m.isFrozen());

        assertArrayEquals(beforeFreeze, m.multiply(vector), 1e-12,
                "CSR phai cho ket qua Y HET adjacency list");
    }

    @Test
    void freezePreservesNnz() {
        SparseMatrix m = new SparseMatrix(100, 100);
        m.set(0, 1, 1.0);
        m.set(50, 99, 1.0);
        m.set(99, 0, 1.0);

        int before = m.nnz();
        m.freeze();
        assertEquals(before, m.nnz());
    }

    @Test
    void freezeReducesEstimatedMemory() {
        SparseMatrix m = new SparseMatrix(1000, 1000);
        for (int i = 0; i < 1000; i++) {
            m.set(i, (i * 7) % 1000, 1.0);
        }
        long before = m.estimatedBytes();
        m.freeze();
        assertTrue(m.estimatedBytes() < before,
                "CSR phai ton it bo nho hon: " + m.estimatedBytes() + " vs " + before);
    }

    @Test
    void setAfterFreezeIsRejected() {
        SparseMatrix m = new SparseMatrix(2, 2);
        m.set(0, 0, 1.0);
        m.freeze();
        assertThrows(IllegalStateException.class, () -> m.set(1, 1, 1.0));
    }

    @Test
    void freezeIsIdempotent() {
        SparseMatrix m = new SparseMatrix(2, 2);
        m.set(0, 1, 2.0);
        m.freeze().freeze();
        assertArrayEquals(new double[]{6.0, 0.0}, m.multiply(new double[]{1.0, 3.0}), 1e-12);
    }

    @Test
    void densityMatchesNnzOverCells() {
        SparseMatrix m = new SparseMatrix(10, 10);
        m.set(0, 0, 1.0);
        m.set(5, 5, 1.0);
        assertEquals(2.0 / 100, m.density(), 1e-12);
    }

    @Test
    void emptyFrozenMatrixMultipliesToZeros() {
        SparseMatrix m = new SparseMatrix(3, 3).freeze();
        assertArrayEquals(new double[]{0, 0, 0}, m.multiply(new double[]{1, 2, 3}), 1e-12);
    }
}
