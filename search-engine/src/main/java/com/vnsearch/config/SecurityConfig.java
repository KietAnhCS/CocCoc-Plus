package com.vnsearch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Phan quyen theo duong dan cho toan bo REST API.
 *
 * <pre>
 *   CONG KHAI                            CAN X-API-Key
 *   ─────────────────────────────        ─────────────────────────────
 *   GET  /api/search                     POST /api/admin/crawl
 *   GET  /api/suggest                    POST /api/admin/reindex
 *   GET  /api/health                     GET  /api/admin/stats
 *   GET  /actuator/health                GET  /api/admin/crawl/{id}/status
 *   GET  /actuator/prometheus            GET  /actuator/**  (con lai)
 * </pre>
 *
 * <p><b>Vi sao phai them {@code /api/health} rieng.</b> Truoc day healthcheck
 * cua {@code docker-compose.yml} goi {@code /api/admin/stats}. Khoa duong dan
 * admin lai ma khong tach mot endpoint suc khoe cong khai se lam container bi
 * danh dau <i>unhealthy</i> ngay lap tuc, roi {@code restart: unless-stopped}
 * khoi dong lai vo han. Day la mot loi ma phep sua bao mat rat de keo theo.
 *
 * <p><b>Vi sao {@code /actuator/prometheus} cong khai.</b> Bo thu thap so lieu
 * (Prometheus) khong gui duoc header tuy y trong cau hinh mac dinh, va endpoint
 * nay chi phoi bay so lieu tong hop, khong co du lieu nguoi dung. Trong mot
 * trien khai that, no nen duoc chan o tang mang (chi cho mang noi bo goi) chu
 * khong phoi ra Internet — day la ranh gioi ma ung dung khong tu dat duoc.
 *
 * <p><b>CSRF duoc tat co y do.</b> Day la API khong trang thai, xac thuc bang
 * header chu khong bang cookie. Tan cong CSRF dua tren viec trinh duyet <i>tu
 * dong</i> dinh kem thong tin xac thuc; header {@code X-API-Key} thi khong bao
 * gio duoc dinh kem tu dong, nen khong co gi de gia mao.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Do dai toi thieu chap nhan duoc cho khoa quan tri. */
    private static final int MIN_KEY_LENGTH = 16;

    @Value("${app.security.admin-api-key:}")
    private String adminApiKey;

    /**
     * Kiem tra khoa NGAY luc khoi dong, truoc khi nhan request dau tien.
     *
     * <p>Khong co khoa thi ung dung <b>khong khoi dong</b>. Lua chon nay co chu
     * y: phuong an con lai — sinh mot khoa ngau nhien roi in ra log — nghe than
     * thien hon nhung tao ra mot he thong <i>co ve</i> dang chay binh thuong
     * trong khi khong ai biet khoa la gi, va lan trien khai sau lai sinh khoa
     * khac. Hong to con hon hong am tham.
     */
    private String requireAdminApiKey() {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            throw new IllegalStateException(
                    "Thieu app.security.admin-api-key (bien moi truong ADMIN_API_KEY). "
                            + "Cac endpoint /api/admin/** dieu khien crawler va co the tai URL tuy y, "
                            + "nen KHONG duoc phep chay ma khong co khoa. "
                            + "Sinh khoa: openssl rand -hex 32");
        }
        if (adminApiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "app.security.admin-api-key qua ngan (" + adminApiKey.length()
                            + " ky tu, toi thieu " + MIN_KEY_LENGTH + ").");
        }
        return adminApiKey;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        String key = requireAdminApiKey();
        log.info("Bao ve /api/admin/** bang API key ({} ky tu) trong header {}",
                key.length(), ApiKeyAuthFilter.HEADER);

        http
                .csrf(csrf -> csrf.disable())          // xem Javadoc lop
                .cors(cors -> {})                       // dung cau hinh cua CorsConfig
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Preflight CORS khong bao gio mang header xac thuc.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/search", "/api/suggest", "/api/health").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(new ApiKeyAuthFilter(key),
                        UsernamePasswordAuthenticationFilter.class)
                // Tra 401 tran thay vi chuyen huong toi trang dang nhap — day la
                // API, khong co trang dang nhap nao de chuyen huong toi.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /**
     * Gioi han tan suat, dat TRUOC chuoi filter cua Spring Security.
     *
     * <p>Dat truoc la co chu y: mot tran request khong hop le phai bi chan
     * <b>truoc</b> khi ton chi phi phan giai xac thuc. Dang ky qua
     * {@link FilterRegistrationBean} thay vi {@code @Component} de khong bi
     * Spring Boot tu dong gan vao chuoi filter servlet <i>hai lan</i>.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            @Value("${app.security.rate-limit.requests-per-minute:120}") int requestsPerMinute,
            @Value("${app.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.security.trust-proxy:false}") boolean trustProxy) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(requestsPerMinute, enabled, trustProxy));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Integer.MIN_VALUE); // truoc moi filter khac
        return registration;
    }
}
