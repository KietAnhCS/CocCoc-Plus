package com.vnsearch.index;

import com.vnsearch.model.WebDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inverted index tu cai dat: {@code Map<String term, List<Posting>>}.
 *
 * <p>Cai dat {@link SearchIndex} de tang truy van/xep hang khong phu thuoc
 * lop cu the nay (xem Javadoc cua giao dien ve ly do).
 *
 * <p><b>BAT BIEN TRUNG TAM.</b> Posting list cua moi term LUON duoc giu sap
 * xep TANG DAN NGHIEM NGAT theo docId. Bat bien nay duoc dam bao <b>mien
 * phi</b> — khong ton mot phep sort nao — nho hai dieu kien:
 * <ol>
 *   <li>{@link #addDocument} luon duoc goi theo thu tu docId TANG DAN;</li>
 *   <li>moi lan chi APPEND vao cuoi posting list.</li>
 * </ol>
 * Truoc day dieu kien (1) phu thuoc vao viec NGUOI GOI nho sort truoc — mot
 * bat bien quan trong ma lop khong tu bao ve. Nay lop tu ep bang
 * {@code lastDocId}: goi sai se nem {@link IllegalArgumentException} NGAY tai
 * cho sai, thay vi tra ket qua sai mot cach im lang o tang tren (binary search
 * tren danh sach chua sap xep cho ket quy tuy y, khong nem ngoai le).
 *
 * <p>Ho tro tim khong dau: voi moi token, ca ban co dau (term) LAN ban khong
 * dau (noDiacriticTerm) deu duoc dung lam khoa rieng, cung tro toi cac Posting
 * giong nhau — nho vay truy van "may tinh" (khong dau) van tim ra tai lieu
 * chua "máy tính" (co dau).
 *
 * <p><b>Flyweight:</b> moi khoa term di qua {@link TermDictionary#intern} nen
 * chi co MOT instance {@code String} cho moi term phan biet, du term do xuat
 * hien hang nghin lan trong corpus.
 *
 * <p>Do phuc tap thoi gian: {@link #addDocument} O(L) voi L = so token.
 * {@link #getPostings}/{@link #getDocumentFrequency}/{@link #getAverageDocLength}
 * deu O(1). {@link #getTermFrequency}/{@link #getPositions} O(log n).
 * Do phuc tap khong gian: O(tong so cap (term, doc)).
 */
public class InvertedIndex implements SearchIndex {

    private final Map<String, List<Posting>> index = new LinkedHashMap<>();
    private final Map<Integer, WebDocument> documents = new LinkedHashMap<>();
    private final Map<Integer, Integer> docLength = new LinkedHashMap<>();

    /**
     * Van ban than bai, luu DA NEN, tach khoi {@link #documents}.
     *
     * <p>Xep hang khong dung toi truong nay; chi khau sinh doan trich cho top-K
     * moi can, va no giai nen dung {@code K} tai lieu. Giu nguyen van trong
     * {@code WebDocument} thi ton gap khoang bon lan — xem
     * {@link CompressedText} ve lua chon nen tai cho thay vi doc tu CSDL.
     */
    private final Map<Integer, byte[]> bodyTexts = new LinkedHashMap<>();
    private final Tokenizer tokenizer;
    private final TermDictionary termDictionary = new TermDictionary();

    /** Tong so token cua ca corpus, giu san de tinh do dai trung binh trong O(1). */
    private long totalTokens = 0;

    /** docId cua lan {@link #addDocument} gan nhat — dung de EP bat bien sap xep. */
    private int lastDocId = Integer.MIN_VALUE;

    public InvertedIndex(Tokenizer tokenizer) {
        this.tokenizer = tokenizer == null ? new VietnameseTokenizer() : tokenizer;
    }

    public InvertedIndex() {
        this(new VietnameseTokenizer());
    }

    /**
     * Them mot tai lieu vao index.
     *
     * <p><b>PHAI goi theo thu tu docId tang dan nghiem ngat</b> de giu bat bien
     * "posting list sap xep theo docId" (xem Javadoc lop). Vi pham se nem
     * ngoai le ngay lap tuc — bien mot loi im lang thanh mot loi on ao.
     *
     * @throws IllegalArgumentException neu docId khong lon hon docId truoc do
     */
    public void addDocument(WebDocument doc) {
        addDocument(doc, tokenizer.tokenize(indexableText(doc)));
    }

    /**
     * Van ban duoc dua vao chi muc cho mot tai lieu: tieu de + mo ta + than bai.
     *
     * <p>Tach thanh ham cong khai de {@code IndexBuilder} <b>tach tu song
     * song</b> tren nhieu nhan roi moi nap tuan tu vao day. Neu ham nay chi la
     * mot bieu thuc noi trong {@link #addDocument}, buoc tach tu — phan chiem
     * gan nhu toan bo thoi gian dung chi muc — bi khoa cung vao mot luong.
     */
    public static String indexableText(WebDocument doc) {
        return String.join(" ",
                doc.getTitle() != null ? doc.getTitle() : "",
                doc.getMetaDescription() != null ? doc.getMetaDescription() : "",
                doc.getBodyText() != null ? doc.getBodyText() : "");
    }

    /**
     * Them mot tai lieu voi danh sach token DA TACH SAN.
     *
     * <p>Cung rang buoc thu tu docId tang dan nhu {@link #addDocument(WebDocument)}.
     * Nguoi goi chiu trach nhiem bao dam token duoc tach bang <b>dung</b>
     * tokenizer cua chi muc nay — xem bat bien song hanh o Javadoc cua
     * {@link Tokenizer}.
     *
     * @throws IllegalArgumentException neu docId khong lon hon docId truoc do
     */
    public void addDocument(WebDocument doc, List<VietnameseTokenizer.Token> tokens) {
        int docId = doc.getDocId();
        if (docId <= lastDocId) {
            throw new IllegalArgumentException(
                    "addDocument phai duoc goi theo docId TANG DAN de giu bat bien"
                            + " 'posting list sap xep theo docId'. docId truoc = " + lastDocId
                            + ", docId hien tai = " + docId
                            + ". Hay sap xep danh sach tai lieu truoc khi index.");
        }
        lastDocId = docId;

        // Van ban than bai di duong RIENG, o dang nen. Ban luu trong `documents`
        // khong con truong do — neu giu ca hai thi khong tiet kiem duoc gi.
        bodyTexts.put(docId, CompressedText.compress(doc.getBodyText()));
        documents.put(docId, doc.withoutBodyText());
        docLength.put(docId, tokens.size());
        totalTokens += tokens.size();

        // Gom vi tri theo term TRUOC, roi moi tao Posting: neu tao Posting ngay
        // khi gap token thi mot term xuat hien 5 lan se sinh 5 Posting cho CUNG
        // mot docId, pha vo gia dinh "moi (term, doc) mot posting" ma binary
        // search dua vao.
        Map<String, List<Integer>> positionsByTerm = new LinkedHashMap<>();
        for (VietnameseTokenizer.Token token : tokens) {
            String term = termDictionary.intern(token.term()); // ← Flyweight
            positionsByTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(token.position());
            if (!token.noDiacriticTerm().equals(token.term())) {
                String noDiacritic = termDictionary.intern(token.noDiacriticTerm());
                positionsByTerm.computeIfAbsent(noDiacritic, k -> new ArrayList<>()).add(token.position());
            }
        }

        for (Map.Entry<String, List<Integer>> entry : positionsByTerm.entrySet()) {
            // Chuyen sang int[] NGAY tai day: danh sach tam o tren chi song
            // trong pham vi mot tai lieu, con mang ket qua thi nam lai trong chi
            // muc suot vong doi ung dung. Xem Javadoc cua Posting ve so do.
            List<Integer> positions = entry.getValue();
            int[] packed = new int[positions.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = positions.get(i);
            }
            Posting posting = new Posting(docId, packed.length, packed);
            index.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(posting); // ← APPEND
        }
    }

    /** O(1) - tra ve posting list cua mot term (rong neu term khong ton tai). */
    @Override
    public List<Posting> getPostings(String term) {
        return index.getOrDefault(term, List.of());
    }

    /** O(1) - so tai lieu co chua term nay (document frequency, dung cho IDF). */
    @Override
    public int getDocumentFrequency(String term) {
        return getPostings(term).size();
    }

    /**
     * O(log n) - binary search tren posting list (da sap xep theo docId) de
     * tim tan suat cua {@code term} trong dung {@code docId}.
     *
     * <p>Truoc day ham nay duoc SAO CHEP gan nhu y het o ba noi
     * ({@code TfIdfScorer}, {@code BM25Scorer}, va o day) — mot dang trung lap
     * ma neu doi cach luu tru (skip list, chi muc nen) se phai sua ba cho.
     * Nay gom ve dung mot cai dat.
     */
    @Override
    public int getTermFrequency(String term, int docId) {
        // Lấy posting list MỘT lần rồi dùng lại. Trước đây dòng này gọi
        // getPostings(term) hai lần, tức tra bảng băm hai lần cho cùng một khoá
        // — mà đây là hàm nóng nhất của cả hệ thống: TF-IDF gọi nó cho MỖI ứng
        // viên nhân MỖI term của truy vấn.
        List<Posting> postings = getPostings(term);
        int position = binarySearchPosting(postings, docId);
        return position < 0 ? 0 : postings.get(position).termFrequency();
    }

    /**
     * O(log n) - danh sach vi tri xuat hien cua {@code term} trong {@code docId}.
     * Dung cho phrase search (kiem tra cac term co nam LIEN TIEP khong).
     *
     * <p>Tra ve mang NOI BO, khong phai ban sao: sao chep o day se dung het
     * phan tiet kiem ma {@code int[]} vua mang lai, va phrase search goi ham
     * nay cho MOI term nhan MOI tai lieu ung vien. Nguoi goi phai coi mang tra
     * ve la CHI DOC — day la danh doi co y, duoc ghi ro thay vi de ngam.
     */
    @Override
    public int[] getPositions(String term, int docId) {
        List<Posting> postings = getPostings(term);
        int position = binarySearchPosting(postings, docId);
        return position < 0 ? EMPTY_POSITIONS : postings.get(position).positions();
    }

    /** Dung chung cho moi lan "khong tim thay" — khoi cap phat mang rong moi lan. */
    private static final int[] EMPTY_POSITIONS = new int[0];

    @Override
    public PostingCursor cursor(String term) {
        return PostingCursor.of(getPostings(term));
    }

    /**
     * Binary search tren posting list da sap xep theo docId.
     *
     * <p>Chu y {@code (low + high) >>> 1} thay vi {@code / 2}: voi danh sach
     * rat lon, {@code low + high} co the TRAN int thanh so am, va {@code /2}
     * giu nguyen dau am -> chi so am. Dich bit khong dau xu ly dung ca khi
     * tran. Day la loi kinh dien tung ton tai 9 nam trong
     * {@code java.util.Arrays.binarySearch} cua chinh JDK.
     *
     * @return chi so cua posting, hoac -1 neu khong tim thay
     */
    private static int binarySearchPosting(List<Posting> postings, int docId) {
        int low = 0;
        int high = postings.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midDocId = postings.get(mid).docId();
            if (midDocId == docId) {
                return mid;
            } else if (midDocId < docId) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return -1;
    }

    @Override
    public WebDocument getDocument(int docId) {
        return documents.get(docId);
    }

    /**
     * Van ban than bai cua mot tai lieu, giai nen tai cho.
     *
     * <p><b>Goi ham nay CHI cho nhung tai lieu that su duoc tra ve.</b> Moi lan
     * goi la mot lan giai nen; goi cho toan bo ung vien se dung het phan tiet
     * kiem ma viec nen mang lai, lai them chi phi CPU. {@code ResultRanker} da
     * duoc chia hai giai doan dung vi ly do nay: cham diem truoc cho moi ung
     * vien, sinh doan trich sau chi cho top-K.
     *
     * @return van ban, hoac chuoi rong neu tai lieu khong ton tai
     */
    @Override
    public String getBodyText(int docId) {
        return CompressedText.decompress(bodyTexts.get(docId));
    }

    /** So luong term (khoa) khac nhau dang co trong index. */
    @Override
    public int getTermCount() {
        return index.size();
    }

    @Override
    public int getDocLength(int docId) {
        return docLength.getOrDefault(docId, 0);
    }

    /**
     * O(1) - do dai tai lieu trung binh (tinh bang so token) tren toan corpus.
     *
     * <p>Duy tri bang mot bien tong cong don thay vi cong lai toan bo map moi
     * lan goi: BM25 goi ham nay cho MOI tai lieu ung vien cua MOI truy van,
     * nen mot phep cong O(N) o day se bien viec xep hang thanh O(N*c).
     */
    @Override
    public double getAverageDocLength() {
        int docCount = docLength.size();
        return docCount == 0 ? 0.0 : (double) totalTokens / docCount;
    }

    @Override
    public int getTotalDocs() {
        return documents.size();
    }

    /**
     * Toan bo tai lieu, <b>khong sua doi duoc</b>.
     *
     * <p>Truoc day ham nay tra ve THANG map noi bo, nen nguoi goi co the
     * {@code index.getAllDocuments().clear()} va pha huy trang thai chi muc.
     * Boc {@code unmodifiableMap} lam moi thao tac ghi nem
     * {@link UnsupportedOperationException} ngay tai cho sai.
     */
    @Override
    public Map<Integer, WebDocument> getAllDocuments() {
        return Collections.unmodifiableMap(documents);
    }

    /** So term phan biet trong kho Flyweight — dung cho thong ke bo nho. */
    public int getInternedTermCount() {
        return termDictionary.size();
    }

    /**
     * Moi khoa term dang co, <b>khong sua doi duoc</b>.
     *
     * <p>Dung cho cac cong cu duyet toan bo chi muc (do bo nho, thong ke). Tra
     * ve khung nhin {@code unmodifiable} chu khong phai ban sao: duyet 56.000
     * term khong nen phai cap phat them mot tap moi.
     */
    public Set<String> getAllTerms() {
        return Collections.unmodifiableSet(index.keySet());
    }

    /**
     * Phiên bản hiện hành của định dạng file chỉ mục.
     *
     * <p>Lịch sử: <b>v1</b> ghi posting list thẳng ra JSON, không nén.
     * <b>v2</b> ghi ở dạng {@link CompressedPostings} (delta + VByte + base64).
     * <b>v3</b> tách {@code bodyText} khỏi {@code WebDocument} sang một bản đồ
     * riêng đã nén ({@link CompressedText}) — trước đó văn bản đầy đủ nằm ngay
     * trong tài liệu và chiếm phần lớn kích thước tệp chỉ mục.
     * Các định dạng <b>không đọc lẫn nhau được</b>.
     */
    public static final int FORMAT_VERSION = 3;

    /**
     * Dung boi IndexPersistence de luu/nap toan bo trang thai index ra file.
     *
     * <p>Posting list duoc luu o dang <b>da nen</b> ({@link CompressedPostings}
     * — delta + VByte), khong phai dang bo nho. Xem Javadoc cua lop do ve ba
     * ky thuat nen va ly do khong dung GZIP tong quat.
     *
     * <p><b>Vì sao có trường {@code version}.</b> Khi định dạng đổi từ v1 sang
     * v2, những file chỉ mục cũ còn nằm trên đĩa vẫn được nạp thử, và Jackson
     * ném ra một {@code MismatchedInputException} nói về kiểu dữ liệu — hoàn
     * toàn không gợi ý rằng vấn đề thật là "file này thuộc định dạng đời
     * trước". Một số hiệu phiên bản biến lỗi khó hiểu đó thành một câu thông
     * báo nói đúng việc phải làm.
     *
     * <p>File v1 không có trường này, nên Jackson để {@code version = 0} — và
     * đó chính là dấu hiệu nhận ra định dạng cũ.
     *
     * <p><b>Vì sao có thêm trường {@code tokenizer}.</b> {@code version} chỉ canh
     * được <i>định dạng nhị phân</i>, không canh được <i>nội dung</i>. Nhưng chỉ
     * mục còn phụ thuộc một thứ nữa mà định dạng không hề biết: <b>bộ tách từ đã
     * dựng ra nó</b>. Javadoc của {@link Tokenizer} nêu bất biến sống còn — tầng
     * chỉ mục và tầng truy vấn phải dùng cùng một tokenizer VÀ cùng một từ điển.
     *
     * <p>Vi phạm bất biến đó là một lỗi <b>hoàn toàn im lặng</b>. Ví dụ thật đã
     * xảy ra: từ điển đổi từ 154 lên 49.793 mục, câu "không trung thực" trước
     * tách thành {@code [không_trung][thực]}, nay thành {@code [không][trung_thực]}.
     * Chỉ mục cũ trên đĩa vẫn đúng định dạng v2, vẫn nạp trót lọt, và mọi truy vấn
     * về chủ đề đó lặng lẽ trả về rỗng — không ngoại lệ, không log, không test đỏ.
     *
     * <p>Trường này lưu {@link Tokenizer#name()}, chuỗi đã chứa sẵn tên thuật toán
     * và <b>kích thước từ điển</b>, nên mọi thay đổi đáng kể đều làm nó khác đi.
     * File đời trước không có trường này ({@code null}) và được chấp nhận có cảnh
     * báo — không thể khẳng định chúng sai, chỉ là không kiểm chứng được.
     */
    record IndexData(int version, String tokenizer,
                      Map<String, CompressedPostings> index,
                      Map<Integer, WebDocument> documents,
                      Map<Integer, byte[]> bodyTexts,
                      Map<Integer, Integer> docLength) {
    }

    IndexData exportData() {
        Map<String, CompressedPostings> compressed = new LinkedHashMap<>(index.size());
        for (Map.Entry<String, List<Posting>> entry : index.entrySet()) {
            compressed.put(entry.getKey(), CompressedPostings.of(entry.getValue()));
        }
        // bodyTexts da o dang byte[]; Jackson tu ma hoa base64 khi ghi JSON,
        // cung co che dang dung cho posting list da nen.
        return new IndexData(FORMAT_VERSION, tokenizer.name(), compressed, documents,
                bodyTexts, docLength);
    }

    static InvertedIndex importData(IndexData data, Tokenizer tokenizer) {
        InvertedIndex result = new InvertedIndex(tokenizer);
        for (Map.Entry<String, CompressedPostings> entry : data.index().entrySet()) {
            // intern lai khoa: nap tu file khong di qua addDocument, nen neu
            // khong intern o day thi loi ich Flyweight bi mat sach sau moi lan
            // khoi dong lai — Jackson tao mot String moi cho MOI khoa.
            result.index.put(result.termDictionary.intern(entry.getKey()),
                    entry.getValue().toPostings());
        }
        result.documents.putAll(data.documents());
        if (data.bodyTexts() != null) {
            result.bodyTexts.putAll(data.bodyTexts());
        }
        result.docLength.putAll(data.docLength());
        // Nap lai tu file KHONG di qua addDocument, nen moi trang thai dan xuat
        // phai duoc tinh lai o day. Quen mot cai la loi im lang: totalTokens = 0
        // -> avgdl = 0 -> BM25 tra ve 0 cho MOI tai lieu.
        result.recomputeDerivedState();
        return result;
    }

    /**
     * Tinh lai moi trang thai DAN XUAT tu trang thai co ban.
     *
     * <p>Gom vao mot cho de moi duong vao cau truc ({@code addDocument},
     * {@code importData}) deu goi dung mot ham — them mot trang thai dan xuat
     * moi chi phai sua mot noi.
     */
    private void recomputeDerivedState() {
        totalTokens = 0;
        for (int length : docLength.values()) {
            totalTokens += length;
        }
        lastDocId = documents.isEmpty()
                ? Integer.MIN_VALUE
                : documents.keySet().stream().mapToInt(Integer::intValue).max().orElse(Integer.MIN_VALUE);
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
        System.out.println("TF('trình_duyệt_web', doc0) = " + index.getTermFrequency("trình_duyệt_web", 0));
        System.out.println("Tong so tai lieu = " + index.getTotalDocs());
        System.out.println("Term phan biet (Flyweight) = " + index.getInternedTermCount());

        // Bat bien duoc EP: goi sai thu tu se nem ngoai le ngay.
        WebDocument outOfOrder = new WebDocument();
        outOfOrder.setDocId(0);
        try {
            index.addDocument(outOfOrder);
        } catch (IllegalArgumentException e) {
            System.out.println("Ep bat bien: " + e.getMessage());
        }
    }
}
