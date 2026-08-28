package com.vnsearch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Giới hạn tần suất, đặt TRƯỚC chuỗi filter của Spring Security.
 *
 * <p><b>Vì sao lớp riêng chứ không nằm trong {@link ServiceSecurityConfig}.</b>
 * Lớp kia bị tắt ở auth-service (nơi tự viết chuỗi bảo mật riêng), và nếu bean
 * này nằm chung thì auth-service — <b>đúng service cần giới hạn tần suất nhất
 * trong cả hệ thống</b>, vì nó là nơi người ta dò mật khẩu — sẽ mất hàng rào
 * mà không ai nhận ra. Một biện pháp an ninh biến mất kèm theo một thứ khác bị
 * tắt là loại lỗ hổng khó thấy nhất.
 *
 * <p>Đặt trước chuỗi bảo mật là có chủ ý: một trận request không hợp lệ phải bị
 * chặn <b>trước</b> khi tốn chi phí xác minh chữ ký RSA hoặc băm BCrypt — hai
 * phép tính cố ý đắt, và chính là thứ kẻ tấn công muốn ta phải làm thật nhiều
 * lần.
 *
 * <p>Đăng ký qua {@link FilterRegistrationBean} thay vì {@code @Component} để
 * không bị Spring Boot tự động gắn vào chuỗi filter servlet <i>hai lần</i>.
 *
 * <p><b>Ở kiến trúc nhiều tiến trình, đây là hàng rào thứ HAI.</b> Hàng rào
 * thứ nhất nằm ở Gateway và mới là hàng rào đếm đúng — nó thấy toàn bộ lưu
 * lượng của một địa chỉ, còn mỗi service chỉ thấy phần lưu lượng đi qua nó.
 * Giữ lại hàng rào ở đây vì service vẫn gọi thẳng được, và một lối vào không có
 * giới hạn tần suất là một lối vào dùng để làm quá tải hệ thống.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            @Value("${app.security.rate-limit.requests-per-minute:120}") int requestsPerMinute,
            @Value("${app.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.security.trust-proxy:false}") boolean trustProxy) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(requestsPerMinute, enabled, trustProxy));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Integer.MIN_VALUE); // trước mọi filter khác
        return registration;
    }
}
