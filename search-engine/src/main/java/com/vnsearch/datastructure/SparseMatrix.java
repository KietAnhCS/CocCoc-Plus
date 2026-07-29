package com.vnsearch.datastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Ma tran thua (sparse matrix) tu cai dat, dung de luu ma tran lien ket
 * giua cac trang web cho PageRank.
 *
 * <p>Vi sao dung sparse thay vi {@code double[n][n]}: voi n = 10.000
 * trang, ma tran dac can 10.000 * 10.000 * 8 byte = 800.000.000 byte
 * (~800MB), trong khi thuc te moi trang chi lien ket toi vai chuc trang
 * khac (nnz &lt;&lt; n^2) nen bieu dien thua chi ton vai MB — ty le nnz/n^2
 * (do "thua") cua do thi web thuc te thuong duoi 0.01%.
 *
 * <p>Dinh dang: adjacency list theo hang — moi hang la mot
 * {@code List<Entry>} gom cac cap (cot, gia tri) khac 0. Chon adjacency
 * list thay vi CSR "cung" (3 mang int[]/double[] lien tuc) vi ma tran nay
 * duoc XAY DAN trong luc crawl (moi lan phat hien 1 outlink moi la 1 lan
 * goi {@link #set}) — adjacency list cho phep them phan tu O(1) amortized,
 * trong khi CSR that su can biet truoc so luong outlink de cap phat mang
 * co dinh. Neu can, co the "nen" (freeze) sang CSR that su sau khi da xay
 * xong de tang locality khi multiply — nhung voi quy mo do an (hang chuc
 * nghin trang) adjacency list da du nhanh.
 *
 * <p>Do phuc tap thoi gian: {@link #set} O(1) amortized;
 * {@link #multiply(double[])} O(nnz) voi nnz = tong so phan tu khac 0
 * trong toan ma tran (khong phai O(n^2) nhu ma tran dac).
 * Do phuc tap khong gian: O(nnz).
 */
public class SparseMatrix {

    private static final class Entry {
        final int col;
        final double value;

        Entry(int col, double value) {
            this.col = col;
            this.value = value;
        }
    }

    private final int rows;
    private final int cols;
    private final List<List<Entry>> rowEntries;

    public SparseMatrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.rowEntries = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            rowEntries.add(new ArrayList<>());
        }
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    /** O(1) amortized - them mot phan tu khac 0 vao vi tri (row, col). */
    public void set(int row, int col, double value) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new IndexOutOfBoundsException("(" + row + "," + col + ") ngoai kich thuoc " + rows + "x" + cols);
        }
        rowEntries.get(row).add(new Entry(col, value));
    }

    /**
     * O(nnz) - nhan ma tran voi vector, dung cho vong lap power iteration
     * cua PageRank: {@code result[row] = sum(entries[row][col] * vector[col])}.
     */
    public double[] multiply(double[] vector) {
        if (vector.length != cols) {
            throw new IllegalArgumentException("Do dai vector (" + vector.length + ") phai bang so cot (" + cols + ")");
        }
        double[] result = new double[rows];
        for (int row = 0; row < rows; row++) {
            double sum = 0.0;
            for (Entry e : rowEntries.get(row)) {
                sum += e.value * vector[e.col];
            }
            result[row] = sum;
        }
        return result;
    }

    /** Tong so phan tu khac 0 (nnz) - dung de bao cao do thua trong DSA-REPORT. */
    public int nnz() {
        int count = 0;
        for (List<Entry> row : rowEntries) {
            count += row.size();
        }
        return count;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        // Ma tran 3x3: row 0 -> col 1 (0.5), row 1 -> col 2 (1.0), row 2 -> col 0 (0.5), col 1 (0.5)
        SparseMatrix m = new SparseMatrix(3, 3);
        m.set(0, 1, 0.5);
        m.set(1, 2, 1.0);
        m.set(2, 0, 0.5);
        m.set(2, 1, 0.5);

        double[] v = {1.0, 1.0, 1.0};
        double[] result = m.multiply(v);
        System.out.println("multiply([1,1,1]) = " + java.util.Arrays.toString(result));
        System.out.println("nnz = " + m.nnz() + " / kich thuoc dac tuong duong = " + (m.getRows() * m.getCols()));
    }
}
