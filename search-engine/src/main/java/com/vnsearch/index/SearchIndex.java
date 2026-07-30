package com.vnsearch.index;

import com.vnsearch.model.WebDocument;

import java.util.List;
import java.util.Map;

/**
 * <b>Strategy pattern</b> — giao dien cua mot chi muc tim kiem, de tang truy
 * van va tang xep hang khong phu thuoc vao MOT cai dat cu the.
 *
 * <p><b>Vi sao can.</b> Truoc day {@code CandidateResolver}, {@code TfIdfScorer},
 * {@code BM25Scorer}, {@code ResultRanker} deu nhan thang lop cu the
 * {@link InvertedIndex}. Hau qua:
 * <ul>
 *   <li>Khong the thay bang cai dat khac (chi muc tren dia, chi muc nen,
 *       chi muc phan tan) ma khong sua moi noi dung.</li>
 *   <li>Khong the gia lap trong test — muon test scorer phai dung chi muc
 *       that voi tokenizer that.</li>
 *   <li>Khong the do "chi muc nen co cham hon khong" vi khong co hai cai dat
 *       de so sanh.</li>
 * </ul>
 *
 * <p><b>Bat bien ma MOI cai dat phai giu</b> — day la dieu kien tien quyet cho
 * toan bo hieu nang cua tang tren:
 *
 * <blockquote>
 * Voi moi term {@code t}, {@link #getPostings(String)} tra ve danh sach
 * <b>sap xep tang dan nghiem ngat theo {@code docId}</b>.
 * </blockquote>
 *
 * Bat bien nay mo khoa BA thu:
 * <ol>
 *   <li>Giao posting list bang two-pointer {@code O(m+n)} thay vi sort lai
 *       {@code O(n log n)} moi truy van.</li>
 *   <li>Binary search {@code O(log n)} de tra tan suat/vi tri, thay vi quet
 *       tuyen tinh {@code O(n)}.</li>
 *   <li>Nen bang delta encoding (xem {@link VByteCodec}) — hieu giua hai
 *       docId lien tiep nho hon gia tri tuyet doi rat nhieu.</li>
 * </ol>
 */
public interface SearchIndex {

    /** O(1) - posting list cua mot term, SAP XEP TANG DAN theo docId (rong neu khong co). */
    List<Posting> getPostings(String term);

    /** O(1) - so tai lieu chua term nay (document frequency, dung cho IDF). */
    int getDocumentFrequency(String term);

    /** O(log n) - tan suat cua {@code term} trong dung {@code docId} (0 neu khong co). */
    int getTermFrequency(String term, int docId);

    /** O(log n) - danh sach vi tri xuat hien cua {@code term} trong {@code docId}. */
    List<Integer> getPositions(String term, int docId);

    /**
     * O(1) - con tro duyet posting list co ho tro nhay coc.
     * Xem {@link PostingCursor} ve galloping search.
     */
    PostingCursor cursor(String term);

    WebDocument getDocument(int docId);

    /** So term (khoa) phan biet dang co trong chi muc. */
    int getTermCount();

    /** Do dai tai lieu tinh bang so token (0 neu khong co tai lieu do). */
    int getDocLength(int docId);

    /** O(1) - do dai tai lieu trung binh toan corpus, BM25 can de chuan hoa. */
    double getAverageDocLength();

    int getTotalDocs();

    /** Toan bo tai lieu, <b>khong sua doi duoc</b> (bao ve dong goi). */
    Map<Integer, WebDocument> getAllDocuments();
}
