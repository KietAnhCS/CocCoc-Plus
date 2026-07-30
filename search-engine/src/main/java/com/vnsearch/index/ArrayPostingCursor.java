package com.vnsearch.index;

import java.util.List;

/**
 * Cai dat {@link PostingCursor} tren mot {@code List<Posting>} da sap xep
 * tang dan theo {@code docId}, voi {@link #skipTo(int)} dung
 * <b>galloping search</b> (exponential search).
 *
 * <p>Khong cap phat gi trong luc duyet — chi giu mot chi so nguyen.
 *
 * <p>Do phuc tap: {@link #next()} O(1); {@link #skipTo(int)} O(log d) voi d
 * la khoang cach thuc su phai nhay (KHONG phai kich thuoc posting list).
 */
final class ArrayPostingCursor implements PostingCursor {

    private final List<Posting> postings;
    private int index;

    ArrayPostingCursor(List<Posting> postings) {
        this.postings = postings == null ? List.of() : postings;
        this.index = 0;
    }

    @Override
    public int docId() {
        return index < postings.size() ? postings.get(index).docId() : NO_MORE;
    }

    @Override
    public int termFrequency() {
        return index < postings.size() ? postings.get(index).termFrequency() : 0;
    }

    @Override
    public List<Integer> positions() {
        return index < postings.size() ? postings.get(index).positions() : List.of();
    }

    @Override
    public boolean next() {
        if (index < postings.size()) {
            index++;
        }
        return index < postings.size();
    }

    @Override
    public int size() {
        return postings.size();
    }

    /**
     * Galloping search hai pha.
     *
     * <p><b>Pha 1 — nhay theo cap so nhan.</b> Bat dau tu buoc 1, nhan doi moi
     * lan ({@code 1, 2, 4, 8, ...}) cho toi khi vuot qua muc tieu hoac ra khoi
     * mang. Sau {@code ceil(log2 d)} lan nhay ta khoanh duoc muc tieu vao doan
     * {@code (index + step/2, index + step]}.
     *
     * <p><b>Pha 2 — binary search</b> trong doan vua khoanh, tim vi tri dau
     * tien co {@code docId >= target}.
     *
     * <p>Tong: {@code O(log d)} voi d la khoang cach thuc su, khong phu thuoc
     * kich thuoc posting list.
     */
    @Override
    public boolean skipTo(int targetDocId) {
        int n = postings.size();
        if (index >= n) {
            return false;
        }
        if (postings.get(index).docId() >= targetDocId) {
            return true; // da o dung cho hoac vuot qua roi
        }

        // --- Pha 1: nhay theo cap so nhan ---
        int step = 1;
        int low = index;
        int high = index + step;
        while (high < n && postings.get(high).docId() < targetDocId) {
            low = high;
            step <<= 1;                    // 1, 2, 4, 8, ...
            high = index + step;
        }
        if (high >= n) {
            high = n - 1;
        }

        // --- Pha 2: binary search trong doan (low, high] ---
        // Tim vi tri DAU TIEN co docId >= targetDocId.
        int lo = low;
        int hi = high;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;     // >>> chong tran, xem InvertedIndex
            if (postings.get(mid).docId() < targetDocId) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        index = postings.get(lo).docId() >= targetDocId ? lo : n;
        return index < n;
    }
}
