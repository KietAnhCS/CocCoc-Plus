package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import com.vnsearch.crawler.config.KafkaCrawlConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * search-service — truy vấn, gợi ý, tìm ảnh, bảng tin.
 *
 * <h2>Vì sao {@code scanBasePackages} liệt kê tường minh</h2>
 *
 * <p>Mặc định, {@code @SpringBootApplication} quét cả gói {@code com.vnsearch},
 * và như vậy nó nạp luôn mọi bean nằm trong {@code vnsearch-core}: kể cả
 * {@code CrawlJobManager} — bộ điều khiển crawl, thứ thuộc về crawler-service.
 * Ở một service mà bộ nhớ là tài nguyên khan hiếm nhất, mỗi bean thừa là một
 * khoản heap bị lấy khỏi chỉ mục.
 *
 * <p>Gói {@code com.vnsearch.service} có mặt vì {@code SearchEngineFacade},
 * {@code SuggestionService}, {@code IndexBuilder} nằm ở đó — cùng gói với
 * {@code CrawlJobManager}. Cái sau vì thế VẪN bị nạp; đó là món nợ còn lại của
 * việc tách một khối mã cũ, và cách trả nó là dời {@code CrawlJobManager} sang
 * {@code com.vnsearch.crawler.job}. Ghi ra đây thay vì để im, vì một khoản nợ
 * không ai ghi lại là một khoản nợ không ai trả.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",      // platform + MetricsConfig, ImageStoreListener/Preloader
        "com.vnsearch.controller",  // Search, Suggest, ImageSearch, Feed, Health
        "com.vnsearch.soap",        // cửa SOAP
        "com.vnsearch.service",     // SearchEngineFacade, SuggestionService, EngineBeansConfig
        "com.vnsearch.ranking"      // ScorerFactory
})
/*
 * KafkaCrawlConfig nằm ở com.vnsearch.crawler.config — KHÔNG được quét, vì quét
 * cả gói `crawler` sẽ kéo theo mọi thứ của crawler-service. Nhập tường minh
 * đúng một lớp: search-service cần nó vì ImageStoreListener tiêu thụ topic ảnh
 * để phục vụ GET /api/images.
 *
 * Bản thân lớp đó nằm sau @ConditionalOnProperty(app.crawler.bus=kafka), nên ở
 * chế độ mặc định (`memory`) nó không tạo bean nào cả.
 */
@Import(KafkaCrawlConfig.class)
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }

    /**
     * Những endpoint KHÔNG cần đăng nhập.
     *
     * <p>Tìm kiếm là chức năng công khai của một máy tìm kiếm — bắt đăng nhập
     * mới tra được là biến nó thành thứ khác. Nhưng danh sách này phải ngắn và
     * mỗi dòng phải trả lời được câu "một người hoàn toàn lạ gọi nó thì lấy
     * được gì".
     *
     * <p>{@code /ws/**} công khai vì cửa SOAP phục vụ đúng những truy vấn mà
     * {@code /api/search} đã mở; đóng một cửa và mở cửa kia cho cùng một dữ
     * liệu chỉ tạo cảm giác an toàn.
     */
    @Bean
    public PublicEndpoints searchPublicEndpoints() {
        return () -> List.of(
                PublicEndpoints.method(HttpMethod.GET.name(), "/api/search"),
                PublicEndpoints.method(HttpMethod.GET.name(), "/api/suggest"),
                PublicEndpoints.method(HttpMethod.GET.name(), "/api/images"),
                PublicEndpoints.method(HttpMethod.GET.name(), "/api/feed"),
                PublicEndpoints.method(HttpMethod.GET.name(), "/api/health"),
                (RequestMatcher) new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/ws/**"),
                new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/v3/api-docs/**"),
                new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/swagger-ui/**"),
                new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/swagger-ui.html"));
    }
}
