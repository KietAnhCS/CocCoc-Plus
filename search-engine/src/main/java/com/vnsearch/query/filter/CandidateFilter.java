package com.vnsearch.query.filter;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.QueryParser;

import java.util.List;

/**
 * <b>Chain of Responsibility pattern</b> — mot tang loc trong duong ong thu
 * hep danh sach ung vien.
 *
 * <p><b>Van de cua ban cu.</b> {@code CandidateResolver.resolve} co ba tang loc
 * CHON CUNG trong than ham 104 dong. Muon them mot bo loc moi (theo domain,
 * theo ngay dang, theo ngon ngu, theo do dai) phai SUA than ham — vi pham
 * nguyen tac Mo/Dong. Va khong the test rieng tung tang, cung khong the do
 * "tang nao loai bao nhieu ung vien, ton bao nhieu ms".
 *
 * <p><b>Ban nay.</b> Moi tang la mot lop rieng. Them bo loc = them mot lop va
 * mot dong vao danh sach; doi thu tu loc = doi thu tu trong danh sach.
 *
 * <p><b>Thu tu quan trong</b> va phai theo nguyen tac "re va loai nhieu truoc":
 * <pre>
 *   1. Giao posting list   5011 -> ~50   (re nhat, loai nhieu nhat)
 *   2. Khop cum tu          ~50 -> ~20   (dat: binary search moi tai lieu)
 *   3. Loai tru             ~20 -> ~19   (re: tra HashSet)
 * </pre>
 * Neu dao thu tu — kiem tra cum tu truoc khi giao — ta phai chay
 * {@code matchesPhrase} tren 5.011 tai lieu thay vi 50, tuc <b>cham hon 100 lan</b>.
 */
public interface CandidateFilter {

    /**
     * Loc danh sach ung vien. Danh sach dau vao va dau ra deu SAP XEP TANG DAN
     * theo docId — bat bien nay phai duoc moi cai dat giu.
     */
    List<Integer> apply(List<Integer> candidates, FilterContext context);

    /** Ten ngan gon, dung lam nhan khi do chi phi tung tang. */
    String name();

    /**
     * Bo loc nay co viec gi de lam voi truy van nay khong.
     *
     * <p>Cho phep bo qua han mot tang thay vi chay no roi phat hien khong co gi
     * de loc — vi du {@code PhraseFilter} khi truy van khong co dau ngoac kep.
     */
    default boolean isApplicable(FilterContext context) {
        return true;
    }

    /** Du lieu dung chung ma cac tang loc can. */
    record FilterContext(SearchIndex index, QueryParser.ParsedQuery parsed) {
    }
}
