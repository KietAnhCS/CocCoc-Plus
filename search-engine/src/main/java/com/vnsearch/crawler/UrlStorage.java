package com.vnsearch.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * <b>Khối "URL Storage"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p><b>Vấn đề nó giải quyết.</b> {@link UrlSeenFilter} trả lời được câu hỏi
 * "URL này đã gặp chưa" nhưng chỉ trong bộ nhớ: tắt chương trình là mất
 * sạch. Một phiên crawl vài chục nghìn trang gần như chắc chắn phải dừng
 * giữa chừng (hết giờ, mất mạng, máy khởi động lại), và khi chạy lại thì
 * crawler tải lại từ đầu toàn bộ những trang đã có. Lớp này ghi bền danh
 * sách URL đã gặp để phiên sau nạp lại và đi tiếp.
 *
 * <p><b>Vì sao lưu URL chứ không lưu bộ lọc Bloom.</b> Bloom Filter chỉ là
 * một mảng bit, ghi ra đĩa được — nhưng kích thước của nó được tính từ
 * {@code maxPages} của phiên crawl. Phiên sau đổi {@code maxPages} là mảng
 * bit cũ không dùng lại được. Lưu URL dạng văn bản thì dựng lại được bộ lọc
 * ở bất kỳ kích thước nào, và còn đọc được bằng mắt khi cần gỡ lỗi.
 *
 * <p><b>Tệp chỉ ghi thêm (append-only).</b> Mỗi dòng một URL. Không sửa,
 * không xoá, nên hai tiến trình cùng ghi cũng không làm hỏng cấu trúc tệp.
 *
 * <p><b>Đánh đổi đã biết: có đệm, không flush mỗi dòng.</b> Ở tốc độ crawl
 * thực tế, mỗi trang sinh hơn 100 URL nên đây là đường đi nóng; flush từng
 * dòng sẽ biến ghi đĩa thành nút thắt cổ chai. Cái giá là nếu tiến trình bị
 * giết đột ngột thì phần đuôi trong bộ đệm mất. Hậu quả chỉ là crawl lại
 * vài trang ở phiên sau — chấp nhận được, vì crawl lại một trang đã có thì
 * vô hại, còn bỏ sót một trang chưa có mới là mất mát thật.
 *
 * <p><b>Mặc định tắt</b> — dùng {@link #disabled()}. Chỉ bật khi cấu hình
 * {@code CrawlConfig.urlStoragePath(...)}, vì với phiên crawl vài trăm trang
 * để thử nghiệm thì việc lưu bền không đáng chi phí.
 *
 * <p>Thread-safe: mọi thao tác ghi đều nằm trong khối {@code synchronized}.
 */
public final class UrlStorage implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(UrlStorage.class);

    /** {@code null} nghĩa là chế độ tắt: mọi thao tác ghi trở thành lệnh rỗng. */
    private final Path path;

    private final Object lock = new Object();
    private BufferedWriter writer;
    private long written;

    private UrlStorage(Path path) {
        this.path = path;
    }

    /** Chế độ tắt — không lưu gì, không đụng đĩa. */
    public static UrlStorage disabled() {
        return new UrlStorage(null);
    }

    /** Lưu vào một tệp văn bản, ghi thêm vào nội dung sẵn có nếu tệp đã tồn tại. */
    public static UrlStorage file(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path không được null; dùng disabled() nếu muốn tắt");
        }
        return new UrlStorage(path);
    }

    public boolean isEnabled() {
        return path != null;
    }

    public Path getPath() {
        return path;
    }

    public long getWrittenCount() {
        synchronized (lock) {
            return written;
        }
    }

    /**
     * Ghi thêm một URL. Lỗi ghi đĩa <b>không</b> làm hỏng phiên crawl: lưu
     * bền chỉ là tiện ích cho lần chạy sau, không phải điều kiện để crawl
     * chạy được.
     */
    public void append(String url) {
        if (path == null || url == null || url.isBlank()) {
            return;
        }
        synchronized (lock) {
            try {
                if (writer == null) {
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                writer.write(url);
                writer.newLine();
                written++;
            } catch (IOException e) {
                log.warn("Không ghi được URL vào {}: {}", path, e.getMessage());
            }
        }
    }

    /**
     * Đọc lại toàn bộ URL đã lưu và đưa từng URL cho {@code consumer} — dùng
     * để dựng lại bộ lọc Bloom khi tiếp tục một phiên crawl dang dở.
     *
     * @return số URL đã đọc; 0 nếu đang tắt hoặc tệp chưa tồn tại
     */
    public long replay(Consumer<String> consumer) {
        if (path == null || !Files.exists(path)) {
            return 0;
        }
        long count = 0;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    consumer.accept(line.trim());
                    count++;
                }
            }
        } catch (IOException e) {
            log.warn("Không đọc lại được {}: {}", path, e.getMessage());
        }
        return count;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    log.warn("Không đóng được {}: {}", path, e.getMessage());
                } finally {
                    writer = null;
                }
            }
        }
    }
}
