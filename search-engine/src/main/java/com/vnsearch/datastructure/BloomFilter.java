package com.vnsearch.datastructure;

import java.nio.charset.StandardCharsets;

/**
 * Bloom Filter tu cai dat, dung de kiem tra nhanh mot URL da duoc crawl hay
 * chua ma khong can luu toan bo URL nhu {@code HashSet<String>} (tiet kiem
 * bo nho dang ke voi hang trieu URL, danh doi bang mot ty le loi false
 * positive nho, chap nhan duoc trong bai toan dedupe URL).
 *
 * <p>Bit array tu quan ly bang {@code long[]} va phep dich bit (khong dung
 * {@link java.util.BitSet} co san) de the hien ro co che luu tru bit.
 *
 * <p>Sinh k ham bam tu 2 ham bam co so bang ky thuat double hashing (Kirsch
 * &amp; Mitzenmacher): {@code h_i(x) = h1(x) + i * h2(x) (mod m)}, i = 0..k-1.
 * Ky thuat nay chi can tinh 2 ham bam that su, cac ham con lai la to hop
 * tuyen tinh cua chung, van dam bao phan bo du tot cho muc dich Bloom
 * Filter (da duoc chung minh trong bai bao goc nam 2008).
 *
 * <p>Constructor tu tinh kich thuoc toi uu theo cong thuc chuan:
 * <pre>
 *   m = ceil(-n * ln(p) / (ln 2)^2)   (so bit)
 *   k = round((m / n) * ln 2)          (so ham bam)
 * </pre>
 *
 * <p><b>Quan trong:</b> Bloom Filter co the co FALSE POSITIVE (bao "co the
 * co" nhung thuc te chua tung add) vi nhieu chuoi khac nhau co the cung
 * bat trung mot tap hop bit. Nhung KHONG BAO GIO co FALSE NEGATIVE (da add
 * thi mightContain luon tra ve true), vi add() chi BAT bit (OR), khong bao
 * gio TAT bit — nen mot bit da bat boi phan tu X se van con bat khi kiem
 * tra lai X du co phan tu Y khac lam bat them bit khac.
 *
 * <p>Do phuc tap thoi gian: {@link #add(String)} va
 * {@link #mightContain(String)} deu O(k) (k la hang so nho, thuong &lt; 20).
 * Do phuc tap khong gian: O(m) bit = O(m/64) long, KHONG phu thuoc do dai
 * chuoi da luu (khac voi HashSet phai luu toan bo chuoi).
 */
public class BloomFilter {

    private final long[] bits;
    private final int numBits;
    private final int numHashes;

    public BloomFilter(int expectedItems, double falsePositiveRate) {
        if (expectedItems <= 0) {
            throw new IllegalArgumentException("expectedItems phai > 0");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate phai trong (0, 1)");
        }
        double ln2 = Math.log(2);
        int m = (int) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (ln2 * ln2));
        m = Math.max(m, 64);
        int k = (int) Math.round((double) m / expectedItems * ln2);
        this.numBits = m;
        this.numHashes = Math.max(k, 1);
        this.bits = new long[(m + 63) / 64];
    }

    /** Chi dung trong test de dung so bit/so ham bam co dinh, khong qua cong thuc. */
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

    /** O(k) - danh dau mot chuoi da them vao filter. */
    public void add(String item) {
        long h1 = hash1(item);
        long h2 = hash2(item);
        for (int i = 0; i < numHashes; i++) {
            int idx = indexFor(h1, h2, i);
            setBit(idx);
        }
    }

    /**
     * O(k) - kiem tra chuoi CO THE da duoc them chua. Tra ve false nghia la
     * CHAC CHAN chua them (khong co false negative). Tra ve true nghia la
     * "co the da them" (co the la false positive).
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
     * Ham bam co so thu 1: FNV-1a 64-bit, mot ham bam pho bien vi nhanh va
     * phan bo tot cho chuoi.
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
     * Ham bam co so thu 2: polynomial rolling hash voi mot so nguyen to
     * khac de dam bao doc lap tuong doi voi hash1.
     */
    private static long hash2(String s) {
        long hash = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            hash = 31 * hash + s.charAt(i);
        }
        // avalanche mix de tranh cac bit thap qua tuong quan voi hash1
        hash ^= (hash >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= (hash >>> 33);
        return hash;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        System.out.println("numBits=" + filter.getNumBits() + " numHashes=" + filter.getNumHashes());

        filter.add("https://vnexpress.net/");
        filter.add("https://tuoitre.vn/");

        System.out.println("mightContain(vnexpress) = " + filter.mightContain("https://vnexpress.net/"));
        System.out.println("mightContain(tuoitre)   = " + filter.mightContain("https://tuoitre.vn/"));
        System.out.println("mightContain(chua-them) = " + filter.mightContain("https://khong-ton-tai.vn/"));
    }
}
