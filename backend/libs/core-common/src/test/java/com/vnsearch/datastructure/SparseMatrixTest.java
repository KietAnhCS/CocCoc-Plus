package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SparseMatrixTest {

    @Test
    void multiplyOnEmptyMatrixReturnsZeroVector() {
        SparseMatrix m = new SparseMatrix(3, 3);
        double[] result = m.multiply(new double[]{1, 2, 3});
        assertArrayEquals(new double[]{0, 0, 0}, result, 1e-9);
        assertEquals(0, m.nnz());
    }

    @Test
    void singleEntryMultiply() {
        SparseMatrix m = new SparseMatrix(2, 2);
        m.set(0, 1, 2.0);
        double[] result = m.multiply(new double[]{5.0, 3.0});
        assertArrayEquals(new double[]{6.0, 0.0}, result, 1e-9);
    }

    @Test
    void multipleEntriesSameRowAreSummed() {
        SparseMatrix m = new SparseMatrix(1, 3);
        m.set(0, 0, 1.0);
        m.set(0, 1, 2.0);
        m.set(0, 2, 3.0);
        double[] result = m.multiply(new double[]{1.0, 1.0, 1.0});
        assertArrayEquals(new double[]{6.0}, result, 1e-9);
    }

    @Test
    void outOfBoundsSetThrows() {
        SparseMatrix m = new SparseMatrix(2, 2);
        assertThrows(IndexOutOfBoundsException.class, () -> m.set(5, 0, 1.0));
        assertThrows(IndexOutOfBoundsException.class, () -> m.set(0, -1, 1.0));
    }

    @Test
    void multiplyWithWrongVectorLengthThrows() {
        SparseMatrix m = new SparseMatrix(2, 3);
        assertThrows(IllegalArgumentException.class, () -> m.multiply(new double[]{1, 2}));
    }

    @Test
    void nnzCountsOnlyExplicitlySetEntries() {
        SparseMatrix m = new SparseMatrix(100, 100);
        m.set(0, 1, 1.0);
        m.set(50, 99, 1.0);
        m.set(3, 3, 1.0);
        assertEquals(3, m.nnz(), "nnz phai it hon nhieu so voi rows*cols=10000, chung minh tinh 'thua'");
    }
}
