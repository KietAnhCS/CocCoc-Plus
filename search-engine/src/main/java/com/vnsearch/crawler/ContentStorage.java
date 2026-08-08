package com.vnsearch.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vnsearch.model.WebDocument;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Khối "Content Storage"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p>Giữ những trang đã vượt qua khối {@code Content Seen?}. Trong sơ đồ,
 * hai khối này nối với nhau bằng mũi tên hai chiều; ở đây trách nhiệm được
 * tách rạch ròi: {@link ContentSeenFilter} giữ <b>vân tay</b> để trả lời câu
 * hỏi "đã thấy chưa", còn lớp này giữ <b>nội dung thật</b>. Gộp làm một sẽ
 * khiến mỗi lần kiểm tra trùng phải quét cả kho nội dung.
 *
 * <p><b>Lưu trong bộ nhớ, ghi ra đĩa một lần ở cuối phiên.</b> Một phiên
 * crawl 5.000 trang chiếm khoảng vài trăm MB — vừa đủ cho bộ nhớ, và cách
 * này tránh được chi phí ghi đĩa trên đường đi nóng của crawler. Với quy mô
 * lớn hơn nhiều thì phải đổi sang ghi thẳng xuống cơ sở dữ liệu; khi đó chỉ
 * cần thay lớp này, phần còn lại của crawler không đổi.
 *
 * <p>Khoá là URL đã chuẩn hoá, nên một URL chỉ có một bản ghi kể cả khi
 * {@link UrlSeenFilter} có sai sót.
 *
 * <p>Phần đọc corpus về sau do {@code com.vnsearch.storage.DocumentStore}
 * đảm nhiệm; lớp này chỉ lo phía <b>ghi</b> trong lúc crawl.
 */
public class ContentStorage {

    private final ConcurrentHashMap<String, WebDocument> byUrl = new ConcurrentHashMap<>();

    /**
     * Lưu một tài liệu; trả về {@code false} nếu URL này đã có bản ghi
     * (khi đó bản cũ được giữ nguyên).
     */
    public boolean save(WebDocument doc) {
        return byUrl.putIfAbsent(doc.getUrl(), doc) == null;
    }

    public int size() {
        return byUrl.size();
    }

    /** Bản sao danh sách tài liệu đã lưu. */
    public List<WebDocument> all() {
        return new ArrayList<>(byUrl.values());
    }

    /**
     * Ghi danh sách tài liệu ra file JSON (Jackson), tạo thư mục cha nếu chưa có.
     *
     * <p><b>Ghi qua tệp tạm rồi đổi tên, chứ không ghi thẳng.</b> Corpus thật
     * nặng vài chục MB nên việc ghi kéo dài cả giây. Ghi đè trực tiếp thì trong
     * suốt khoảng đó tệp đích ở trạng thái dở dang, và một lần Ctrl+C hay mất
     * điện đúng lúc sẽ để lại một tệp JSON cụt — mất luôn corpus CŨ vốn đang
     * hoàn chỉnh, đổi lấy corpus mới cũng hỏng. Đổi tên là thao tác nguyên tử ở
     * mức hệ tệp: tệp đích hoặc còn nguyên bản cũ, hoặc đã là bản mới đầy đủ,
     * không có trạng thái ở giữa.
     *
     * <p>Chi tiết này quan trọng hơn hẳn khi có ghi điểm kiểm tra định kỳ
     * ({@link CheckpointCrawlListener}): số lần ghi đè tăng từ một lần lên hàng
     * chục lần mỗi phiên, nên xác suất bị cắt ngang cũng tăng theo.
     */
    public static void saveToJson(List<WebDocument> documents, String path) throws IOException {
        Path filePath = Path.of(path);
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);

        Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), documents);
        try {
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Vài hệ tệp (thường là ổ mạng) không hỗ trợ đổi tên nguyên tử.
            // Đổi tên thường vẫn tốt hơn nhiều so với ghi đè trực tiếp: cửa sổ
            // nguy hiểm rút từ cả giây xuống còn một thao tác siêu dữ liệu.
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static List<WebDocument> loadFromJson(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WebDocument[] docs = mapper.readValue(new File(path), WebDocument[].class);
        return new ArrayList<>(List.of(docs));
    }
}
