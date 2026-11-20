package com.vnsearch.query.ast;

import com.vnsearch.index.SearchIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * Nut TRONG cua cay truy van: phu dinh — chi hop le TRONG ngu canh
 * {@link AndNode}.
 *
 * <p><b>Vi sao khong danh gia doc lap duoc.</b> Phu dinh thuan tuy cho ra
 * <i>tap bu</i>: voi truy van {@code NOT quảng_cáo} tren corpus 5.011 tai lieu,
 * ket qua la gan 5.000 tai lieu — vua vo nghia voi nguoi dung, vua dat (phai
 * liet ke toan bo corpus roi tru di). Moi he thong tim kiem thuc te deu yeu cau
 * phu dinh gan voi mot menh de khang dinh: {@code A AND NOT B}, khong phai
 * {@code NOT B} don doc.
 *
 * <p>Vi vay {@link #evaluate} nem ngoai le co thong diep ro rang, con
 * {@link #evaluateAgainst} moi la duong dung.
 */
public record NotNode(QueryNode inner) implements QueryNode {

    @Override
    public List<Integer> evaluate(SearchIndex index) {
        throw new UnsupportedOperationException(
                "NOT chi hop le trong ngu canh AND (vi du 'A AND NOT B'); "
                        + "phu dinh doc lap se tra ve gan nhu toan bo corpus.");
    }

    /**
     * <b>O(m+n)</b> - tru tap ket qua cua {@code inner} khoi {@code candidates}
     * bang two-pointer.
     *
     * <p>Dung duoc two-pointer vi CA HAI danh sach deu sap xep tang dan — lai
     * mot lan nua bat bien cua {@link SearchIndex} tra co tuc.
     *
     * @param candidates danh sach ung vien, SAP XEP TANG DAN
     */
    public List<Integer> evaluateAgainst(List<Integer> candidates, SearchIndex index) {
        List<Integer> excluded = inner.evaluate(index);
        if (excluded.isEmpty()) {
            return candidates;
        }
        List<Integer> result = new ArrayList<>(candidates.size());
        int j = 0;
        for (int candidate : candidates) {
            // Vi ca hai sap xep tang dan, con tro j chi tien mot chieu:
            // tong chi phi la O(m+n), khong phai O(m*n).
            while (j < excluded.size() && excluded.get(j) < candidate) {
                j++;
            }
            if (j >= excluded.size() || excluded.get(j) != candidate) {
                result.add(candidate);
            }
        }
        return result;
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        return index.getTotalDocs(); // chan tren tho
    }

    @Override
    public String describe() {
        return "NOT " + inner.describe();
    }
}
