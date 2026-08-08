package com.vnsearch.index;

import java.util.List;

/**
 * <b>Iterator pattern</b> — con tro duyet mot posting list <i>khong cap phat</i>,
 * co ho tro <b>nhay coc (skip)</b> bang galloping search.
 *
 * <p><b>Vi sao can:</b> cach cu ({@code PostingListMerger.docIdsOf}) vat chat
 * hoa moi posting list thanh {@code List<Integer>} truoc khi giao. Voi posting
 * list 4.000 muc, moi {@code docId} bi <i>autobox</i> thanh mot object
 * {@code Integer} 16 byte thay vi 4 byte — <b>64 KB rac GC</b> moi lan goi, va
 * goi k lan cho truy van k term. Cursor duyet thang tren du lieu goc:
 * <b>khong cap phat gi</b>.
 *
 * <p><b>Loi ich thu hai — quan trong hon:</b> {@link #skipTo(int)} cho phep
 * <i>nhay coc</i> thay vi buoc tung buoc. Khi giao mot list rat NGAN voi mot
 * list rat DAI, two-pointer thuan phai duyet gan het list dai:
 * <pre>
 *   two-pointer thuan : O(m + n)          = 5 + 4000 = 4005 buoc
 *   galloping skipTo  : O(m * log(n/m))   = 5 * log(800) ~= 48 buoc
 * </pre>
 *
 * <p><b>Galloping search (con goi la exponential search)</b> hoat dong hai pha:
 * <ol>
 *   <li><i>Pha nhay:</i> tang buoc nhay theo cap so nhan 1, 2, 4, 8, ... cho
 *       toi khi vuot qua muc tieu. Ton {@code O(log d)} voi d la khoang cach
 *       that su phai nhay.</li>
 *   <li><i>Pha thu hep:</i> binary search trong doan vua khoanh duoc, cung
 *       {@code O(log d)}.</li>
 * </ol>
 * Diem manh so voi binary search thuan tren ca mang: chi phi phu thuoc
 * <b>khoang cach that</b> (d) chu khong phu thuoc <b>kich thuoc mang</b> (n).
 * Khi hai posting list co nhieu phan tu chung, d nho nen galloping gan nhu
 * mien phi; khi chung lech nhau nhieu, no nhay xa duoc ngay.
 *
 * <p>Ky thuat nay dua hoan toan vao bat bien "posting list sap xep tang dan
 * theo docId" ma {@link InvertedIndex} dam bao.
 */
public interface PostingCursor {

    /** Gia tri bao hieu da duyet het posting list. */
    int NO_MORE = Integer.MAX_VALUE;

    /** O(1) - docId hien tai, hoac {@link #NO_MORE} neu da het. */
    int docId();

    /** O(1) - tan suat term trong tai lieu hien tai. */
    int termFrequency();

    /** O(1) - danh sach vi tri xuat hien trong tai lieu hien tai (CHI DOC). */
    int[] positions();

    /** O(1) - tien mot buoc. Tra ve {@code false} neu da het. */
    boolean next();

    /**
     * <b>O(log d)</b> - nhay toi posting dau tien co
     * {@code docId >= targetDocId} bang galloping search.
     *
     * @return {@code false} neu khong con posting nao thoa man
     */
    boolean skipTo(int targetDocId);

    /** Tong so posting — dung de sap xep shortest-first. */
    int size();

    /** Tao cursor tren mot posting list da sap xep theo docId. */
    static PostingCursor of(List<Posting> postings) {
        return new ArrayPostingCursor(postings);
    }
}
