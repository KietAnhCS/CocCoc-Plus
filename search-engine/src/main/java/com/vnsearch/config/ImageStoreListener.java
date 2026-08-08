package com.vnsearch.config;

import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.modular.ImageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Đổ ảnh vào {@link ImageStore} để phục vụ {@code GET /api/images}.
 *
 * <h2>Vì sao đây là một lớp RIÊNG, không phải một dòng trong CrawlKafkaListeners</h2>
 *
 * <p>Hai ràng buộc gặp nhau ở đây, và bỏ qua một trong hai thì tính năng hỏng
 * theo kiểu <b>chỉ mất một phần dữ liệu</b> — loại hỏng khó thấy nhất vì giao
 * diện vẫn có ảnh để hiện.
 *
 * <h3>1. Phải là consumer group RIÊNG</h3>
 *
 * <p>{@code CrawlAnalyticsService} cũng đọc topic ảnh, ở group
 * {@code vnsearch-analytics}. Nếu kho ảnh dùng chung group đó thì hai bên
 * <b>chia nhau</b> thông điệp thay vì mỗi bên nhận đủ — Kafka giao mỗi phân
 * hoạch cho đúng một consumer trong một group.
 *
 * <p>Group riêng thì cả hai cùng nhận trọn luồng. Đó chính là cơ chế phát tán
 * một-tới-nhiều mà cả kiến trúc này dựa vào.
 *
 * <h3>2. Chỉ được chạy ở MỘT tiến trình</h3>
 *
 * <p>{@link ImageStore} nằm <b>trong bộ nhớ tiến trình</b>. Ở chế độ phân tán,
 * backend và crawler-worker đều đặt {@code app.crawler.bus=kafka}, nên nếu
 * listener này chạy ở cả hai thì chúng lại vào chung một group và chia đôi
 * luồng — mỗi tiến trình giữ một nửa số ảnh, còn API thì chỉ đọc được nửa của
 * backend.
 *
 * <p>Triệu chứng: lưới ảnh vẫn hiện, chỉ là thiếu một nửa. Không có lỗi nào,
 * không có cảnh báo nào.
 *
 * <p>Nên listener này bị chặn bởi {@code app.crawler.role}:
 *
 * <table border="1">
 *   <caption>Vai trò của tiến trình</caption>
 *   <tr><th>{@code app.crawler.role}</th><th>Tiến trình</th><th>Có giữ kho ảnh?</th></tr>
 *   <tr><td>{@code api} (mặc định)</td><td>backend — phục vụ truy vấn</td><td><b>Có</b></td></tr>
 *   <tr><td>{@code worker}</td><td>crawler-worker — tiêu thụ Kafka</td><td>Không</td></tr>
 * </table>
 *
 * <p><b>Giới hạn phải nêu rõ:</b> vì kho nằm trong bộ nhớ, chạy <i>nhiều bản
 * sao backend</i> sẽ lại chia luồng và mỗi bản chỉ có một phần ảnh. Lời giải
 * đúng ở quy mô đó là đưa kho ảnh xuống PostgreSQL — cùng bước mà
 * {@code UrlSeenFilter} sẽ phải đi khi cần bộ lọc lưu bền. Ở quy mô đồ án, một
 * bản sao backend là đủ và giới hạn này được ghi ra đây thay vì giấu đi.
 */
@Component
@ConditionalOnProperty(name = "app.crawler.bus", havingValue = "kafka")
public class ImageStoreListener {

    private static final Logger log = LoggerFactory.getLogger(ImageStoreListener.class);

    private final ImageStore imageStore;

    public ImageStoreListener(ImageStore imageStore) {
        this.imageStore = imageStore;
        log.info("Kho anh se duoc nap tu Kafka (vai tro: api)");
    }

    /**
     * {@code autoStartup} quyết định listener có chạy hay không, dựa trên vai
     * trò của tiến trình.
     *
     * <p>Dùng {@code autoStartup} thay vì {@code @ConditionalOnProperty} trên
     * chính lớp: bean vẫn được tạo ở cả hai vai trò, nên
     * {@code ImageSearchController} luôn có {@link ImageStore} để tiêm — chỉ
     * là ở vai trò {@code worker} thì không có thông điệp nào chảy vào. Cách
     * này tránh một chuỗi bean vắng mặt lan sang chỗ khác.
     */
    @KafkaListener(
            topics = "${app.crawler.kafka.topic.images}",
            groupId = "${app.crawler.kafka.group.image-store}",
            containerFactory = "crawlListenerContainerFactory",
            autoStartup = "${app.crawler.role.is-api:true}")
    public void onImage(ImageFound image) {
        imageStore.add(image);
    }
}
