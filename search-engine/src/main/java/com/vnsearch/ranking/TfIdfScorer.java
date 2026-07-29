package com.vnsearch.ranking;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Posting;

import java.util.List;
import java.util.Map;

/**
 * Tinh diem lien quan giua mot truy van va mot tai lieu bang mo hinh
 * vector TF-IDF + cosine similarity.
 *
 * <p>tf  = 1 + log10(termFrequency)   (log-normalized — giam bot anh huong
 * cua tai lieu lap tu qua nhieu lan, vi tf tang tuyen tinh se lam mot tai
 * lieu spam-tu thang qua de dang).
 * <br>idf = log10(totalDocs / documentFrequency) (tu hiem gap trong it
 * tai lieu se co idf cao hon, mang nhieu thong tin phan biet hon).
 *
 * <p><b>Chuan hoa do dai tai lieu:</b> cosine similarity chuan can chia
 * cho ||vector tai lieu|| — norm nay ve ly thuyet phai tinh tren TAT CA
 * term xuat hien trong tai lieu (khong chi cac term co trong truy van),
 * nhung luu vet do can O(|tu vung|) cho MOI tai lieu. Thay vao do, ta
 * dung xap xi kinh dien cua Lucene classic Similarity:
 * {@code docNorm ≈ sqrt(docLength)} (do dai tai lieu tinh bang so token)
 * — tai lieu cang dai thi cang "loang" tf-idf trung binh tren moi chieu,
 * xap xi nay tranh duoc chi phi luu tru/tinh toan day du ma van phat huy
 * dung tinh chat "trang dai khong bi loi the qua muc vi lap tu nhieu hon".
 *
 * <p>Do phuc tap thoi gian: {@link #score} la O(q log d) voi q = so term
 * khac nhau trong truy van, d = do dai posting list dai nhat trong q term
 * (do dung binary search {@link #findTermFrequencyInDoc} tren posting
 * list da sap xep theo docId).
 */
public class TfIdfScorer {

    public static double tf(int termFrequency) {
        return termFrequency > 0 ? 1 + Math.log10(termFrequency) : 0.0;
    }

    public static double idf(int totalDocs, int documentFrequency) {
        if (documentFrequency <= 0 || totalDocs <= 0) {
            return 0.0;
        }
        return Math.log10((double) totalDocs / documentFrequency);
    }

    /**
     * Tinh diem TF-IDF cosine cua {@code docId} doi voi truy van.
     *
     * @param queryTermFrequency so lan moi term xuat hien trong truy van
     *                           (thuong la 1, nhung cho phep truy van lap
     *                           tu de nhan manh, vi du "internet internet cham")
     */
    public double score(Map<String, Integer> queryTermFrequency, int docId, InvertedIndex index) {
        int totalDocs = index.getTotalDocs();
        double dot = 0.0;
        double queryNormSq = 0.0;

        for (Map.Entry<String, Integer> entry : queryTermFrequency.entrySet()) {
            String term = entry.getKey();
            List<Posting> postings = index.getPostings(term);
            double idfValue = idf(totalDocs, postings.size());
            if (idfValue <= 0.0) {
                continue; // term khong ton tai trong index, hoac xuat hien trong TAT CA tai lieu -> khong phan biet duoc
            }

            double queryWeight = tf(entry.getValue()) * idfValue;
            queryNormSq += queryWeight * queryWeight;

            int docTermFrequency = findTermFrequencyInDoc(postings, docId);
            if (docTermFrequency > 0) {
                double docWeight = tf(docTermFrequency) * idfValue;
                dot += queryWeight * docWeight;
            }
        }

        if (dot == 0.0) {
            return 0.0;
        }
        double queryNorm = Math.sqrt(queryNormSq);
        double docNorm = Math.sqrt(Math.max(index.getDocLength(docId), 1));
        return dot / (queryNorm * docNorm);
    }

    /**
     * Binary search tren posting list (da sap xep tang dan theo docId) de
     * tim termFrequency cua mot tai lieu cu the — O(log n) thay vi quet
     * tuyen tinh O(n). Day la loi ich thu 2 (ngoai two-pointer merge) cua
     * bat bien "posting list luon sap xep theo docId".
     */
    private int findTermFrequencyInDoc(List<Posting> postings, int docId) {
        int low = 0, high = postings.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midDocId = postings.get(mid).docId();
            if (midDocId == docId) {
                return postings.get(mid).termFrequency();
            } else if (midDocId < docId) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return 0;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        InvertedIndex index = new InvertedIndex();

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
