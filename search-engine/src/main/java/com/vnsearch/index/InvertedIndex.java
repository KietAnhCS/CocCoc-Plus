package com.vnsearch.index;

import com.vnsearch.model.WebDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverted index tu cai dat: {@code HashMap<String term, List<Posting>>}.
 *
 * <p>Posting list cua moi term LUON duoc giu sap xep TANG DAN theo docId —
 * day la dieu kien tien quyet bat buoc de {@link com.vnsearch.query.PostingListMerger}
 * co the giao/hop cac posting list bang thuat toan two-pointer O(m+n) thay
 * vi phai sort lai O(n log n) moi lan truy van. Bat bien nay duoc dam bao
 * vi {@link #addDocument} luon duoc goi theo thu tu docId TANG DAN (crawler
 * gan docId tang dan roi moi index), va moi lan addDocument chi APPEND vao
 * cuoi moi posting list lien quan.
 *
 * <p>Ho tro tim khong dau: voi moi token, ca ban co dau (term) LAN ban
 * khong dau (noDiacriticTerm) deu duoc dung lam khoa rieng trong index,
 * cung tro toi cac Posting giong nhau — nho vay truy van "may tinh" (khong
 * dau) van tim ra tai lieu chua "máy tính" (co dau).
 *
 * <p>Do phuc tap thoi gian: {@link #addDocument} O(L) voi L = do dai van
 * ban (so token). {@link #getPostings}/{@link #getDocumentFrequency} O(1)
 * tra cuu HashMap. Do phuc tap khong gian: O(tong so (term, doc) xuat hien).
 */
public class InvertedIndex {

    private final Map<String, List<Posting>> index = new LinkedHashMap<>();
    private final Map<Integer, WebDocument> documents = new LinkedHashMap<>();
    private final Map<Integer, Integer> docLength = new LinkedHashMap<>();
    private final VietnameseTokenizer tokenizer;

    public InvertedIndex(VietnameseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public InvertedIndex() {
        this(new VietnameseTokenizer());
    }

    /**
     * Them mot tai lieu vao index. PHAI goi theo thu tu docId tang dan de
     * giu bat bien "posting list sap xep theo docId" (xem Javadoc lop).
     */
    public void addDocument(WebDocument doc) {
        String combinedText = String.join(" ",
                doc.getTitle() != null ? doc.getTitle() : "",
                doc.getMetaDescription() != null ? doc.getMetaDescription() : "",
                doc.getBodyText() != null ? doc.getBodyText() : "");

        List<VietnameseTokenizer.Token> tokens = tokenizer.tokenize(combinedText);
        documents.put(doc.getDocId(), doc);
        docLength.put(doc.getDocId(), tokens.size());

        Map<String, List<Integer>> positionsByTerm = new LinkedHashMap<>();
        for (VietnameseTokenizer.Token token : tokens) {
            positionsByTerm.computeIfAbsent(token.term(), k -> new ArrayList<>()).add(token.position());
            if (!token.noDiacriticTerm().equals(token.term())) {
                positionsByTerm.computeIfAbsent(token.noDiacriticTerm(), k -> new ArrayList<>()).add(token.position());
            }
        }

        for (Map.Entry<String, List<Integer>> entry : positionsByTerm.entrySet()) {
            List<Integer> positions = entry.getValue();
            Posting posting = new Posting(doc.getDocId(), positions.size(), positions);
            index.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(posting);
        }
    }

    /** O(1) - tra ve posting list cua mot term (rong neu term khong ton tai). */
    public List<Posting> getPostings(String term) {
        return index.getOrDefault(term, List.of());
    }

    /** O(1) - so tai lieu co chua term nay (document frequency, dung cho IDF). */
    public int getDocumentFrequency(String term) {
        return getPostings(term).size();
    }

    public WebDocument getDocument(int docId) {
        return documents.get(docId);
    }

    public int getDocLength(int docId) {
        return docLength.getOrDefault(docId, 0);
    }

    public int getTotalDocs() {
        return documents.size();
    }

    public Map<Integer, WebDocument> getAllDocuments() {
        return documents;
    }

    /** Dung boi IndexPersistence de luu/nap toan bo trang thai index ra file JSON. */
    record IndexData(Map<String, List<Posting>> index, Map<Integer, WebDocument> documents,
                      Map<Integer, Integer> docLength) {
    }

    IndexData exportData() {
        return new IndexData(index, documents, docLength);
    }

    static InvertedIndex importData(IndexData data, VietnameseTokenizer tokenizer) {
        InvertedIndex result = new InvertedIndex(tokenizer);
        result.index.putAll(data.index());
        result.documents.putAll(data.documents());
        result.docLength.putAll(data.docLength());
        return result;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        InvertedIndex index = new InvertedIndex();

        WebDocument doc1 = new WebDocument();
        doc1.setDocId(0);
        doc1.setTitle("Trình duyệt web là gì");
        doc1.setBodyText("Trình duyệt web giúp người dùng truy cập internet dễ dàng.");
        index.addDocument(doc1);

        WebDocument doc2 = new WebDocument();
        doc2.setDocId(1);
        doc2.setTitle("Máy tính và công nghệ");
        doc2.setBodyText("Máy tính hiện đại chạy nhiều trình duyệt web cùng lúc.");
        index.addDocument(doc2);

        System.out.println("Postings('trình_duyệt_web') = " + index.getPostings("trình_duyệt_web"));
        System.out.println("Postings('may_tinh') (khong dau) = " + index.getPostings("may_tinh"));
        System.out.println("DF('trình_duyệt_web') = " + index.getDocumentFrequency("trình_duyệt_web"));
        System.out.println("Tong so tai lieu = " + index.getTotalDocs());
    }
}
