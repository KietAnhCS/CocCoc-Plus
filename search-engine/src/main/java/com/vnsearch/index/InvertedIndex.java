package com.vnsearch.index;

/**
 * TODO (PHASE 4): Inverted index tu cai dat.
 *
 * Cau truc: HashMap<String term, List<Posting>>. Posting list LUON giu sap
 * xep tang dan theo docId (dieu kien tien quyet de PostingListMerger merge
 * nhanh bang two-pointer). Luu them Map<Integer docId, WebDocument> va
 * Map<Integer docId, Integer> docLength.
 *
 * Method du kien:
 *   - void addDocument(WebDocument doc)
 *   - List<Posting> getPostings(String term)
 *   - int getDocumentFrequency(String term)
 */
public class InvertedIndex {
    // TODO: implement in PHASE 4
}
