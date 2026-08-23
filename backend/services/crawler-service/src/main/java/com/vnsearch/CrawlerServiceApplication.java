package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import com.vnsearch.crawler.config.KafkaCrawlConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * crawler-service — điều khiển crawl và chạy các Modular Service.
 *
 * <p><b>Không có endpoint công khai nào.</b> Toàn bộ bề mặt của service này
 * nằm dưới {@code /api/admin/**}, và đó là điều bắt buộc: {@code POST
 * /api/admin/crawl} khiến máy chủ đi tải một URL do người gọi chọn. Một
 * endpoint như thế mà mở công khai là một máy quét cổng miễn phí cho bất kỳ ai
 * — chính là <i>A10:2021 — Server-Side Request Forgery</i>.
 *
 * <p>Danh sách {@link PublicEndpoints} dưới đây vì vậy chỉ chứa Swagger. Nó
 * KHÔNG rỗng chỉ vì tài liệu API cần đọc được; nếu ngay cả điều đó cũng không
 * cần, bean này nên bị xoá hẳn.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",      // platform + CrawlKafkaListeners
        "com.vnsearch.controller",  // AdminController
        "com.vnsearch.service",     // CrawlJobManager, SearchEngineFacade, EngineBeansConfig
        // ScorerFactory sống ở đây. crawler-service cần nó vì lập chỉ mục lại
        // (POST /api/admin/reindex) đi qua SearchEngineFacade, và facade dựng
        // bộ chấm điểm ngay lúc khởi tạo chỉ mục. Thiếu dòng này thì service
        // KHÔNG khởi động được — lỗi thật đã gặp:
        //   NoSuchBeanDefinitionException: com.vnsearch.ranking.ScorerFactory
        "com.vnsearch.ranking"
})
/*
 * KafkaCrawlConfig nhập tường minh thay vì quét cả gói `com.vnsearch.crawler`:
 * quét cả gói sẽ nạp mọi lớp crawler, trong đó có các runner dòng lệnh không
 * bao giờ nên sống trong một tiến trình phục vụ HTTP.
 */
@Import(KafkaCrawlConfig.class)
public class CrawlerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrawlerServiceApplication.class, args);
    }

    @Bean
    public PublicEndpoints crawlerPublicEndpoints() {
        return PublicEndpoints.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
