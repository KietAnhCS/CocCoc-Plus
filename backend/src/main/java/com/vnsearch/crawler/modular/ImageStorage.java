package com.vnsearch.crawler.modular;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vnsearch.crawler.bus.ImageFound;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Ghi và đọc kho ảnh dưới dạng JSON — phần <b>lưu bền</b> của
 * {@link ImageStore}.
 *
 * <h2>Vì sao lớp này phải tồn tại</h2>
 *
 * <p>{@link ImageStore} nằm hoàn toàn trong bộ nhớ tiến trình. Hệ quả không ai
 * nói ra nhưng ai dùng cũng gặp:
 *
 * <ul>
 *   <li><b>Khởi động lại backend là mất sạch ảnh.</b> Tab "Hình ảnh" trống trơn
 *       cho tới khi có một phiên crawl mới, dù corpus văn bản trên đĩa vẫn
 *       nguyên vẹn và tab "Tất cả" vẫn đầy kết quả. Người dùng thấy hai tab
 *       bất đồng nhau mà không có lỗi nào để lần.</li>
 *   <li><b>Công cụ ngoài tiến trình không đọc được gì.</b> {@code crawl-stats}
 *       thống kê corpus bằng cách đọc tệp; ảnh không có tệp nào nên không có
 *       cách nào đếm — kể cả khi vừa crawl xong hàng nghìn ảnh.</li>
 * </ul>
 *
 * <p>Cả hai đều biến mất khi kho ảnh có một tệp của riêng nó.
 *
 * <h2>Tệp RIÊNG, không nhét thêm trường vào corpus</h2>
 *
 * <p>Cách rẻ hơn là thêm một mảng {@code images} vào mỗi {@code WebDocument}.
 * Không chọn cách đó, vì ba lý do:
 *
 * <ol>
 *   <li>Corpus đang được đọc bởi {@code EvaluationRunner},
 *       {@code TokenizerBenchmark}, {@code QrelsEvaluationRunner} và
 *       {@code crawl-stats.ps1}. Đổi lược đồ của nó là đụng vào tất cả.</li>
 *   <li>Ảnh và văn bản có <b>vòng đời khác nhau</b>: ảnh do một Modular Service
 *       riêng sinh ra, qua bus, có thể tới sau khi trang đã được lưu. Ghép
 *       chúng vào một tệp là ép hai nhịp ghi khác nhau dùng chung một khoá.</li>
 *   <li>Corpus đã là 87&nbsp;MB cho vài nghìn trang. Ai chỉ cần số liệu ảnh
 *       không nên phải quét cả phần {@code bodyText}.</li>
 * </ol>
 *
 * <p>Nên tệp ảnh là tệp <b>anh em</b> của corpus, tên do {@link #pathFor} suy
 * ra. Cặp tệp đi cùng nhau: {@code data/foo.json} có
 * {@code data/foo.images.json}.
 *
 * <h2>Ghi nguyên tử — cùng lý do ContentStorage đã ghi</h2>
 *
 * <p>Ghi ra {@code .tmp} rồi đổi tên. Bị cắt ngang giữa chừng thì tệp đích hoặc
 * còn nguyên bản cũ, hoặc đã là bản mới đầy đủ — không có trạng thái JSON cụt ở
 * giữa. Điều này quan trọng hơn hẳn khi có ghi điểm kiểm tra định kỳ, vì số lần
 * ghi đè tăng từ một lên hàng chục mỗi phiên.
 *
 * @see ImageStore kho trong bộ nhớ mà lớp này ghi ra và nạp vào
 */
public final class ImageStorage {

    /** Hậu tố tệp ảnh, gắn vào tên corpus — xem {@link #pathFor}. */
    private static final String SUFFIX = ".images.json";

    private ImageStorage() {
    }

    /**
     * Tệp ảnh đi kèm một tệp corpus.
     *
     * <pre>
     *   data/crawled-documents.json  ->  data/crawled-documents.images.json
     *   data/thu-nghiem.json         ->  data/thu-nghiem.images.json
     * </pre>
     *
     * <p><b>Suy ra chứ không cấu hình.</b> Một hằng số {@code data/images.json}
     * dùng chung sẽ hỏng ngay khi có hai corpus trong cùng thư mục: phiên crawl
     * thứ hai ghi đè ảnh của phiên thứ nhất, và từ đó số ảnh không còn ứng với
     * số trang nữa. Buộc tên tệp ảnh theo tên corpus khiến trạng thái sai đó
     * không biểu diễn được.
     *
     * @param corpusPath đường dẫn tệp corpus {@code .json}
     */
    public static String pathFor(String corpusPath) {
        if (corpusPath == null || corpusPath.isBlank()) {
            throw new IllegalArgumentException("corpusPath không được rỗng");
        }
        String base = corpusPath.endsWith(".json")
                ? corpusPath.substring(0, corpusPath.length() - ".json".length())
                : corpusPath;
        return base + SUFFIX;
    }

    /**
     * Ghi toàn bộ kho ảnh ra tệp, nguyên tử.
     *
     * <p>Danh sách rỗng vẫn được ghi, không bỏ qua: một tệp chứa {@code []} nói
     * "đã crawl, không tìm được ảnh nào", còn không có tệp nói "chưa crawl lần
     * nào". Hai ca đó cần hai lời khuyên khác nhau ở {@code crawl-stats}, nên
     * phân biệt được chúng là có ích.
     */
    public static void saveToJson(Collection<ImageFound> images, String path) throws IOException {
        Path filePath = Path.of(path);
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // INDENT_OUTPUT: cùng lựa chọn mà ContentStorage đã làm, và ở đây nó
        // còn là một ràng buộc thật — crawl-stats.ps1 đọc tệp theo TỪNG DÒNG
        // bằng StreamReader chứ không nạp cả cây JSON. Cách đọc đó chỉ chạy khi
        // mỗi trường nằm trên một dòng riêng. Tắt INDENT_OUTPUT ở đây là làm
        // hỏng thống kê mà không có lỗi biên dịch nào báo.
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), new ArrayList<>(images));
        try {
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Vài hệ tệp (thường là ổ mạng) không hỗ trợ đổi tên nguyên tử.
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Đọc kho ảnh từ tệp.
     *
     * <p>Jackson dựng lại {@link ImageFound} qua hàm khởi tạo chính của record.
     * Hai phương thức {@code isDownloaded()} và {@code missingAlt()} mang
     * {@code @JsonIgnore} nên không lọt vào tệp — nếu lọt, chính lời gọi này sẽ
     * ném {@code UnrecognizedPropertyException}. Xem javadoc của
     * {@link ImageFound#isDownloaded()} về lần lỗi đó đã xảy ra thật.
     */
    public static List<ImageFound> loadFromJson(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ImageFound[] images = mapper.readValue(new File(path), ImageFound[].class);
        return new ArrayList<>(List.of(images));
    }

    /**
     * Đọc tệp ảnh nếu có, trả về danh sách rỗng nếu không có hoặc tệp hỏng.
     *
     * <p>Dành cho đường khởi động: thiếu ảnh làm giao diện nghèo đi, còn ném
     * ngoại lệ ở đây thì <b>backend không lên được</b> — hỏng cả phần tìm kiếm
     * văn bản vốn chẳng liên quan gì. Đánh đổi rõ ràng nghiêng về phía chạy
     * được, và người gọi vẫn ghi log để lần khi cần.
     */
    public static List<ImageFound> loadQuietly(String path) {
        try {
            if (path == null || !Files.exists(Path.of(path))) {
                return List.of();
            }
            return loadFromJson(path);
        } catch (Exception e) {
            return List.of();
        }
    }
}
