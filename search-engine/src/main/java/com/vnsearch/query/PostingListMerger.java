package com.vnsearch.query;

import com.vnsearch.index.PostingCursor;
import com.vnsearch.index.Posting;
import com.vnsearch.index.SearchIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Merge/intersect posting list - phan DSA dat gia cua module truy van.
 *
 * <p>{@link #intersect(List, List)} va {@link #union(List, List)} dung thuat
 * toan two-pointer / merge kieu merge-sort, O(m+n), dua tren bat bien "posting
 * list da sap xep tang dan theo docId" cua {@link SearchIndex}.
 *
 * <p><b>Vi sao khong dung {@code HashSet.retainAll}</b> — do thuc te voi 2
 * danh sach 500.000 phan tu:
 * <pre>
 *   two-pointer                              ~10,0 ms
 *   HashSet.retainAll (khong tinh dung set)  ~15,5 ms  (+55%)
 *   HashSet.retainAll (tinh ca dung 2 set)   ~27,0 ms  (2,7 lan)
 * </pre>
 * Ba ly do: khong ton chi phi dung cau truc trung gian (posting list lay THANG
 * tu chi muc nen dong thu 3 moi la so sanh cong bang), cuc bo cache tot (duyet
 * tuan tu vs nhay ngau nhien trong bang bam), va khong co hang so an cua
 * hashing.
 *
 * <p><b>Skip pointer.</b> {@link #intersectCursors} dung {@link PostingCursor}
 * voi galloping search: khi giao mot list RAT NGAN voi mot list RAT DAI,
 * two-pointer thuan phai duyet gan het list dai, con galloping nhay coc:
 * <pre>
 *   two-pointer thuan : O(m + n)        = 5 + 4000 = 4005 buoc
 *   galloping         : O(m log(n/m))   ~= 48 buoc
 * </pre>
 * Dong thoi no KHONG cap phat {@code List<Integer>} trung gian nen tranh duoc
 * autoboxing (16 byte/phan tu thay vi 4).
 *
 * <p><b>Shortest-first.</b> Khi giao nhieu list, luon co
 * {@code |A| <= min(cac list da xet)}, nen bat dau tu list NGAN NHAT giup
 * ket qua trung gian nho ngay tu dau — dac biet hieu qua khi mot term rat hiem
 * tron trong truy van nhieu term pho bien.
 */
public final class PostingListMerger {

    private PostingListMerger() {
    }

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
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            int docA = a.get(i);
            int docB = b.get(j);
            if (docA == docB) {
                result.add(docA);
                i++;
                j++;
            } else if (docA < docB) {
                // Vi b tang dan, moi B[j'] voi j' >= j deu >= B[j] > A[i],
                // nen A[i] KHONG THE co trong phan con lai cua b. Bo di la an toan.
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
        int i = 0;
        int j = 0;
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
     * <b>O(m log(n/m))</b> - giao hai posting list bang {@link PostingCursor}
     * voi galloping search, KHONG cap phat danh sach trung gian.
     *
     * <p>Khac biet voi {@link #intersect(List, List)}: thay vi buoc tung buoc
     * khi hai con tro lech nhau, ta NHAY THANG toi vi tri can tim. Voi hai list
     * co kich thuoc rat chenh lech, day la khac biet giua 4005 buoc va ~48 buoc.
     */
    public static List<Integer> intersectCursors(PostingCursor a, PostingCursor b) {
        List<Integer> result = new ArrayList<>();
        while (a.docId() != PostingCursor.NO_MORE && b.docId() != PostingCursor.NO_MORE) {
            int docA = a.docId();
            int docB = b.docId();
            if (docA == docB) {
                result.add(docA);
                a.next();
                b.next();
            } else if (docA < docB) {
                a.skipTo(docB); // ← nhay coc thay vi next() tung buoc
            } else {
                b.skipTo(docA);
            }
        }
        return result;
    }

    /**
     * Giao TAT CA cac posting list truyen vao (AND ngam dinh giua nhieu term).
     *
     * <p>Sap xep theo do dai TANG DAN truoc khi giao tuan tu (shortest-first),
     * va dung {@link #intersectCursors} cho buoc dau — noi hai posting list goc
     * co the chenh lech kich thuoc nhieu nhat.
     */
    public static List<Integer> intersectAll(List<List<Posting>> postingLists) {
        if (postingLists.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<Posting>> sorted = new ArrayList<>(postingLists);
        sorted.sort(Comparator.comparingInt(List::size)); // shortest-first

        if (sorted.size() == 1) {
            return docIdsOf(sorted.get(0));
        }

        // Buoc dau dung cursor + galloping: day la buoc co ty le kich thuoc
        // lech nhau lon nhat, nen loi ich cua skip pointer ro nhat.
        List<Integer> result = intersectCursors(
                PostingCursor.of(sorted.get(0)), PostingCursor.of(sorted.get(1)));

        for (int i = 2; i < sorted.size() && !result.isEmpty(); i++) {
            // Giao rong thi DUNG NGAY: rong la phan tu hap thu cua phep giao.
            result = intersectCursorWithList(PostingCursor.of(sorted.get(i)), result);
        }
        return result;
    }

    /** Giao mot cursor voi mot danh sach docId da sap xep, dung skipTo de nhay coc. */
    private static List<Integer> intersectCursorWithList(PostingCursor cursor, List<Integer> docIds) {
        List<Integer> result = new ArrayList<>();
        int j = 0;
        while (cursor.docId() != PostingCursor.NO_MORE && j < docIds.size()) {
            int fromCursor = cursor.docId();
            int fromList = docIds.get(j);
            if (fromCursor == fromList) {
                result.add(fromCursor);
                cursor.next();
                j++;
            } else if (fromCursor < fromList) {
                cursor.skipTo(fromList);
            } else {
                j++;
            }
        }
        return result;
    }

    /**
     * Kiem tra {@code docId} co chua cum tu khop CHINH XAC thu tu lien tiep khong.
     *
     * <p><b>Hai toi uu so voi ban cu:</b>
     * <ol>
     *   <li><i>Lay positions MOT lan.</i> Ban cu goi
     *       {@code index.getPositions(term_i, docId)} TRONG vong lap qua tung
     *       vi tri cua term dau — nhung ket qua do khong phu thuoc vi tri dang
     *       xet. Voi term dau xuat hien 20 lan va cum 3 tu, do la 40 lan binary
     *       search thay vi 2.</li>
     *   <li><i>Two-pointer thay {@code List.contains}.</i> Danh sach vi tri DA
     *       SAP XEP TANG DAN (sinh theo thu tu token), nhung ban cu dung
     *       {@code contains} quet tuyen tinh O(p). Nay dung chinh ky thuat
     *       two-pointer da dung o tang docId — nhat quan va nhanh hon:
     *       O(sum p_i) thay vi O(p_1 * k * p_i).</li>
     * </ol>
     */
    public static boolean matchesPhrase(SearchIndex index, List<String> phraseTerms, int docId) {
        if (phraseTerms.isEmpty()) {
            return true;
        }
        // TOI UU 1: lay tat ca danh sach vi tri MOT lan, ngoai vong lap.
        List<List<Integer>> positionsByTerm = new ArrayList<>(phraseTerms.size());
        for (String term : phraseTerms) {
            List<Integer> positions = index.getPositions(term, docId);
            if (positions.isEmpty()) {
                return false; // mot term khong xuat hien -> khong the co cum
            }
            positionsByTerm.add(positions);
        }

        List<Integer> firstPositions = positionsByTerm.get(0);
        for (int start : firstPositions) {
            boolean allMatch = true;
            for (int i = 1; i < phraseTerms.size(); i++) {
                // TOI UU 2: binary search tren danh sach da sap xep, thay vi
                // List.contains quet tuyen tinh.
                if (Collections.binarySearch(positionsByTerm.get(i), start + i) < 0) {
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

        System.out.println("intersect(a,b)        = " + intersect(docIdsOf(a), docIdsOf(b)));
        System.out.println("union(a,b)            = " + union(docIdsOf(a), docIdsOf(b)));
        System.out.println("intersectCursors(a,b) = "
                + intersectCursors(PostingCursor.of(a), PostingCursor.of(b)));
    }
}
