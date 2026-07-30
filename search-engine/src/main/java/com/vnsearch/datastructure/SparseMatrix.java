package com.vnsearch.datastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Ma tran thua (sparse matrix) tu cai dat, dung de luu ma tran lien ket
 * giua cac trang web cho PageRank.
 *
 * <p>Vi sao dung sparse thay vi {@code double[n][n]}: voi n = 5.011 trang,
 * ma tran dac can {@code 5011^2 * 8 = 191,5 MB}, trong khi thuc te chi co
 * {@code nnz = 239.691} phan tu khac 0 (do thua 0,95%) nen bieu dien thua
 * chi ton vai MB. Ty le nay con XAU DI khi corpus lon hon, vi
 * {@code do thua = nnz/n^2 = k_tb/n} ty le nghich voi n.
 *
 * <p><b>Hai che do luu tru — dung linh hoat, dong bang de chay nhanh:</b>
 *
 * <p>1. <i>Che do XAY DUNG (adjacency list).</i> Moi hang la mot
 * {@code List<Entry>} gom cac cap (cot, gia tri) khac 0. Cho phep {@link #set}
 * O(1) khau hao — bat buoc phai co vi ma tran duoc xay DAN trong luc duyet
 * outlink, khi chua biet truoc so phan tu.
 *
 * <p>2. <i>Che do CHAY (CSR — Compressed Sparse Row).</i> Sau khi xay xong,
 * goi {@link #freeze()} de "dong bang" sang ba mang nguyen thuy lien tuc:
 * <pre>
 *   values[]  double[nnz]    gia tri khac 0, xep theo hang
 *   colIdx[]  int[nnz]       chi so cot tuong ung
 *   rowPtr[]  int[rows+1]    rowPtr[i] = chi so bat dau cua hang i
 * </pre>
 * Loi ich do duoc:
 * <ul>
 *   <li><b>Bo nho:</b> 12 byte/phan tu thay vi ~32 byte cua {@code Entry}
 *       object (16 B header + 4 B int + 8 B double + can le) — <b>tiet kiem
 *       ~2,7 lan</b>.</li>
 *   <li><b>Cuc bo cache:</b> ba mang lien tuc tuyet doi, CPU nap duoc 16 gia
 *       tri {@code double} moi cache line thay vi nhay toi cac object rai rac.</li>
 *   <li><b>Ap luc GC:</b> 3 object thay vi 239.691 object.</li>
 * </ul>
 * PageRank chay 53 vong lap tren CUNG mot ma tran, nen tra chi phi dong bang
 * MOT lan de doi lay 53 lan nhan nhanh hon la danh doi rat co loi.
 *
 * <p>{@link #multiply(double[])} tu chon che do: dung CSR neu da dong bang,
 * nguoc lai dung adjacency list. Nguoi goi khong phai biet.
 *
 * <p>Do phuc tap thoi gian: {@link #set} O(1) khau hao; {@link #freeze} O(nnz);
 * {@link #multiply(double[])} O(nnz) o CA HAI che do (nhung hang so cua CSR
 * nho hon dang ke). Do phuc tap khong gian: O(nnz).
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

    /** Che do xay dung — null sau khi {@link #freeze()}. */
    private List<List<Entry>> rowEntries;

    /** Che do chay (CSR) — null truoc khi {@link #freeze()}. */
    private double[] csrValues;
    private int[] csrColIdx;
    private int[] csrRowPtr;

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

    /** Da dong bang sang CSR chua. */
    public boolean isFrozen() {
        return rowEntries == null;
    }

    /**
     * O(1) khau hao - them mot phan tu khac 0 vao vi tri (row, col).
     *
     * <p><b>Luu y ve ngu nghia:</b> day la phep THEM, khong phai phep GAN de.
     * Goi hai lan cung mot (row, col) se tao HAI muc, va {@link #multiply}
     * cong ca hai. Nguoi goi ({@code PageRankService}) dam bao khong trung nho
     * {@code HtmlExtractor} da khu trung outlink bang {@code LinkedHashSet}.
     *
     * @throws IllegalStateException neu ma tran da bi dong bang
     */
    public void set(int row, int col, double value) {
        if (isFrozen()) {
            throw new IllegalStateException("Ma tran da dong bang (frozen), khong the sua doi");
        }
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new IndexOutOfBoundsException("(" + row + "," + col + ") ngoai kich thuoc " + rows + "x" + cols);
        }
        rowEntries.get(row).add(new Entry(col, value));
    }

    /**
     * O(nnz) - "dong bang" sang dinh dang CSR de {@link #multiply} chay nhanh
     * hon va ton it bo nho hon. Sau khi goi, {@link #set} se nem ngoai le.
     *
     * <p>Goi lai lan thu hai la vo hai (khong lam gi).
     *
     * @return chinh doi tuong nay, de goi noi chuoi
     */
    public SparseMatrix freeze() {
        if (isFrozen()) {
            return this;
        }
        int nnz = 0;
        for (List<Entry> row : rowEntries) {
            nnz += row.size();
        }

        csrValues = new double[nnz];
        csrColIdx = new int[nnz];
        csrRowPtr = new int[rows + 1];

        int position = 0;
        for (int row = 0; row < rows; row++) {
            csrRowPtr[row] = position;
            for (Entry e : rowEntries.get(row)) {
                csrValues[position] = e.value;
                csrColIdx[position] = e.col;
                position++;
            }
        }
        csrRowPtr[rows] = position; // canh bien: giup vong lap khong can if rieng cho hang cuoi

        rowEntries = null; // nhuong toan bo Entry object cho bo gom rac
        return this;
    }

    /**
     * O(nnz) - nhan ma tran voi vector, dung cho vong lap power iteration
     * cua PageRank: {@code result[row] = sum(M[row][col] * vector[col])}.
     *
     * <p>Chi duyet cac o KHAC 0, nen la O(nnz) chu khong phai O(n^2) — voi
     * du lieu that day la khac biet 105 lan.
     */
    public double[] multiply(double[] vector) {
        if (vector.length != cols) {
            throw new IllegalArgumentException("Do dai vector (" + vector.length + ") phai bang so cot (" + cols + ")");
        }
        double[] result = new double[rows];
        if (isFrozen()) {
            multiplyCsr(vector, result);
        } else {
            multiplyAdjacencyList(vector, result);
        }
        return result;
    }

    /**
     * Nhan tren CSR: hai mang nguyen thuy lien tuc, khong dereference object nao
     * trong vong lap nong.
     */
    private void multiplyCsr(double[] vector, double[] result) {
        for (int row = 0; row < rows; row++) {
            double sum = 0.0;
            int end = csrRowPtr[row + 1];
            for (int p = csrRowPtr[row]; p < end; p++) {
                sum += csrValues[p] * vector[csrColIdx[p]];
            }
            result[row] = sum;
        }
    }

    private void multiplyAdjacencyList(double[] vector, double[] result) {
        for (int row = 0; row < rows; row++) {
            double sum = 0.0;
            for (Entry e : rowEntries.get(row)) {
                sum += e.value * vector[e.col];
            }
            result[row] = sum;
        }
    }

    /** Tong so phan tu khac 0 (nnz) - dung de bao cao do thua trong DSA-REPORT. */
    public int nnz() {
        if (isFrozen()) {
            return csrValues.length;
        }
        int count = 0;
        for (List<Entry> row : rowEntries) {
            count += row.size();
        }
        return count;
    }

    /** Do thua nnz/(rows*cols) — cang nho thi bieu dien thua cang co loi. */
    public double density() {
        long cells = (long) rows * cols;
        return cells == 0 ? 0.0 : (double) nnz() / cells;
    }

    /** Uoc luong bo nho theo che do hien tai, dung de bao cao. */
    public long estimatedBytes() {
        int n = nnz();
        if (isFrozen()) {
            // double[nnz] + int[nnz] + int[rows+1]
            return (long) n * (Double.BYTES + Integer.BYTES) + (long) (rows + 1) * Integer.BYTES;
        }
        // Entry object: ~16 B header + 4 B int + 8 B double, can le len 32 B,
        // cong tham chieu trong ArrayList va overhead cua tung ArrayList.
        return (long) n * 32 + (long) rows * 40;
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
        System.out.println("Adjacency list -> " + java.util.Arrays.toString(m.multiply(v)));
        long beforeBytes = m.estimatedBytes();

        m.freeze();
        System.out.println("CSR (da freeze) -> " + java.util.Arrays.toString(m.multiply(v)));
        System.out.printf("Bo nho: %d B -> %d B (tiet kiem %.1f%%)%n",
                beforeBytes, m.estimatedBytes(),
                100.0 * (beforeBytes - m.estimatedBytes()) / beforeBytes);
        System.out.printf("nnz = %d / %d o, do thua = %.2f%%%n",
                m.nnz(), m.getRows() * m.getCols(), m.density() * 100);
    }
}
