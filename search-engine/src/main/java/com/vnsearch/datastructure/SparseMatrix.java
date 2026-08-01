package com.vnsearch.datastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Ma trận thưa (sparse matrix) tự cài đặt, dùng để lưu ma trận liên kết giữa
 * các trang web cho PageRank.
 *
 * <p>Vì sao dùng thưa thay vì {@code double[n][n]}: với n = 5.011 trang, ma
 * trận đặc cần {@code 5011^2 * 8 = 191,5 MB}, trong khi thực tế chỉ có
 * {@code nnz = 239.691} phần tử khác 0 (độ thưa 0,95 %) nên biểu diễn thưa
 * chỉ tốn vài MB. Tỷ lệ này còn XẤU ĐI khi corpus lớn hơn, vì
 * {@code độ thưa = nnz/n^2 = k_tb/n} tỷ lệ nghịch với n.
 *
 * <p><b>Hai chế độ lưu trữ — dựng linh hoạt, đông cứng để chạy nhanh:</b>
 *
 * <p>1. <i>Chế độ XÂY DỰNG (adjacency list).</i> Mỗi hàng là một
 * {@code List<Entry>} gồm các cặp (cột, giá trị) khác 0. Cho phép {@link #set}
 * O(1) khấu hao — bắt buộc phải có vì ma trận được xây DẦN trong lúc duyệt
 * outlink, khi chưa biết trước số phần tử.
 *
 * <p>2. <i>Chế độ CHẠY (CSR — Compressed Sparse Row).</i> Sau khi xây xong,
 * gọi {@link #freeze()} để "đông cứng" sang ba mảng nguyên thuỷ liên tục:
 * <pre>
 *   values[]  double[nnz]    giá trị khác 0, xếp theo hàng
 *   colIdx[]  int[nnz]       chỉ số cột tương ứng
 *   rowPtr[]  int[rows+1]    rowPtr[i] = chỉ số bắt đầu của hàng i
 * </pre>
 * Lợi ích đổi được:
 * <ul>
 *   <li><b>Bộ nhớ:</b> 12 byte/phần tử thay vì khoảng 32 byte của {@code Entry}
 *       object (16 B header + 4 B int + 8 B double + căn lề) — <b>tiết kiệm
 *       khoảng 2,7 lần</b>.</li>
 *   <li><b>Cục bộ cache:</b> ba mảng liên tục tuyệt đối, CPU nạp được 16 giá
 *       trị {@code double} mỗi cache line thay vì nhảy tới các object rải rác.</li>
 *   <li><b>Áp lực GC:</b> 3 object thay vì 239.691 object.</li>
 * </ul>
 * PageRank chạy 53 vòng lặp trên CÙNG một ma trận, nên trả chi phí đông cứng
 * MỘT lần để đổi lấy 53 lần nhân nhanh hơn là đánh đổi rất có lợi.
 *
 * <p>{@link #multiply(double[])} tự chọn chế độ: dùng CSR nếu đã đông cứng,
 * ngược lại dùng adjacency list. Người gọi không phải biết.
 *
 * <p>Độ phức tạp thời gian: {@link #set} O(1) khấu hao; {@link #freeze} O(nnz);
 * {@link #multiply(double[])} O(nnz) ở CẢ HAI chế độ (nhưng hằng số của CSR
 * nhỏ hơn đáng kể). Độ phức tạp không gian: O(nnz).
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

    /** Chế độ xây dựng — null sau khi {@link #freeze()}. */
    private List<List<Entry>> rowEntries;

    /** Chế độ chạy (CSR) — null trước khi {@link #freeze()}. */
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

    /** Đã đông cứng sang CSR chưa. */
    public boolean isFrozen() {
        return rowEntries == null;
    }

    /**
     * O(1) khấu hao — thêm một phần tử khác 0 vào vị trí (row, col).
     *
     * <p><b>Lưu ý về ngữ nghĩa:</b> đây là phép THÊM, không phải phép GÁN ĐÈ.
     * Gọi hai lần cùng một (row, col) sẽ tạo HAI mục, và {@link #multiply}
     * cộng cả hai. Người gọi ({@code PageRankService}) đảm bảo không trùng nhờ
     * {@code LinkExtractor} đã khử trùng outlink bằng {@code LinkedHashSet}.
     *
     * @throws IllegalStateException nếu ma trận đã bị đông cứng
     */
    public void set(int row, int col, double value) {
        if (isFrozen()) {
            throw new IllegalStateException("Ma trận đã đông cứng (frozen), không thể sửa đổi");
        }
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new IndexOutOfBoundsException("(" + row + "," + col + ") ngoài kích thước " + rows + "x" + cols);
        }
        rowEntries.get(row).add(new Entry(col, value));
    }

    /**
     * O(nnz) — "đông cứng" sang định dạng CSR để {@link #multiply} chạy nhanh
     * hơn và tốn ít bộ nhớ hơn. Sau khi gọi, {@link #set} sẽ ném ngoại lệ.
     *
     * <p>Gọi lại lần thứ hai là vô hại (không làm gì).
     *
     * @return chính đối tượng này, để gọi nối chuỗi
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
        csrRowPtr[rows] = position; // canh biên: giúp vòng lặp không cần if riêng cho hàng cuối

        rowEntries = null; // nhường toàn bộ Entry object cho bộ gom rác
        return this;
    }

    /**
     * O(nnz) — nhân ma trận với vector, dùng cho vòng lặp power iteration của
     * PageRank: {@code result[row] = sum(M[row][col] * vector[col])}.
     *
     * <p>Chỉ duyệt các ô KHÁC 0, nên là O(nnz) chứ không phải O(n^2) — với dữ
     * liệu thật đây là khác biệt 105 lần.
     */
    public double[] multiply(double[] vector) {
        if (vector.length != cols) {
            throw new IllegalArgumentException("Độ dài vector (" + vector.length + ") phải bằng số cột (" + cols + ")");
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
     * Nhân trên CSR: hai mảng nguyên thuỷ liên tục, không dereference object
     * nào trong vòng lặp nóng.
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

    /** Tổng số phần tử khác 0 (nnz) — dùng để báo cáo độ thưa trong DSA-REPORT. */
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

    /** Độ thưa {@code nnz/(rows*cols)} — càng nhỏ thì biểu diễn thưa càng có lợi. */
    public double density() {
        long cells = (long) rows * cols;
        return cells == 0 ? 0.0 : (double) nnz() / cells;
    }

    /** Ước lượng bộ nhớ theo chế độ hiện tại, dùng để báo cáo. */
    public long estimatedBytes() {
        int n = nnz();
        if (isFrozen()) {
            // double[nnz] + int[nnz] + int[rows+1]
            return (long) n * (Double.BYTES + Integer.BYTES) + (long) (rows + 1) * Integer.BYTES;
        }
        // Entry object: khoảng 16 B header + 4 B int + 8 B double, căn lề lên
        // 32 B, cộng tham chiếu trong ArrayList và overhead của từng ArrayList.
        return (long) n * 32 + (long) rows * 40;
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        // Ma trận 3x3: row 0 -> col 1 (0.5), row 1 -> col 2 (1.0), row 2 -> col 0 (0.5), col 1 (0.5)
        SparseMatrix m = new SparseMatrix(3, 3);
        m.set(0, 1, 0.5);
        m.set(1, 2, 1.0);
        m.set(2, 0, 0.5);
        m.set(2, 1, 0.5);

        double[] v = {1.0, 1.0, 1.0};
        System.out.println("Adjacency list -> " + java.util.Arrays.toString(m.multiply(v)));
        long beforeBytes = m.estimatedBytes();

        m.freeze();
        System.out.println("CSR (đã freeze) -> " + java.util.Arrays.toString(m.multiply(v)));
        System.out.printf("Bộ nhớ: %d B -> %d B (tiết kiệm %.1f%%)%n",
                beforeBytes, m.estimatedBytes(),
                100.0 * (beforeBytes - m.estimatedBytes()) / beforeBytes);
        System.out.printf("nnz = %d / %d ô, độ thưa = %.2f%%%n",
                m.nnz(), m.getRows() * m.getCols(), m.density() * 100);
    }
}
