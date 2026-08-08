package com.vnsearch.eval;

import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.model.WebDocument;
import com.vnsearch.service.IndexBuilder;
import com.vnsearch.storage.JsonDocumentStore;

import java.io.IOException;
import java.util.List;

/**
 * Do BO NHO thuc te cua chi muc, tach theo tung thanh phan.
 *
 * <p><b>Vi sao can cong cu nay.</b> Ban ra soat truoc uoc luong rang bo
 * {@code bodyText} khoi chi muc se giam 60-70% bo nho. Con so do la <b>ngoai
 * suy, chua do</b>. Ma quyet dinh toi uu dua tren con so chua do la cach nhanh
 * nhat de bo cong vao dung cho khong đau: neu phan lon bo nho nam o posting
 * list chu khong o van ban, thi go bodyText di van khong doi duoc gi nhieu.
 *
 * <p><b>Cach do.</b> Do chenh lech bo nho da dung truoc va sau tung buoc, moi
 * lan deu goi {@link #settle()} de bo thu gom rac don dep phan trung gian.
 * Phep do nay <b>khong chinh xac tuyet doi</b> — {@code System.gc()} chi la
 * mot loi de nghi, va bo nho con chua ca rac chua don. Nhung sai so do la
 * ngau nhien va nho so voi khoang cach giua cac thanh phan, nen no du de tra
 * loi cau hoi that su can tra loi: <i>phan nao chiem nhieu nhat?</i>
 *
 * <p>Chay:
 * <pre>
 *   MAVEN_OPTS=-Xmx4g ./mvnw -q compile exec:java \
 *     -Dexec.mainClass=com.vnsearch.eval.MemoryBreakdown \
 *     -Dexec.args="data/measure-corpus.json"
 * </pre>
 */
public final class MemoryBreakdown {

    private MemoryBreakdown() {
    }

    /** Bo nho dang thuc su bi giu, tinh bang byte. */
    private static long usedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Goi thu gom rac va cho no on dinh.
     *
     * <p>Goi {@code System.gc()} MOT lan la khong du: bo thu gom co the chi
     * don mot phan, va cac doi tuong co {@code finalize}/{@code Cleaner} can
     * hai chu ky moi thuc su duoc giai phong. Lap vai lan cho tri so on dinh.
     */
    private static void settle() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String mb(long bytes) {
        return String.format("%,10.1f MB", bytes / 1048576.0);
    }

