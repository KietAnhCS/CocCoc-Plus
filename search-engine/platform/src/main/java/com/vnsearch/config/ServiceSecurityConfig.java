package com.vnsearch.config;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Chuỗi bảo mật MẶC ĐỊNH cho một service nội bộ đứng sau API Gateway.
 *
 * <p>Bốn service dùng nguyên lớp này ({@code search}, {@code crawler},
 * {@code analytics}, {@code history}/{@code downloads}/{@code settings}); chỉ
 * {@code auth-service} tự viết chuỗi riêng, vì nó là <b>nguồn</b> của danh
 * tính chứ không phải bên tiêu thụ danh tính — nó phải chấp nhận
 * {@code Authorization: Bearer} thật, còn mọi service khác thì không bao giờ
 * nhìn tới header đó.
 *
 * <h2>Luật, theo đúng thứ tự Spring Security đánh giá</h2>
 * <pre>
 *   OPTIONS /**                    mở   — preflight CORS không bao giờ mang xác thực
 *   DispatcherType.ERROR           mở   — xem chú thích dài bên dưới
 *   (do service khai)              mở   — PublicEndpoints
 *   /actuator/health, /prometheus  mở   — probe của Kubernetes và của Prometheus
 *   /api/admin/**, /actuator/**    ADMIN
 *   còn lại                        chặn
 * </pre>
 *
 * <h2>Vì sao dòng DispatcherType.ERROR là bắt buộc</h2>
 *
 * <p>Khi một người ĐÃ ĐĂNG NHẬP gọi endpoint không đủ quyền, Spring Security
 * ném {@code AccessDeniedException} và trả 403. Spring Boot sau đó FORWARD nội
 * bộ tới {@code /error} để dựng thân phản hồi — và lần forward đó đi qua chuỗi
 * filter một lần nữa, lúc này {@code SecurityContext} ĐÃ BỊ XOÁ.
 * {@code /error} không nằm trong danh sách nào nên rơi vào {@code denyAll()}
 * và thành 401, thay thế mã 403 ban đầu.
 *
 * <p>Hậu quả không chỉ là sai mã trạng thái: giao diện thấy 401 sẽ đẩy người
 * dùng về màn hình đăng nhập, họ đăng nhập lại thành công, rồi lại bị đẩy về —
 * một vòng lặp không lối thoát cho đúng những người đã đăng nhập nhưng không
 * đủ quyền.
 *
 * <p>Lỗi này CHỈ lộ ra khi chạy thật: MockMvc mặc định không thực hiện lần gửi
 * ERROR, nên bài kiểm thử tích hợp vẫn thấy 403 và vẫn xanh.
 *
 * <h2>Vì sao vẫn giữ khoá API bên cạnh danh tính từ Gateway</h2>
 *
 * <pre>
 *   Ai gọi        Cơ chế                    Đặc điểm
 *   ───────────   ───────────────────────   ──────────────────────────────
 *   con người     Gateway + X-VnSearch-*    có danh tính, thu hồi được,
 *                                           hết hạn sau 12 giờ
 *   công cụ       X-API-Key (thẳng vào      không danh tính, không hết hạn,
 *                 service, không qua        đổi bằng cách khởi động lại
 *                 Gateway)
 * </pre>
 *
 * <p>Khoá tĩnh là thứ duy nhất dùng được ở những nơi không có ai ngồi đăng
 * nhập: script triển khai, job cron, và — quan trọng nhất — <b>lối vào dự
 * phòng khi Gateway hoặc auth-service hỏng</b>. Một hệ thống mà cách duy nhất
 * để vào là đi qua Gateway, và Gateway vừa hỏng, là một hệ thống tự khoá mình
 * ra ngoài.
 */
