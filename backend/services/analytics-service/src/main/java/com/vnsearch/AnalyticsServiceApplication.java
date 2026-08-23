package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.List;

/**
 * analytics-service — thu số liệu sử dụng và dựng bảng điều khiển quản trị.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",      // platform
        "com.vnsearch.controller",  // EventController, AdminAnalyticsController
        "com.vnsearch.dashboard",   // AdminDashboardAssembler
        "com.vnsearch.analytics"    // UsageAnalyticsService
})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }

    /**
     * Chiều GHI số liệu sử dụng là <b>công khai có chủ ý</b>.
     *
     * <p>Mọi người dùng đều phải báo được hành vi của mình, kể cả khách chưa
     * đăng nhập; bật xác thực ở đây đồng nghĩa với việc không còn số liệu nào
     * về phần lớn lưu lượng. Chiều ĐỌC ({@code /api/admin/analytics}) vẫn cần
     * vai trò ADMIN.
     *
     * <p>Ràng buộc theo PHƯƠNG THỨC, không theo đường dẫn: chỉ {@code POST}
     * được mở. Một {@code GET /api/events} thêm vào sau này — nếu có ai thêm —
     * sẽ KHÔNG tự động thừa kế quyền công khai này, và đó chính là điều mong
     * muốn: đọc lại luồng sự kiện là việc của quản trị viên.
     */
    @Bean
    public PublicEndpoints analyticsPublicEndpoints() {
        return () -> List.of(
                PublicEndpoints.method(HttpMethod.POST.name(), "/api/events"),
                new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/v3/api-docs/**"),
                new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/swagger-ui/**"),
                new org.springframework.security.web.util.matcher
                        .AntPathRequestMatcher("/swagger-ui.html"));
    }

    /**
     * Thời gian chờ cho MỌI lượt gọi service khác.
     *
     * <p><b>Đặt ở đây, một lần, thay vì ở từng chỗ gọi.</b> Mặc định của
     * {@code SimpleClientHttpRequestFactory} là <i>chờ vô hạn</i>: một
     * crawler-service treo sẽ giữ luôn luồng xử lý của bảng điều khiển, và khi
     * đủ nhiều người mở bảng thì bể luồng của service này cạn — một service
     * hỏng kéo theo một service khác hỏng, đúng định nghĩa sự cố dây chuyền.
     *
     * <p>Ba giây là trần cho một khối dữ liệu chỉ để hiển thị. Vượt quá thì
     * hiện khối trống còn hữu ích hơn bắt người xem chờ.
     */
    @Bean
    public RestClientCustomizer thoiGianChoNganGon() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(3));
            builder.requestFactory(factory);
        };
    }
}
