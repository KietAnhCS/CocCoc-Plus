package com.vnsearch.query;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Posting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merge/intersect posting list - phan DSA dat gia cua module truy van.
 *
 * <p>{@link #intersect(List, List)} va {@link #union(List, List)} dung
 * thuat toan two-pointer / merge kieu merge-sort, O(m+n), dua tren bat
 * bien "posting list da sap xep tang dan theo docId" cua
 * {@link com.vnsearch.index.InvertedIndex}. KHONG dung
 * {@code HashSet.retainAll} — voi HashSet, retainAll phai duyet toan bo
 * mot tap (kiem tra contains() O(1) trung binh nhung van O(n)) VA khong
 * tan dung duoc thu tu co san cua du lieu; two-pointer chi duyet MOI phan
 * tu cua ca 2 danh sach dung 1 lan (O(m+n) tuyet doi, khong co hang so
 * an cua hashing), dong thoi KHONG can cap phat cau truc bam trung gian.
 * Ket qua do luong trong DSA-REPORT.md se so sanh cu the.
 *
 * <p>Toi uu khi truy van nhieu term ({@link #intersectAll}): sap xep cac
 * posting list theo do dai TANG DAN roi intersect lan luot tu ngan nhat.
 * Vi sao toi uu: goi A la ket qua giao sau k buoc dau, luon co
 * |A| &lt;= min(cac list da xet). Bat dau tu list NGAN NHAT lam ket qua
 * ban dau giup |A| nho ngay tu dau, nen cac buoc intersect tiep theo
 * (O(|A| + |list ke tiep|)) re hon rat nhieu so voi bat dau tu list dai —
 * dac biet hieu qua khi co 1 term rat hiem (df nho) tron trong query nhieu
 * term pho bien (df lon).
 */
public class PostingListMerger {

    /** Trich danh sach docId (da sap xep) tu mot posting list. */
    public static List<Integer> docIdsOf(List<Posting> postings) {
        List<Integer> ids = new ArrayList<>(postings.size());
        for (Posting p : postings) {
            ids.add(p.docId());
        }
        return ids;
    }

    /** O(m+n) - giao 2 danh sach docId da sap xep bang two-pointer. */
    public static List<Integer> intersect(List<Integer> a, List<Integer> b) {
        List<Integer> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            int docA = a.get(i);
            int docB = b.get(j);
            if (docA == docB) {
                result.add(docA);
                i++;
                j++;
            } else if (docA < docB) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    /** O(m+n) - hop 2 danh sach docId da sap xep (kieu merge-sort), loai trung. */
    public static List<Integer> union(List<Integer> a, List<Integer> b) {
        List<Integer> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            int docA = a.get(i);
            int docB = b.get(j);
            if (docA == docB) {
                result.add(docA);
                i++;
                j++;
            } else if (docA < docB) {
                result.add(docA);
                i++;
            } else {
                result.add(docB);
                j++;
            }
        }
        while (i < a.size()) {
            result.add(a.get(i++));
        }
        while (j < b.size()) {
            result.add(b.get(j++));
        }
        return result;
    }

    /**
     * Giao TAT CA cac posting list truyen vao (dung cho AND ngam dinh giua
     * nhieu term trong 1 truy van). Sap xep theo do dai tang dan truoc khi
     * intersect tuan tu (xem Javadoc lop).
     */
    public static List<Integer> intersectAll(List<List<Posting>> postingLists) {
        if (postingLists.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<Posting>> sorted = new ArrayList<>(postingLists);
        sorted.sort(Comparator.comparingInt(List::size));

        List<Integer> result = docIdsOf(sorted.get(0));
        for (int i = 1; i < sorted.size() && !result.isEmpty(); i++) {
            result = intersect(result, docIdsOf(sorted.get(i)));
        }
        return result;
    }

    /**
     * Kiem tra {@code docId} co chua cum tu (phrase) khop CHINH XAC thu tu
     * lien tiep khong — dung danh sach positions cua tung term (tra ve boi
     * {@link InvertedIndex#getPositions}) de xac nhan term thu i+1 nam o
     * vi tri = vi tri term thu i + 1.
     */
    public static boolean matchesPhrase(InvertedIndex index, List<String> phraseTerms, int docId) {
        if (phraseTerms.isEmpty()) {
            return true;
        }
        List<Integer> firstPositions = index.getPositions(phraseTerms.get(0), docId);
        for (int start : firstPositions) {
            boolean allMatch = true;
            for (int i = 1; i < phraseTerms.size(); i++) {
                List<Integer> positions = index.getPositions(phraseTerms.get(i), docId);
                if (!positions.contains(start + i)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return true;
            }
        }
        return false;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        List<Posting> a = List.of(
                new Posting(1, 2, List.of(0, 5)),
                new Posting(3, 1, List.of(2)),
                new Posting(5, 1, List.of(1)),
                new Posting(7, 1, List.of(0)));
        List<Posting> b = List.of(
                new Posting(2, 1, List.of(0)),
                new Posting(3, 1, List.of(1)),
                new Posting(5, 2, List.of(0, 3)),
                new Posting(8, 1, List.of(0)));

        System.out.println("intersect(a,b) = " + intersect(docIdsOf(a), docIdsOf(b)));
        System.out.println("union(a,b) = " + union(docIdsOf(a), docIdsOf(b)));
    }
}
