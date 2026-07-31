package com.vnsearch.datastructure;

import java.nio.charset.StandardCharsets;

/**
 * Bloom Filter tự cài đặt, dùng để kiểm tra nhanh một URL đã được crawl hay
 * chưa mà không cần lưu toàn bộ URL như {@code HashSet<String>} (tiết kiệm bộ
 * nhớ đáng kể với hàng triệu URL, đánh đổi bằng một tỷ lệ lỗi false positive
 * nhỏ, chấp nhận được trong bài toán khử trùng lặp URL).
 *
 * <p>Bit array tự quản lý bằng {@code long[]} và phép dịch bit (không dùng
 * {@link java.util.BitSet} có sẵn) để thể hiện rõ cơ chế lưu trữ bit.
 *
 * <p>Sinh k hàm băm từ 2 hàm băm cơ sở bằng kỹ thuật double hashing (Kirsch
 * &amp; Mitzenmacher): {@code h_i(x) = h1(x) + i * h2(x) (mod m)}, i = 0..k-1.
 * Kỹ thuật này chỉ cần tính 2 hàm băm thật sự, các hàm còn lại là tổ hợp
 * tuyến tính của chúng, vẫn đảm bảo phân bố đủ tốt cho mục đích Bloom Filter
 * (đã được chứng minh trong bài báo gốc năm 2008).
 *
 * <p>Constructor tự tính kích thước tối ưu theo công thức chuẩn:
 * <pre>
 *   m = ceil(-n * ln(p) / (ln 2)^2)   (số bit)
 *   k = round((m / n) * ln 2)          (số hàm băm)
 * </pre>
 *
 * <p><b>Quan trọng — chiều sai của cấu trúc này.</b> Bloom Filter có thể có
 * FALSE POSITIVE (báo "có thể có" nhưng thực tế chưa từng add) vì nhiều chuỗi
 * khác nhau có thể cùng bật trúng một tập hợp bit. Nhưng nó KHÔNG BAO GIỜ có
 * FALSE NEGATIVE (đã add thì {@code mightContain} luôn trả về true), vì
 * {@link #add} chỉ BẬT bit (phép OR), không bao giờ TẮT bit — nên một bit đã
 * bật bởi phần tử X sẽ vẫn còn bật khi kiểm tra lại X, dù có phần tử Y khác
 * làm bật thêm bit khác.
 *
 * <p>Đây chính là lý do cấu trúc này dùng được cho bài toán khử trùng lặp URL:
 * false positive khiến bỏ lỡ vài trang — tiếc nhưng vô hại; false negative sẽ
 * khiến crawl lại trang cũ và gây <b>vòng lặp vô hạn</b> — và điều đó không
 * bao giờ xảy ra.
 *
 * <p>Độ phức tạp thời gian: {@link #add(String)} và
 * {@link #mightContain(String)} đều O(k) (k là hằng số nhỏ, thường &lt; 20).
 * Độ phức tạp không gian: O(m) bit = O(m/64) long, KHÔNG phụ thuộc độ dài
 * chuỗi đã lưu (khác với HashSet phải lưu toàn bộ chuỗi).
 */
public class BloomFilter {

    private final long[] bits;
    private final int numBits;
    private final int numHashes;

    public BloomFilter(int expectedItems, double falsePositiveRate) {
        if (expectedItems <= 0) {
            throw new IllegalArgumentException("expectedItems phải > 0");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate phải trong khoảng (0, 1)");
        }
        double ln2 = Math.log(2);
        int m = (int) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (ln2 * ln2));
        m = Math.max(m, 64);
        int k = (int) Math.round((double) m / expectedItems * ln2);
        this.numBits = m;
        this.numHashes = Math.max(k, 1);
        this.bits = new long[(m + 63) / 64];
    }

    /** Chỉ dùng trong test để đặt số bit / số hàm băm cố định, không qua công thức. */
    BloomFilter(int numBits, int numHashes, boolean rawConfig) {
        this.numBits = numBits;
        this.numHashes = numHashes;
        this.bits = new long[(numBits + 63) / 64];
    }

    public int getNumBits() {
        return numBits;
    }

    public int getNumHashes() {
        return numHashes;
    }

    /** O(k) — đánh dấu một chuỗi đã thêm vào filter. */
    public void add(String item) {
        long h1 = hash1(item);
        long h2 = hash2(item);
        for (int i = 0; i < numHashes; i++) {
            int idx = indexFor(h1, h2, i);
            setBit(idx);
        }
    }

    /**
     * O(k) — kiểm tra chuỗi CÓ THỂ đã được thêm chưa. Trả về false nghĩa là
     * CHẮC CHẮN chưa thêm (không có false negative). Trả về true nghĩa là
     * "có thể đã thêm" (có thể là false positive).
     */
    public boolean mightContain(String item) {
        long h1 = hash1(item);
        long h2 = hash2(item);
        for (int i = 0; i < numHashes; i++) {
            int idx = indexFor(h1, h2, i);
            if (!getBit(idx)) {
                return false;
            }
        }
        return true;
    }

    private int indexFor(long h1, long h2, int i) {
        long combined = h1 + (long) i * h2;
        return (int) Math.floorMod(combined, (long) numBits);
    }

    private void setBit(int index) {
        bits[index / 64] |= (1L << (index % 64));
    }

    private boolean getBit(int index) {
        return (bits[index / 64] & (1L << (index % 64))) != 0;
    }

    /**
     * Hàm băm cơ sở thứ 1: FNV-1a 64-bit, một hàm băm phổ biến vì nhanh và
     * phân bố tốt cho chuỗi.
     */
    private static long hash1(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (byte b : data) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }

    /**
     * Hàm băm cơ sở thứ 2: polynomial rolling hash với một số nguyên tố khác
     * để đảm bảo độc lập tương đối với hash1.
     */
    private static long hash2(String s) {
        long hash = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            hash = 31 * hash + s.charAt(i);
        }
        // trộn avalanche để tránh các bit thấp quá tương quan với hash1
        hash ^= (hash >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= (hash >>> 33);
        return hash;
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        System.out.println("numBits=" + filter.getNumBits() + " numHashes=" + filter.getNumHashes());

        filter.add("https://vnexpress.net/");
        filter.add("https://tuoitre.vn/");

        System.out.println("mightContain(vnexpress) = " + filter.mightContain("https://vnexpress.net/"));
        System.out.println("mightContain(tuoitre)   = " + filter.mightContain("https://tuoitre.vn/"));
        System.out.println("mightContain(chưa thêm) = " + filter.mightContain("https://khong-ton-tai.vn/"));
    }
}