    public static void main(String[] args) throws IOException {
        String corpusPath = args.length > 0 ? args[0] : "data/seed-documents.json";

        JsonDocumentStore store = new JsonDocumentStore(corpusPath);
        if (!store.isAvailable()) {
            System.out.println("Khong tim thay corpus: " + corpusPath);
            return;
        }

        settle();
        long baseline = usedBytes();

        // --- Buoc 1: chi NAP tai lieu, chua dung chi muc ---
        // Khong `final`: buoc 3 se buong tham chieu nay de do trang thai on dinh.
        List<WebDocument> documents = store.loadAll();
        settle();
        long afterDocuments = usedBytes();

        // Do rieng phan van ban than bai, bang tong so ky tu. Chuoi Java chua
        // ky tu tieng Viet duoc luu UTF-16 (2 byte/ky tu), vi nen tang nen
        // Latin-1 cua Java 9+ chi ap dung khi MOI ky tu nam trong Latin-1.
        int docCount = documents.size();
        long bodyChars = 0;
        long titleChars = 0;
        for (WebDocument doc : documents) {
            if (doc.getBodyText() != null) {
                bodyChars += doc.getBodyText().length();
            }
            if (doc.getTitle() != null) {
                titleChars += doc.getTitle().length();
            }
        }

        // --- Buoc 2: dung chi muc dao ---
        InvertedIndex index = new IndexBuilder(new VietnameseTokenizer()).build(documents);
        settle();
        long afterIndex = usedBytes();

        // --- Buoc 3: BUONG corpus goc ---
        //
        // Day moi la con so quan trong. Ung dung dang chay KHONG giu danh sach
        // tai lieu goc: SearchEngineFacade dung chi muc xong la buong, va
        // reindex thi doc lai tu dia. Neu chi do o buoc 2 thi ca corpus lan chi
        // muc cung song, va phep do se bao mot con so khong bao gio xay ra trong
        // thuc te.
        documents = null;
        settle();
        long steadyState = usedBytes() - baseline;

        long documentsCost = afterDocuments - baseline;
        long indexCost = afterIndex - afterDocuments;
        long total = afterIndex - baseline;
        long bodyTextBytes = bodyChars * 2L; // UTF-16

        System.out.println();
        System.out.println("=== BO NHO THEO THANH PHAN ===");
        System.out.printf("Corpus                  : %s%n", corpusPath);
        System.out.printf("So tai lieu             : %,d%n", docCount);
        System.out.printf("So term phan biet       : %,d%n", index.getTermCount());
        System.out.printf("Do dai tai lieu TB      : %,.0f token%n", index.getAverageDocLength());
        System.out.println();
        System.out.printf("1. Tai lieu (WebDocument) : %s   %5.1f%%%n",
                mb(documentsCost), 100.0 * documentsCost / total);
        System.out.printf("   trong do bodyText      : %s   %5.1f%%  <- phan co the bo di%n",
                mb(bodyTextBytes), 100.0 * bodyTextBytes / total);
        System.out.printf("   trong do title         : %s%n", mb(titleChars * 2L));
        System.out.printf("2. Chi muc dao            : %s   %5.1f%%%n",
                mb(indexCost), 100.0 * indexCost / total);

        // Dem chinh xac so posting va so vi tri, de quy trach nhiem cho dung
        // thanh phan thay vi doan. Mot `Integer` da dong hop ton 16 byte cho
        // doi tuong + 4..8 byte cho o tham chieu trong mang cua ArrayList; mot
        // `int` trong mang int[] ton dung 4 byte.
        long postingCount = 0;
        long positionCount = 0;
        for (String term : index.getAllTerms()) {
            for (com.vnsearch.index.Posting posting : index.getPostings(term)) {
                postingCount++;
                positionCount += posting.positionCount();
            }
        }
        long boxedCost = positionCount * 24L;   // Integer + o tham chieu
        long rawIntCost = positionCount * 4L;   // neu dung int[]

        System.out.printf("   so posting (term,doc)  : %,d%n", postingCount);
        System.out.printf("   so vi tri (position)   : %,d%n", positionCount);
        System.out.printf("   vi tri dang Integer    : %s   %5.1f%%  <- thu phan lon%n",
                mb(boxedCost), 100.0 * boxedCost / total);
        System.out.printf("   neu doi sang int[]     : %s   (tiet kiem %s)%n",
                mb(rawIntCost), mb(boxedCost - rawIntCost));
        System.out.println("   " + "-".repeat(50));
        System.out.printf("   DINH luc dung chi muc  : %s%n", mb(total));
        System.out.println();
        System.out.println("=== TRANG THAI ON DINH (thu ung dung THAT SU giu) ===");
        System.out.printf("Chi muc, sau khi buong corpus goc : %s%n", mb(steadyState));
        System.out.printf("Bo nho moi trang                  : %,.1f KB%n",
                steadyState / 1024.0 / docCount);
        System.out.println();
        System.out.println("Con so nay moi la con so de so sanh giua cac phien ban:");
        System.out.println("  - dinh luc dung chi muc chi ton tai vai giay;");
        System.out.println("  - trang thai on dinh thi ton tai suot vong doi ung dung.");

        // Giu tham chieu toi luc nay de bo toi uu hoa cua JIT khong thu gom som.
        System.out.printf("%n(kiem tra: chi muc con %d tai lieu)%n", index.getTotalDocs());
    }
}
