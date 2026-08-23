package com.vnsearch.config;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Chuỗi bảo mật RIÊNG của auth-service.
 *
 * <p>Đây là service duy nhất không dùng {@link ServiceSecurityConfig} — nó bị
 * tắt bằng {@code app.security.default-chain=false} trong
 * {@code application.properties}. Lý do: auth-service vừa là <b>nguồn phát</b>
 * token vừa là bên <b>tiêu thụ</b> token, và hai vai trò đó cần hai nhóm luật
 * khác nhau trên cùng một tiến trình.
 *
 * <pre>
 *   Nhóm đường dẫn                     Ai vào được      Vì sao
 *   ────────────────────────────────   ──────────────   ─────────────────────────
 *   /oauth2/token, /oauth2/revoke      công khai        cửa duy nhất để LẤY token;
 *                                                       đòi token ở đây là bài
 *                                                       toán con gà quả trứng
 *   /oauth2/jwks, /.well-known/**      công khai        chỉ chứa khoá CÔNG KHAI
 *   POST /api/auth/register, /login    công khai        cửa của người chưa có phiên
 *   POST /api/auth/logout              công khai        xem Javadoc AuthController:
 *                                                       token hết hạn vẫn phải đăng
 *                                                       xuất được
 *   /api/auth/**  (còn lại)            đã đăng nhập     "tôi là ai", đổi mật khẩu
 *   /api/admin/**, /actuator/**        ADMIN            quản trị tài khoản
 *   còn lại                            chặn             mặc định đóng
 * </pre>
 *
 * <h2>Vì sao {@code /oauth2/token} công khai KHÔNG phải là một lỗ hổng</h2>
 *
 * <p>Endpoint này không cấp gì cho người không biết mật khẩu: nó gọi
 * {@code UserService.authenticate}, và lớp đó đã có <b>khoá tạm sau nhiều lần
 * sai</b> cùng <b>so sánh trong thời gian hằng định</b>. Cộng thêm hàng rào tần
 * suất của {@link RateLimitConfig} đứng trước cả chuỗi bảo mật, đây là ba lớp
 * chồng nhau chống dò mật khẩu — đúng yêu cầu của
 * <i>A07:2021 — Identification and Authentication Failures</i>.
 *
 * <p>Điều <b>không</b> được phép làm là mở thêm bất cứ endpoint nào khác vào
 * nhóm công khai này. Mỗi dòng thêm vào đó phải trả lời được câu hỏi "một người
 * hoàn toàn lạ gọi nó thì lấy được gì".
 */
@Configuration
@EnableWebSecurity
public class AuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurityConfig.class);

    private static final int MIN_KEY_LENGTH = 16;

    @Value("${app.security.admin-api-key:}")
    private String adminApiKey;

    @Bean
    public SecurityFilterChain authFilterChain(HttpSecurity http) throws Exception {
        String key = requireAdminApiKey();
        log.info("Bảo vệ /api/admin/** bằng API key ({} ký tự) trong header {}",
                key.length(), ApiKeyAuthFilter.HEADER);

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {})
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000L)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Xem chú thích dài trong ServiceSecurityConfig: thiếu
                        // dòng này, một người ĐÃ đăng nhập nhưng thiếu quyền sẽ
                        // nhận 401 thay vì 403 và bị giao diện đá về màn hình
                        // đăng nhập trong một vòng lặp không lối thoát.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        .requestMatchers("/oauth2/token", "/oauth2/revoke", "/oauth2/jwks",
                                "/.well-known/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register",
                                "/api/auth/login", "/api/auth/logout",
                                "/api/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()

                        // Chỉ cần ĐÃ ĐĂNG NHẬP, không phân biệt vai trò: một
                        // người dùng thường vẫn phải xem được họ là ai và vẫn
                        // phải đổi được mật khẩu của chính mình.
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().denyAll())

                // auth-service cũng kiểm JWT do CHÍNH NÓ phát ra. Nghe vòng vo
                // nhưng đúng: không có ngoại lệ nào cho "token của mình", nên
                // không có nhánh mã nào chỉ chạy ở đây mà không được bảy service
                // kia thử nghiệm cùng.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoleConverter())))

                .addFilterBefore(new ApiKeyAuthFilter(key),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /**
     * Kiểm tra khoá NGAY lúc khởi động, trước khi nhận request đầu tiên.
     *
     * <p>Không có khoá thì ứng dụng <b>không khởi động</b>. Phương án còn lại —
     * sinh một khoá ngẫu nhiên rồi in ra log — nghe thân thiện hơn nhưng tạo ra
     * một hệ thống <i>có vẻ</i> đang chạy bình thường trong khi không ai biết
     * khoá là gì, và lần triển khai sau lại sinh khoá khác. Hỏng to còn hơn
     * hỏng âm thầm.
     */
    private String requireAdminApiKey() {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu app.security.admin-api-key (biến môi trường ADMIN_API_KEY). "
                            + "Các endpoint /api/admin/users/** có thể nâng vai trò của bất kỳ "
                            + "tài khoản nào lên ADMIN, nên KHÔNG được phép chạy mà không có "
                            + "khoá. Sinh khoá: openssl rand -hex 32");
        }
        if (adminApiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("app.security.admin-api-key quá ngắn ("
                    + adminApiKey.length() + " ký tự, tối thiểu " + MIN_KEY_LENGTH + ").");
        }
        return adminApiKey;
    }
}
