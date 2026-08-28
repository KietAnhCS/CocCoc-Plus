package com.vnsearch.query.ast;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.PostingListMerger;

import java.util.ArrayList;
import java.util.List;

/**
 * Nut TRONG cua cay truy van: hop cua moi con.
 *
 * <p>Day chinh la cho {@link PostingListMerger#union} duoc dung. Truoc khi co
 * cay bieu thuc, ham do da ton tai va da co test nhung <b>khong co duong nao
 * goi toi no</b> tu tang truy van — mot cau truc du lieu bi bo phi hoan toan
 * vi ngon ngu truy van khong ho tro OR.
 *
 * <p>Hop bang two-pointer O(m+n) giu nguyen bat bien "sap xep tang dan", nen
 * nut cha van ghep tiep duoc ma khong phai sort.
 */
public record OrNode(List<QueryNode> children) implements QueryNode {

    @Override
    public List<Integer> evaluate(SearchIndex index) {
        List<Integer> accumulator = List.of();
        for (QueryNode child : children) {
            accumulator = PostingListMerger.union(accumulator, child.evaluate(index));
        }
        return accumulator;
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        // Chan tren: tong cac con. Thuc te nho hon neu chung chong lan, nhung
        // chan tren la du de sap xep shortest-first o nut AND cha.
        long sum = 0;
        for (QueryNode child : children) {
            sum += child.estimatedSize(index);
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }

    @Override
    public String describe() {
        List<String> parts = new ArrayList<>(children.size());
        for (QueryNode child : children) {
            parts.add(child.describe());
        }
        return "(" + String.join(" OR ", parts) + ")";
    }
}