@Configuration
@EnableWebSecurity
public class ServiceSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceSecurityConfig.class);

    /** Độ dài tối thiểu chấp nhận được cho một bí mật dùng chung. */
    private static final int MIN_SECRET_LENGTH = 16;

    @Value("${app.security.admin-api-key:}")
    private String adminApiKey;

    @Value("${app.security.gateway-secret:}")
    private String gatewaySecret;

    /**
     * Có service nào <b>bắt buộc</b> phải có khoá quản trị không.
     *
     * <p>{@code true} ở crawler-service và analytics-service: chúng có
     * {@code /api/admin/**} điều khiển crawler và đọc số liệu, nên chạy mà
     * không có khoá nghĩa là các endpoint đó không còn lối vào dự phòng nào
     * được bảo vệ. {@code false} ở history/downloads/settings: chúng không có
     * endpoint quản trị nào, và bắt người triển khai sinh thêm một khoá không
     * dùng tới chỉ tạo thêm một bí mật nữa để rò rỉ.
     */
    @Value("${app.security.require-admin-api-key:false}")
    private boolean requireAdminApiKey;

    /**
     * Kiểm tra bí mật NGAY lúc khởi động, trước khi nhận request đầu tiên.
     *
     * <p>Thiếu thì ứng dụng <b>không khởi động</b>. Lựa chọn này có chủ ý:
     * phương án còn lại — sinh một bí mật ngẫu nhiên rồi in ra log — nghe thân
     * thiện hơn nhưng tạo ra một hệ thống <i>có vẻ</i> đang chạy bình thường
     * trong khi Gateway và service không bao giờ khớp bí mật, và mọi request
     * có danh tính đều trả 401 mà không ai hiểu vì sao. Hỏng to còn hơn hỏng
     * âm thầm.
     */
    private String require(String value, String propertyName, String envName, String why) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu " + propertyName + " (biến môi trường " + envName + "). " + why
                            + " Sinh khoá: openssl rand -hex 32");
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(propertyName + " quá ngắn (" + value.length()
                    + " ký tự, tối thiểu " + MIN_SECRET_LENGTH + ").");
        }
        return value;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain serviceFilterChain(HttpSecurity http,
                                                  ObjectProvider<PublicEndpoints> publicEndpoints)
            throws Exception {

        String secret = require(gatewaySecret, "app.security.gateway-secret",
                "GATEWAY_SHARED_SECRET",
                "Không có nó, service không phân biệt được danh tính thật do Gateway"
                        + " truyền xuống với danh tính do bất kỳ ai tự khai.");

        List<RequestMatcher> publicMatchers = publicEndpoints.stream()
                .flatMap(endpoints -> endpoints.matchers().stream())
                .toList();

        http
                // Không có cookie phiên nào để giả mạo: mọi thứ đi bằng header,
                // và trình duyệt không tự đính header vào request chéo trang.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    if (!publicMatchers.isEmpty()) {
                        auth.requestMatchers(publicMatchers.toArray(new RequestMatcher[0]))
                                .permitAll();
                    }
                    auth.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                            .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                            .anyRequest().denyAll();
                })
                .addFilterBefore(new GatewayIdentityFilter(secret),
                        UsernamePasswordAuthenticationFilter.class)
                // Trả 401 trần thay vì chuyển hướng tới trang đăng nhập — đây
                // là API, không có trang đăng nhập nào để chuyển hướng tới.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        if (requireAdminApiKey || !adminApiKey.isBlank()) {
            String key = require(adminApiKey, "app.security.admin-api-key", "ADMIN_API_KEY",
                    "Các endpoint /api/admin/** của service này điều khiển crawler"
                            + " hoặc phơi số liệu vận hành.");
            log.info("Bảo vệ /api/admin/** bằng API key ({} ký tự) trong header {}",
                    key.length(), ApiKeyAuthFilter.HEADER);
            // ĐẶT SAU GatewayIdentityFilter: một request mang cả hai thì phiên
            // CÓ DANH TÍNH thắng, vì nó ghi lại được ai đã gọi. Cả hai filter
            // đều chỉ hành động khi header của mình có mặt nên chúng không
            // giẫm lên nhau.
            http.addFilterAfter(new ApiKeyAuthFilter(key), GatewayIdentityFilter.class);
        }

        return http.build();
    }

    /**
     * Giới hạn tần suất, đặt TRƯỚC chuỗi filter của Spring Security.
     *
     * <p>Đặt trước là có chủ ý: một trận request không hợp lệ phải bị chặn
     * <b>trước</b> khi tốn chi phí phân giải xác thực. Đăng ký qua
     * {@link FilterRegistrationBean} thay vì {@code @Component} để không bị
     * Spring Boot tự động gắn vào chuỗi filter servlet <i>hai lần</i>.
     *
     * <p><b>Ở kiến trúc nhiều tiến trình, đây là hàng rào thứ HAI.</b> Hàng
     * rào thứ nhất nằm ở Gateway và mới là hàng rào đếm đúng — nó thấy toàn bộ
     * lưu lượng của một địa chỉ, còn mỗi service chỉ thấy phần lưu lượng đi
     * qua nó. Giữ lại hàng rào ở đây vì service vẫn gọi thẳng được (xem khoá
     * API ở trên), và một lối vào không có giới hạn tần suất là một lối vào
     * dùng để làm quá tải hệ thống.
     */
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
