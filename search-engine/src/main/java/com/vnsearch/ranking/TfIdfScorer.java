package com.vnsearch.ranking;

import com.vnsearch.index.SearchIndex;

import java.util.Map;

/**
 * Tinh diem lien quan giua mot truy van va mot tai lieu bang mo hinh vector
 * TF-IDF + cosine similarity.
 *
 * <p>{@code tf  = 1 + log10(termFrequency)} — nen phi tuyen de mot tai lieu
 * nhoi tu khoa khong thang qua de: lap gap 10 lan chi duoc them 1 diem.
 * <br>{@code idf = log10(totalDocs / documentFrequency)} — chinh la
 * <i>luong thong tin</i> (self-information) cua bien co "tai lieu chua term
 * nay": tu hiem mang nhieu thong tin phan biet hon.
 *
 * <p><b>Chuan hoa do dai tai lieu.</b> Cosine chuan can chia cho
 * {@code ||vector tai lieu||}, ma norm nay ve ly thuyet phai tinh tren TAT CA
 * term cua tai lieu — ton O(|tu vung|) cho MOI tai lieu, va phai tinh lai moi
 * lan them tai lieu (vi idf doi khi N doi). Thay vao do dung xap xi kinh dien
 * cua Lucene classic Similarity: {@code docNorm ~= sqrt(docLength)}.
 *
 * <p><b>Sai so cua xap xi, noi cho cong bang.</b> Theo dinh luat Heaps, so term
 * phan biet cua tai lieu tang theo {@code |d|^beta} voi {@code beta ~ 0,5}, nen
 * norm that ty le {@code |d|^0,25} trong khi xap xi dung {@code |d|^0,5} — tuc
 * no PHAT tai lieu dai manh hon thuc te. Day dung la diem ma BM25 hon, vi BM25
 * co tham so {@code b} de dieu chinh muc phat thay vi chon cung.
 *
 * <p>Do phuc tap thoi gian: {@link #score} la {@code O(q log d)} voi q = so term
 * phan biet trong truy van, d = do dai posting list dai nhat (do dung binary
 * search cua {@link SearchIndex#getTermFrequency}).
 */
public class TfIdfScorer implements RelevanceScorer {

    @Override
    public String name() {
        return "TF-IDF cosine";
    }

    /** Log-normalized term frequency. Tra 0 cho f=0 de tranh {@code log10(0) = -inf}. */
    public static double tf(int termFrequency) {
        return termFrequency > 0 ? 1 + Math.log10(termFrequency) : 0.0;
    }

    /** Inverse document frequency — luong thong tin cua term. */
    public static double idf(int totalDocs, int documentFrequency) {
        if (documentFrequency <= 0 || totalDocs <= 0) {
            return 0.0;
        }
        return Math.log10((double) totalDocs / documentFrequency);
    }

    /**
     * Tinh diem TF-IDF cosine cua {@code docId} doi voi truy van.
     *
     * <p>Chi duyet qua term cua TRUY VAN chu khong phai 136.768 chieu cua khong
     * gian vector: voi term khong thuoc truy van, {@code w(t,q) = 0} nen so hang
     * {@code w(t,q)*w(t,d) = 0}. Bo qua chung la CHINH XAC, khong phai xap xi —
     * day la toan bo ly do vector thua lam viec duoc.
     *
     * @param queryTermFrequency so lan moi term xuat hien trong truy van
     *                           (thuong la 1, nhung cho phep lap tu de nhan manh)
     */
    @Override
    public double score(Map<String, Integer> queryTermFrequency, int docId, SearchIndex index) {
        // Uy quyen cho prepare() thay vi lap lai cong thuc: mot cong thuc, mot
        // cho. Duong nong (ResultRanker) goi thang prepare() nen khong tra phi
        // chuan bi lai; duong nay chi phuc vu cac lan cham diem le.
        return prepare(queryTermFrequency, index).score(docId);
    }

    /**
     * Tinh truoc trong so TRUY VAN cho tung term, dung lai cho moi tai lieu.
     *
     * <p>{@code idf} va {@code tf} cua ve truy van khong phu thuoc tai lieu —
     * truoc day chung bi tinh lai cho MOI ung vien, tuc hai {@code Math.log10}
     * nhan voi so ung vien nhan voi so term. Chuan hoa {@code queryNorm} cung
     * vay.
     *
     * <p>Cac term bi loai ngay o day thay vi kiem tra lai trong tung lan cham
     * diem: term khong ton tai, hoac xuat hien trong TAT CA tai lieu (khi do
     * {@code idf = log10(1) = 0}, khong phan biet duoc gi).
     *
     * <p>Dung mang song song thay vi mot mang doi tuong: vong lap trong cua
     * viec cham diem chay {@code c*q} lan, va mang {@code double} phang cho cuc
     * bo cache tot hon han mot mang tham chieu tro toi cac doi tuong nam rai
     * rac trong heap.
     */
    @Override
    public DocumentScorer prepare(Map<String, Integer> queryTermFrequency, SearchIndex index) {
        int totalDocs = index.getTotalDocs();
        int size = queryTermFrequency.size();

        String[] terms = new String[size];
        double[] idfValues = new double[size];
        double[] queryWeights = new double[size];
        int kept = 0;
        double queryNormSq = 0.0;

        for (Map.Entry<String, Integer> entry : queryTermFrequency.entrySet()) {
            double idfValue = idf(totalDocs, index.getDocumentFrequency(entry.getKey()));
            if (idfValue <= 0.0) {
                continue;
            }
            double queryWeight = tf(entry.getValue()) * idfValue;
            terms[kept] = entry.getKey();
            idfValues[kept] = idfValue;
            queryWeights[kept] = queryWeight;
            kept++;
            queryNormSq += queryWeight * queryWeight;
        }

        final int count = kept;
        final double queryNorm = Math.sqrt(queryNormSq);
        if (count == 0 || queryNorm == 0.0) {
            return docId -> 0.0;
        }

        return docId -> {
            double dot = 0.0;
            for (int i = 0; i < count; i++) {
                int docTermFrequency = index.getTermFrequency(terms[i], docId); // binary search O(log n)
                if (docTermFrequency > 0) {
                    dot += queryWeights[i] * tf(docTermFrequency) * idfValues[i];
                }
            }
            if (dot == 0.0) {
                return 0.0;
            }
            double docNorm = Math.sqrt(Math.max(index.getDocLength(docId), 1)); // max(.,1) chong chia 0
            return dot / (queryNorm * docNorm);
        };
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        com.vnsearch.index.InvertedIndex index = new com.vnsearch.index.InvertedIndex();

        com.vnsearch.model.WebDocument doc0 = new com.vnsearch.model.WebDocument();
        doc0.setDocId(0);
        doc0.setTitle("Máy tính xách tay");
        doc0.setBodyText("Máy tính xách tay giá rẻ chất lượng tốt cho sinh viên");
        index.addDocument(doc0);

        com.vnsearch.model.WebDocument doc1 = new com.vnsearch.model.WebDocument();
        doc1.setDocId(1);
        doc1.setTitle("Công thức nấu ăn");
        doc1.setBodyText("Công thức nấu ăn ngon mỗi ngày cho gia đình");
        index.addDocument(doc1);

        TfIdfScorer scorer = new TfIdfScorer();
        Map<String, Integer> query = Map.of("máy_tính", 1);
        System.out.println("score(query='máy tính', doc0) = " + scorer.score(query, 0, index));
        System.out.println("score(query='máy tính', doc1) = " + scorer.score(query, 1, index));
    }
}
