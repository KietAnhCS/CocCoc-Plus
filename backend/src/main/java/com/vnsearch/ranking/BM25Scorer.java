package com.vnsearch.ranking;

import com.vnsearch.index.SearchIndex;

import java.util.Map;

/**
 * BM25 (Okapi BM25) — mo hinh xep hang chuan cong nghiep (Lucene,
 * Elasticsearch, Solr deu dung), o day lam <b>baseline</b> doi chieu voi
 * {@link TfIdfScorer} cosine.
 *
 * <p>Cong thuc:
 * <pre>
 *   score(D,Q) = SUM  IDF(q) * ( f(q,D) * (k1 + 1) )
 *                 q            ─────────────────────────────────────
 *                              f(q,D) + k1 * (1 − b + b * |D| / avgdl)
 *
 *   IDF(q) = ln( 1 + (N − df + 0.5) / (df + 0.5) )
 * </pre>
 *
 * <p><b>BM25 hon TF-IDF cosine o ba diem:</b>
 *
 * <p>1. <i>Tan suat bao hoa co TRAN.</i> O TF-IDF, {@code tf = 1 + log10(f)}
 * van tang vo han theo so lan lap. O BM25, phan thuc tien toi tiem can ngang
 * {@code k1 + 1 = 2,2}: tu khoa xuat hien 50 lan gan nhu khong hon gi 20 lan.
 * Diem nua bao hoa dat tai {@code f = K}, tuc voi tai lieu do dai trung binh
 * thi chi can {@code f = k1 = 1,2} lan da dat mot nua muc toi da.
 *
 * <p>2. <i>Chuan hoa do dai co THAM SO.</i> TF-IDF o day chia cung cho
 * {@code sqrt(docLength)}. BM25 dung {@code b} de chinh muc phat: {@code b = 0}
 * khong phat gi, {@code b = 1} chuan hoa hoan toan theo {@code |D|/avgdl}.
 * Mac dinh {@code b = 0.75} la dung hoa da duoc kiem chung qua nhieu thap ky
 * thuc nghiem TREC.
 *
 * <p>3. <i>IDF khong bao gio am.</i> Dang
 * {@code ln(1 + (N − df + 0.5)/(df + 0.5))} xuat phat tu mo hinh xac suat
 * Robertson–Sparck Jones. Cac so hang {@code +0.5} la lam tron tranh chia 0 va
 * tranh {@code ln 0}; boc trong {@code ln(1 + ...)} dam bao ket qua luon duong
 * — khac voi bien the {@code log(N/df)} von hoa AM khi term xuat hien o hon
 * mot nua so tai lieu, khien tai lieu chua no bi TRU diem mot cach vo ly.
 *
 * <p>Do phuc tap thoi gian: {@code O(q log d)} — giong TF-IDF, vi cung dung
 * binary search tren posting list da sap xep theo docId.
 */
public class BM25Scorer implements RelevanceScorer {

    /** Dieu khien muc bao hoa tan suat. Chuan thuc nghiem: 1.2 - 2.0. */
    public static final double DEFAULT_K1 = 1.2;

    /** Muc chuan hoa theo do dai tai lieu: 0 = khong phat, 1 = chuan hoa hoan toan. */
    public static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;

    public BM25Scorer() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    public BM25Scorer(double k1, double b) {
        if (k1 < 0) {
            throw new IllegalArgumentException("k1 phai >= 0, nhan duoc: " + k1);
        }
        if (b < 0 || b > 1) {
            throw new IllegalArgumentException("b phai trong [0, 1], nhan duoc: " + b);
        }
        this.k1 = k1;
        this.b = b;
    }

    /** IDF theo mo hinh xac suat Robertson–Sparck Jones, luon khong am. */
    public static double idf(int totalDocs, int documentFrequency) {
        if (documentFrequency <= 0 || totalDocs <= 0) {
            return 0.0;
        }
        return Math.log(1 + ((double) totalDocs - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

    @Override
    public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
        // Mot cong thuc, mot cho — xem ghi chu cung ten o TfIdfScorer.
        return prepare(queryTermFrequency, index).score(docId);
    }

    /**
     * Tinh truoc {@code idf} cua tung term truy van, dung lai cho moi tai lieu.
     *
     * <p>{@code idf} chi phu thuoc {@code df} va {@code N} — ca hai co dinh
     * trong mot truy van — nhung truoc day no duoc tinh lai cho MOI ung vien,
     * tuc mot {@code Math.log} du thua cho moi cap (term, ung vien).
     *
     * <p>Cac term co {@code df = 0} bi loai ngay o buoc chuan bi, nen vong lap
     * nong khong con phai kiem tra chung.
     */
    @Override
    public DocumentScorer prepare(Map<String, Integer> queryTermFrequency, SearchIndex index) {
        int totalDocs = index.getTotalDocs();
        double avgDocLength = index.getAverageDocLength();
        if (totalDocs == 0 || avgDocLength <= 0) {
            // avgDocLength = 0 xay ra khi trang thai dan xuat totalTokens chua
            // duoc tinh lai sau khi nap chi muc tu file — xem
            // InvertedIndex.recomputeDerivedState.
            return docId -> 0.0;
        }

        int size = queryTermFrequency.size();
        String[] terms = new String[size];
        double[] idfValues = new double[size];
        int kept = 0;
        for (String term : queryTermFrequency.keySet()) {
            int df = index.getDocumentFrequency(term);
            if (df == 0) {
                continue;
            }
            terms[kept] = term;
            idfValues[kept] = idf(totalDocs, df);
            kept++;
        }

        final int count = kept;
        if (count == 0) {
            return docId -> 0.0;
        }

        return docId -> {
            // He so chuan hoa do dai khong phu thuoc term nen van tinh MOT lan
            // cho ca tai lieu, thay vi q lan trong vong lap.
            double lengthNorm = k1 * (1 - b + b * (index.getDocLength(docId) / avgDocLength));
            double total = 0.0;
            for (int i = 0; i < count; i++) {
                int termFrequency = index.getTermFrequency(terms[i], docId);
                if (termFrequency == 0) {
                    continue;
                }
                total += idfValues[i] * (termFrequency * (k1 + 1)) / (termFrequency + lengthNorm);
            }
            return total;
        };
    }

    @Override
    public String name() {
        return String.format("BM25(k1=%.1f,b=%.2f)", k1, b);
    }
}
