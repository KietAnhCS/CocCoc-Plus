package com.vnsearch.ranking;

/**
 * TODO (PHASE 5): Tong hop diem cuoi cung va sinh snippet.
 *
 * finalScore = alpha*tfidfScore + beta*pageRankScore + gamma*titleMatchBonus
 * (mac dinh alpha=0.6, beta=0.3, gamma=0.1, doc tu application.properties).
 * Dung MinHeap.topK de lay top N, KHONG sort toan bo danh sach.
 * Snippet: tim doan van ban chua nhieu tu khoa nhat bang cua so truot
 * (sliding window), highlight tu khoa bang the <mark>.
 */
public class ResultRanker {
    // TODO: implement in PHASE 5
}
