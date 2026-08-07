package com.vnsearch.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vnsearch.model.WebDocument;
import com.vnsearch.service.IndexBuilder;
import com.vnsearch.storage.JsonDocumentStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Luu/nap {@link InvertedIndex} ra file trong thu muc data/, de khong phai
 * crawl + index lai moi lan khoi dong ung dung.
 *
 * <p><b>Dinh dang.</b> Mot file JSON duy nhat chua ca ba phan (posting list,
 * WebDocument, do dai tai lieu) — don gian hoa viec nap lai dung thu tu.
 * Rieng <b>posting list duoc nen</b> bang delta + variable-byte truoc khi ghi
 * (xem {@link CompressedPostings}), roi Jackson ma hoa mang byte do sang
 * base64.
 *
 * <p><b>Base64 co lam mat het loi ich nen khong?</b> Khong. Base64 co
 * overhead co dinh 4/3 (+33 %), trong khi so nguyen viet duoi dang JSON ton
 * trung binh 4–6 ky tu ke ca dau phay. Ket qua rong van la mot khoan giam
 * lon — con so do duoc in ra boi {@link #main} va ghi trong
 * {@code docs/DSA-REPORT.md} §4.2.
 *
 * <p><b>Hai thay doi doc lap, do rieng.</b> File cu vua <i>khong nen</i> vua
 * <i>thut dong</i> ({@code INDENT_OUTPUT}). Gop ca hai roi bao mot con so se
 * quy nham cong cua thut dong cho phan nen. {@link #main} vi vay do <b>ba</b>
 * moc: (thut dong + khong nen) → (goi + khong nen) → (goi + nen), tach bach
 * dong gop cua tung thay doi. Day la cung mot bai hoc phuong phap voi loi
 * JIT warmup ghi o {@code DSA-REPORT.md} §3.2: <i>khong bao gio doi hai bien
 * cung luc roi bao mot ty le</i>.
 */
public class IndexPersistence {

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static void save(InvertedIndex index, String path) throws IOException {
        Path filePath = Path.of(path);
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
        createMapper().writeValue(new File(path), index.exportData());
    }

    /**
     * Nạp chỉ mục từ file, <b>kiểm tra số hiệu phiên bản trước</b>.
     *
     * <p>Trước đây hàm này nạp thẳng và để Jackson tự vấp: một file chỉ mục
     * định dạng cũ cho ra {@code MismatchedInputException: Cannot deserialize
     * value of type CompressedPostings from Array value} — thông báo nói về
     * kiểu dữ liệu Java chứ không nói về nguyên nhân thật, và người đọc không
     * có cách nào đoán ra rằng việc cần làm chỉ là xoá file đi.
     *
     * <p>Nay phiên bản được đọc trước; sai phiên bản thì ném
     * {@link IOException} nói đúng phải làm gì.
     *
     * @throws IOException nếu file không đọc được, hoặc thuộc định dạng đời khác
     */
    public static InvertedIndex load(String path, Tokenizer tokenizer) throws IOException {
        InvertedIndex.IndexData data;
        try {
            data = createMapper().readValue(new File(path), InvertedIndex.IndexData.class);
        } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
            throw new IOException(formatMismatchMessage(path, 1), e);
        }
        if (data.version() != InvertedIndex.FORMAT_VERSION) {
            throw new IOException(formatMismatchMessage(path, data.version()));
        }
        checkTokenizerMatches(path, data.tokenizer(), tokenizer);
        return InvertedIndex.importData(data, tokenizer);
    }

    /**
     * Chặn việc nạp một chỉ mục do bộ tách từ KHÁC dựng nên.
     *
     * <p>Đây là bất biến sống còn nêu trong Javadoc của {@link Tokenizer}: chỉ mục
     * và truy vấn phải dùng cùng một tokenizer và cùng một từ điển. Nếu lệch, chỉ
     * mục vẫn đúng định dạng và vẫn nạp trót lọt, nhưng term hai bên sinh ra không
     * còn khớp nhau — truy vấn trả về rỗng mà <b>không có bất kỳ dấu hiệu nào</b>.
     *
     * <p>Ném {@link IOException} chứ không phải ghi log rồi chạy tiếp, vì bên gọi
     * ({@code SearchEngineFacade.loadCorpus}) đã bắt sẵn ngoại lệ này và tự dựng
     * lại chỉ mục từ corpus gốc — đúng việc cần làm. Chỉ mục dựng sẵn là
     * <b>cache dẫn xuất</b>, không phải nguồn sự thật, nên vứt đi rồi làm lại luôn
     * là hành vi đúng.
     *
     * @param stored dấu vân tay đọc từ file; {@code null} với file đời trước
     */
    private static void checkTokenizerMatches(String path, String stored, Tokenizer current)
            throws IOException {
        if (stored == null) {
            // File ghi trước khi có trường này. Không thể khẳng định nó sai — chỉ
            // là không kiểm chứng được. Cảnh báo thay vì chặn, để chỉ mục cũ hợp lệ
            // không bị vứt đi oan.
            System.err.println("[CANH BAO] Chi muc '" + path + "' khong ghi dau van tay"
                    + " tokenizer (dinh dang doi truoc). Khong the kiem chung no co khop voi"
                    + " bo tach tu hien tai hay khong. Neu ket qua tim kiem rong bat thuong,"
                    + " hay xoa file nay de dung lai chi muc.");
            return;
        }
        String expected = current.name();
        if (!stored.equals(expected)) {
            throw new IOException("File chỉ mục '" + path + "' được dựng bởi một bộ tách từ"
                    + " KHÁC với bộ đang dùng.\n"
                    + "  trong file : " + stored + "\n"
                    + "  hiện tại   : " + expected + "\n"
                    + "Chỉ mục và truy vấn bắt buộc phải dùng cùng một tokenizer và cùng một"
                    + " từ điển, nếu không term hai bên sinh ra sẽ không khớp và mọi truy vấn"
                    + " trả về rỗng một cách im lặng. Chỉ mục sẽ được dựng lại từ corpus gốc.");
        }
    }

    private static String formatMismatchMessage(String path, int foundVersion) {
        return "File chỉ mục '" + path + "' thuộc định dạng phiên bản "
                + (foundVersion <= 1 ? "cũ (v1, không nén)" : "v" + foundVersion)
                + ", nhưng mã nguồn hiện tại đọc định dạng v" + InvertedIndex.FORMAT_VERSION
                + " (delta + VByte). Hai định dạng KHÔNG đọc lẫn nhau được."
                + " Cách xử lý: xoá file này đi — chỉ mục sẽ được dựng lại từ corpus gốc"
                + " và ghi ra ở định dạng mới.";
    }

    public static InvertedIndex load(String path) throws IOException {
        return load(path, new VietnameseTokenizer());
    }

    // ------------------------------------------------------------------
    // Duoi day CHI phuc vu phep do so sanh dinh dang — khong nam tren duong
    // chay cua ung dung. Giu lai trong ma nguon (thay vi lam mot lan roi xoa)
    // de con so trong tai lieu TAI LAP duoc bat cu luc nao.
    // ------------------------------------------------------------------

    /** Dinh dang CU: posting list ghi thang, khong nen. Chi dung lam moc do. */
    record RawIndexData(Map<String, List<Posting>> index, Map<Integer, WebDocument> documents,
                        Map<Integer, Integer> docLength) {
    }

    private static void saveRaw(InvertedIndex index, String path, boolean indent) throws IOException {
        InvertedIndex.IndexData compressed = index.exportData();
        Map<String, List<Posting>> raw = new LinkedHashMap<>(compressed.index().size());
        for (Map.Entry<String, CompressedPostings> entry : compressed.index().entrySet()) {
            raw.put(entry.getKey(), entry.getValue().toPostings());
        }
        ObjectMapper mapper = createMapper();
        if (indent) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        mapper.writeValue(new File(path),
                new RawIndexData(raw, compressed.documents(), compressed.docLength()));
    }

    /**
     * Do kich thuoc ba dinh dang tren corpus that va kiem chung nap lai dung
     * nguyen ven. Chay:
     * <pre>
     *   MAVEN_OPTS=-Xmx4g ./mvnw.cmd -q compile exec:java \
     *     -Dexec.mainClass=com.vnsearch.index.IndexPersistence \
     *     -Dexec.args="data/crawled-multi.json"
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        String corpusPath = args.length > 0 ? args[0] : "data/seed-documents.json";

        JsonDocumentStore store = new JsonDocumentStore(corpusPath);
        if (!store.isAvailable()) {
            System.out.println("Khong tim thay corpus: " + corpusPath);
            return;
        }
        List<WebDocument> documents = store.loadAll();
        documents.sort(Comparator.comparingInt(WebDocument::getDocId));
        System.out.printf("Corpus: %s — %d tai lieu%n", corpusPath, documents.size());

        InvertedIndex index = new IndexBuilder(new VietnameseTokenizer()).build(documents);
        System.out.printf("Chi muc: %d term phan biet%n%n", index.getTermCount());

        Path directory = Files.createTempDirectory("vnsearch-index-format");
        String prettyRaw = directory.resolve("a-thutdong-khongnen.json").toString();
        String compactRaw = directory.resolve("b-goi-khongnen.json").toString();
        String compactVByte = directory.resolve("c-goi-nen.json").toString();

        saveRaw(index, prettyRaw, true);
        saveRaw(index, compactRaw, false);
        save(index, compactVByte);

        long a = Files.size(Path.of(prettyRaw));
        long b = Files.size(Path.of(compactRaw));
        long c = Files.size(Path.of(compactVByte));

        System.out.println("=== KICH THUOC FILE CHI MUC THEO DINH DANG ===");
        System.out.printf("A. Thut dong + khong nen (dinh dang CU) : %,d byte  (%.1f MB)%n", a, a / 1048576.0);
        System.out.printf("B. Goi     + khong nen                  : %,d byte  (%.1f MB)  -%.1f%% so voi A%n",
                b, b / 1048576.0, 100.0 * (a - b) / a);
        System.out.printf("C. Goi     + nen VByte (dinh dang MOI)  : %,d byte  (%.1f MB)  -%.1f%% so voi B%n",
                c, c / 1048576.0, 100.0 * (b - c) / b);
        System.out.printf("%nTong cong A -> C: giam %.1f%% (nho %.2f lan)%n",
                100.0 * (a - c) / a, (double) a / c);

        // Kiem chung: nap lai tu dinh dang nen phai ra dung chi muc cu.
        InvertedIndex reloaded = load(compactVByte);
        boolean same = reloaded.getTermCount() == index.getTermCount()
                && reloaded.getTotalDocs() == index.getTotalDocs()
                && Math.abs(reloaded.getAverageDocLength() - index.getAverageDocLength()) < 1e-9;
        String sampleTerm = index.getPostings("công_nghệ").isEmpty() ? null : "công_nghệ";
        if (sampleTerm != null) {
            same = same && reloaded.getPostings(sampleTerm).equals(index.getPostings(sampleTerm));
        }
        System.out.println("\nNap lai tu dinh dang nen — dung nguyen ven: " + same);
    }
}
