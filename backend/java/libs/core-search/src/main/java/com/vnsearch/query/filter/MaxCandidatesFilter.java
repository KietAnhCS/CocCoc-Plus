package com.vnsearch.query.filter;

import java.util.List;

/**
 * Chan tren so ung vien duoc dua sang khau cham diem.
 *
 * <p><b>Van de.</b> Truy van mot term rat pho bien co the cho hang nghin ung
 * vien, va TAT CA deu duoc cham diem o tang sau: moi ung vien ton
 * {@code O(q log d)} cho scorer. Voi 4.000 ung vien va 3 term, do la khoang
 * 132.000 phep so sanh — trong khi nguoi dung chi xem 10 ket qua dau.
 *
 * <p><b>Cach chuan cua nganh</b> la <b>WAND</b> hoac <b>MaxScore</b>: uoc
 * luong chan tren diem cua tung tai lieu va bo qua som nhung tai lieu khong
 * the lot top-K. Chung phuc tap va doi hoi luu chan tren diem theo term.
 *
 * <p>Bo loc nay la mot chan tren <b>don gian va an toan</b>: giu lai
 * {@code maxCandidates} ung vien dau tien. Vi posting list sap xep theo docId
 * chu khong theo diem, phep cat nay KHONG bao toan top-K mot cach chinh xac —
 * do la danh doi co y thuc, va no chi kich hoat o nguong rat cao (mac dinh
 * 10.000) nen thuc te khong anh huong den cac truy van binh thuong.
 *
 * <p><b>Noi ro han che nay</b> quan trong hon viec giau no: bo loc bao ve he
 * thong khoi truy van bat thuong, khong phai mot toi uu xep hang.
 */
public final class MaxCandidatesFilter implements CandidateFilter {

    /** Nguong mac dinh: du cao de khong anh huong truy van binh thuong. */
    public static final int DEFAULT_MAX_CANDIDATES = 10_000;

    private final int maxCandidates;

    public MaxCandidatesFilter() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public MaxCandidatesFilter(int maxCandidates) {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates phai > 0, nhan duoc: " + maxCandidates);
        }
        this.maxCandidates = maxCandidates;
    }

    @Override
    public boolean isApplicable(FilterContext context) {
        return true;
    }

    @Override
    public List<Integer> apply(List<Integer> candidates, FilterContext context) {
        if (candidates.size() <= maxCandidates) {
            return candidates; // truong hop pho bien: khong lam gi, khong cap phat
        }
        return List.copyOf(candidates.subList(0, maxCandidates));
    }

    @Override
    public String name() {
        return "max-candidates";
    }
}
