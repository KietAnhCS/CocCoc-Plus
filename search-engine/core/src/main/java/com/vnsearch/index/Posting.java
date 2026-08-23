package com.vnsearch.index;

import java.util.Arrays;

/**
 * Mot muc trong posting list cua inverted index: mot tai lieu (docId) co
 * chua mot term nao do, kem so lan xuat hien (termFrequency) va danh sach
 * vi tri xuat hien (positions - la chi so thu tu token trong tai lieu,
 * dung cho phrase search: 2 term "canh nhau" khi position cua term sau =
 * position cua term truoc + 1).
 *
 * <p>Record (immutable) vi mot Posting khong bao gio thay doi sau khi tao
 * (tai lieu duoc index lai thi tao Posting moi thay vi sua Posting cu).
 *
 * <p><b>Vi sao {@code positions} la {@code int[]} chu khong phai
 * {@code List<Integer>}.</b> Day la thay doi tiet kiem bo nho lon nhat cua ca
 * chi muc, va no duoc quyet dinh bang <b>so do</b> chu khong phai cam tinh —
 * xem {@link com.vnsearch.eval.MemoryBreakdown}. Tren corpus 2.518 trang:
 *
 * <pre>
 *   3.821.061 vi tri, 1.594.938 posting
 *
 *   List&lt;Integer&gt;                       int[]
 *   ├─ Integer: 16 byte/phan tu          ├─ 4 byte/phan tu
 *   ├─ o tham chieu: 4..8 byte           ├─ (khong co)
 *   ├─ ArrayList: 40 byte/danh sach      ├─ (khong co)
 *   └─ Object[] header: 16 byte          └─ mang header: 16 byte/mang
 *
 *   Do duoc: 87,5 MB  ──────────────▶   14,6 MB
 * </pre>
 *
 * <p>Danh sach vi tri la thu <b>chi doc, duyet tuan tu hoac tim nhi phan</b> —
 * khong bao gio them/bot phan tu sau khi tao. Toan bo tien ich cua
 * {@code List} vi vay khong duoc dung toi, chi con lai chi phi.
 *
 * <p><b>Vi sao phai tu viet {@code equals}/{@code hashCode}.</b> Record sinh
 * san hai ham do, nhung voi truong kieu mang chung so sanh theo <b>danh tinh
 * tham chieu</b> chu khong theo noi dung — tuc hai Posting giong het nhau van
 * "khac nhau". Loi nay hoan toan im lang, va no se lam hong dung phep kiem
 * chung quan trong nhat: {@code IndexPersistence} so sanh posting list truoc va
 * sau khi nen de khang dinh vong nen/giai nen khong lam mat du lieu.
 */
public record Posting(int docId, int termFrequency, int[] positions) {

    /** Posting khong co vi tri nao — dung chung mot mang rong duy nhat. */
    private static final int[] NO_POSITIONS = new int[0];

    public Posting {
        if (positions == null) {
            positions = NO_POSITIONS;
        }
    }

    /** So vi tri — thay cho {@code positions().size()} cua ban dung List. */
    public int positionCount() {
        return positions.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Posting that
                && docId == that.docId
                && termFrequency == that.termFrequency
                && Arrays.equals(positions, that.positions);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * docId + termFrequency) + Arrays.hashCode(positions);
    }

    @Override
    public String toString() {
        return "Posting[docId=" + docId + ", tf=" + termFrequency
                + ", positions=" + Arrays.toString(positions) + "]";
    }
}
