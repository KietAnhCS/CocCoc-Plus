package com.vnsearch.query.ast;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.PostingListMerger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Nut TRONG cua cay truy van: giao cua moi con.
 *
 * <p><b>Tu ap dung shortest-first.</b> Co so la bat dang thuc
 * {@code |A ∩ B| <= min(|A|, |B|)}: giao khong bao gio lon hon tap nho hon.
 * Nen neu bat dau tu con cho IT ket qua nhat, ket qua trung gian nho ngay tu
 * dau va cac buoc giao sau — moi buoc ton {@code O(|A| + |list ke tiep|)} —
 * re hon dang ke.
 *
 * <p>Uoc luong kich thuoc dung {@link QueryNode#estimatedSize} nen KHONG phai
 * danh gia that de biet nen sap xep the nao: voi {@link TermNode} do chi la
 * mot phep tra document frequency O(1).
 *
 * <p><b>Xu ly NOT.</b> {@link NotNode} khong danh gia doc lap duoc (phu dinh
 * thuan tuy cho ra gan nhu ca corpus), nen nut nay tach rieng cac con NOT ra
 * va ap chung SAU CUNG, dang phep tru tren tap ket qua khang dinh.
 */
public record AndNode(List<QueryNode> children) implements QueryNode {

    @Override
    public List<Integer> evaluate(SearchIndex index) {
        if (children.isEmpty()) {
            return List.of();
        }

        List<QueryNode> positives = new ArrayList<>(children.size());
        List<NotNode> negatives = new ArrayList<>();
        for (QueryNode child : children) {
            if (child instanceof NotNode not) {
                negatives.add(not);
            } else {
                positives.add(child);
            }
        }
        if (positives.isEmpty()) {
            throw new UnsupportedOperationException(
                    "AND chi gom cac menh de NOT thi khong danh gia duoc; "
                            + "can it nhat mot menh de khang dinh.");
        }

        positives.sort(Comparator.comparingInt(node -> node.estimatedSize(index))); // shortest-first

        List<Integer> accumulator = positives.get(0).evaluate(index);
        for (int i = 1; i < positives.size(); i++) {
            if (accumulator.isEmpty()) {
                return List.of(); // rong la phan tu HAP THU cua phep giao
            }
            accumulator = PostingListMerger.intersect(accumulator, positives.get(i).evaluate(index));
        }

        for (NotNode negative : negatives) {
            if (accumulator.isEmpty()) {
                break;
            }
            accumulator = negative.evaluateAgainst(accumulator, index);
        }
        return accumulator;
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        int min = Integer.MAX_VALUE;
        for (QueryNode child : children) {
            if (child instanceof NotNode) {
                continue; // NOT khong thu hep uoc luong
            }
            min = Math.min(min, child.estimatedSize(index));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    @Override
    public String describe() {
        List<String> parts = new ArrayList<>(children.size());
        for (QueryNode child : children) {
            parts.add(child.describe());
        }
        return "(" + String.join(" AND ", parts) + ")";
    }
}
