package com.vnsearch.config;

import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.modular.ImageStorage;
import com.vnsearch.crawler.modular.ImageStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Nạp kho ảnh từ đĩa vào {@link ImageStore} lúc backend khởi động.
 *
 * <h2>Lỗ hổng mà lớp này bịt</h2>
 *
 * <p>Trước đây kho ảnh chỉ được đổ đầy bởi <i>một phiên crawl đang chạy</i> —
 * qua {@code CrawlKafkaListeners} ở chế độ Kafka, hoặc qua bus in-process ở chế
 * độ một tiến trình. Không có đường nào nạp lại từ đĩa, vì cũng chẳng có tệp
 * nào để nạp.
 *
 * <p>Hệ quả là một sự bất đối xứng khó chịu giữa hai tab của cùng một giao
 * diện:
 *
 * <table border="1">
 *   <caption>Sau khi khởi động lại backend, chưa crawl lại</caption>
 *   <tr><th>Tab</th><th>Nguồn</th><th>Còn dữ liệu?</th></tr>
 *   <tr><td>Tất cả</td><td>{@code index.json} / corpus trên đĩa</td><td><b>Có</b></td></tr>
 *   <tr><td>Hình ảnh</td><td>{@link ImageStore} trong RAM</td><td>Không — trống trơn</td></tr>
 * </table>
 *
 * <p>Người dùng gõ một truy vấn, tab "Tất cả" trả về 40 kết quả, tab "Hình ảnh"
 * báo "không tìm thấy ảnh nào". Không có lỗi, không có log, và lời khuyên mà
 * giao diện đưa ra ("hãy chạy một phiên crawl mới") lại <i>đúng về hành vi
 * nhưng sai về nguyên nhân</i> — ảnh đã từng được crawl, chỉ là không ai lưu.
 *
 * <h2>Vì sao là lớp riêng, không nhét vào SearchEngineFacade</h2>
 *
 * <p>Facade nạp corpus và dựng chỉ mục — một việc bắt buộc phải xong thì
 * ứng dụng mới trả lời được truy vấn. Nạp ảnh là việc <b>có thì tốt</b>: hỏng
 * nó thì tab "Hình ảnh" nghèo đi, còn mọi thứ khác vẫn chạy. Trộn hai mức quan
 * trọng đó vào một hàm nghĩa là một tệp ảnh hỏng có thể kéo sập cả phần tìm
 * kiếm văn bản.
 *
 * <p>Nên lớp này đứng riêng và nuốt mọi lỗi ({@link ImageStorage#loadQuietly}).
 *
 * <h2>Nạp GỘP, không phải nạp ĐÈ</h2>
 *
 * <p>Dùng {@link ImageStore#addAll} nên nếu một phiên crawl đã kịp đổ ảnh vào
 * kho trước khi bean này chạy, ảnh cũ trên đĩa chỉ <i>bổ sung</i> chứ không xoá
 * gì. Khử trùng theo {@code imageUrl} nằm sẵn trong {@code add}, nên nạp hai
 * lần cũng không sinh bản ghi trùng.
 */
@Component
public class ImageStorePreloader {

    private static final Logger log = LoggerFactory.getLogger(ImageStorePreloader.class);

    /**
     * Đường dẫn corpus — tệp ảnh suy ra từ nó qua {@link ImageStorage#pathFor}.
     *
     * <p>Dùng chung khoá cấu hình với {@code SearchEngineFacade} chứ không thêm
     * một khoá {@code app.images.data-path} riêng. Hai khoá độc lập thì sẽ có
     * ngày chúng trỏ lệch nhau, và khi đó kho ảnh nạp từ phiên crawl này còn
     * chỉ mục nạp từ phiên khác — số liệu hai tab không khớp mà không ai biết
     * vì sao.
     */
    @Value("${app.crawler.data-path}")
    private String crawledDataPath;

    private final ImageStore imageStore;

    public ImageStorePreloader(ImageStore imageStore) {
        this.imageStore = imageStore;
    }

    @PostConstruct
    public void preload() {
        String path = ImageStorage.pathFor(crawledDataPath);
        long start = System.currentTimeMillis();

        List<ImageFound> images = ImageStorage.loadQuietly(path);
        if (images.isEmpty()) {
            // Không phải lỗi: repo mới clone, hoặc phiên crawl gần nhất chạy
            // bằng bản mã cũ chưa ghi ảnh. Nói rõ tệp nào bị thiếu để người đọc
            // log không phải đoán.
            log.info("Chua co kho anh tai {} — tab Hinh anh se trong cho toi phien crawl tiep theo",
                    path);
            return;
        }

        int added = imageStore.addAll(images);
        log.info("Da nap kho anh tu {}: {} anh tren {} trang ({} ms)",
                path, added, imageStore.pageCount(), System.currentTimeMillis() - start);
    }
}
