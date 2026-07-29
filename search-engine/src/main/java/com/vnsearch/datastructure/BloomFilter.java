package com.vnsearch.datastructure;

/**
 * TODO (PHASE 2): Bloom Filter tu cai dat, dung de kiem tra nhanh mot URL
 * da duoc crawl hay chua ma khong can luu toan bo URL nhu HashSet (tiet
 * kiem bo nho dang ke voi hang trieu URL).
 *
 * Cau truc du lieu du kien:
 *   - long[] bitArray tu quan ly bit bang phep dich bit (khong dung
 *     BitSet co san de chung minh hieu ban chat).
 *   - k ham bam sinh tu 2 ham bam co so bang double hashing:
 *     h_i(x) = h1(x) + i * h2(x)  (i = 0..k-1)
 *   - Constructor (expectedItems, falsePositiveRate) tu tinh:
 *       m = -n * ln(p) / (ln2)^2   (kich thuoc bit array)
 *       k = (m / n) * ln2          (so ham bam)
 *
 * Method can cai dat:
 *   - void add(String item)                O(k)
 *   - boolean mightContain(String item)     O(k)
 *
 * Luu y quan trong: co the co false positive (bao "co the co" nhung thuc
 * te khong co) nhung KHONG BAO GIO co false negative (da add thi
 * mightContain luon tra ve true) - vi moi bit da bat 1 lan se khong bao
 * gio bi tat lai.
 *
 * Se so sanh bo nho Bloom Filter vs HashSet<String> voi 1 trieu URL trong
 * docs/DSA-REPORT.md.
 */
public class BloomFilter {
    // TODO: implement in PHASE 2
}
