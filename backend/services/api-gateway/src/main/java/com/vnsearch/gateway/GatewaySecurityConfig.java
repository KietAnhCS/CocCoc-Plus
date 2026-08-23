package com.vnsearch.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Bảo mật tại biên: CORS, xác thực JWT, và luật phân quyền theo tiền tố.
 *
 * <h2>Vì sao Gateway kiểm JWT khi các service phía sau cũng kiểm</h2>
 *
 * <p>Nghe như làm hai lần. Nó là hai lần, và cả hai đều cần:
 *
 * <ul>
 *   <li><b>Ở Gateway</b> — để một token hỏng bị chặn <i>trước</i> khi tốn một
 *       chặng mạng nội bộ và một lượt xử lý ở service. Với một trận request
 *       giả mạo, khác biệt này là khác biệt giữa "Gateway bận" và "cả tám
 *       service bận".</li>
 *   <li><b>Ở service</b> — vì trong mạng container, mọi service gọi thẳng được
 *       tới nhau, không đi qua Gateway. Một service tin rằng "mọi request tới
 *       đây đều đã qua cửa" là một service có thể bị gọi thẳng.</li>
 * </ul>
 *
 * <p>Đây chính là <i>defense in depth</i>: hai lớp độc lập, không lớp nào giả
 * định lớp kia còn nguyên.
 *
 * <h2>Vì sao CORS chỉ khai ở đây</h2>
 *
 * <p>Trình duyệt chỉ nói chuyện với Gateway; nó không biết bảy service phía
 * sau tồn tại. Khai CORS ở tám nơi là tám danh sách origin phải giữ đồng bộ,
 * và chỗ bị quên sẽ hỏng đúng vào ngày ai đó gọi thẳng một service để gỡ lỗi.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                              CorsConfigurationSource corsSource) {
        return http
                // Không có cookie phiên nào để giả mạo: token đi trong header
                // Authorization, và trình duyệt không tự đính header đó vào
                // request chéo trang.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsSource))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                .authorizeExchange(exchange -> exchange
                        // Preflight CORS không bao giờ mang header xác thực.
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Cửa để LẤY token. Đòi token ở đây là bài toán con gà
                        // quả trứng. auth-service tự bảo vệ chúng bằng khoá
                        // tạm sau nhiều lần sai + giới hạn tần suất chặt hơn.
                        .pathMatchers("/oauth2/**", "/.well-known/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register",
                                "/api/auth/login", "/api/auth/logout",
                                "/api/auth/refresh").permitAll()

                        // Máy tìm kiếm là dịch vụ công khai. Bắt đăng nhập mới
                        // tra được là biến nó thành thứ khác.
                        .pathMatchers(HttpMethod.GET, "/api/search", "/api/suggest",
                                "/api/images", "/api/feed", "/api/health").permitAll()
                        // Cửa SOAP phục vụ đúng những truy vấn đó.
                        .pathMatchers("/ws/**").permitAll()
                        // Chiều GHI số liệu công khai có chủ ý — xem
                        // AnalyticsServiceApplication. Chỉ POST, không phải cả
                        // đường dẫn.
                        .pathMatchers(HttpMethod.POST, "/api/events").permitAll()
                        // Bóng đá là dữ liệu công khai, không gắn với ai.
                        .pathMatchers(HttpMethod.GET, "/api/football/**").permitAll()

                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**").permitAll()

                        .pathMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")

                        // MẶC ĐỊNH ĐÓNG. Một tuyến mới quên khai ở đây sẽ trả
                        // 401 ngay lần gọi đầu — hỏng to, thấy ngay, sửa một
                        // dòng. Với mặc định mở thì nó lặng lẽ phơi dữ liệu ra
                        // và không bài test nào phát hiện được, vì nó vẫn trả
                        // về đúng nội dung.
                        .anyExchange().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(new JwtRoleConverter()))))

                // Trả 401 trần thay vì chuyển hướng tới trang đăng nhập — đây
                // là API, không có trang đăng nhập nào để chuyển hướng tới.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /**
     * Danh sách origin lấy từ cấu hình, KHÔNG bao giờ là {@code *}.
     *
     * <p>Với {@code allowCredentials(true)}, trình duyệt từ chối thẳng dấu sao.
     * Và ngay cả khi không có credentials, dấu sao mở API cho <i>mọi</i> trang
     * web bất kỳ đọc dữ liệu thay cho người dùng đang đăng nhập — đúng thứ CORS
     * sinh ra để ngăn.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split("\\s*,\\s*")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key",
                "X-Idempotency-Key"));
        // Giao diện đọc hai header này để biết còn bao nhiêu lượt gọi.
        config.setExposedHeaders(List.of("X-RateLimit-Remaining", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Dịch claim {@code roles} thành {@code ROLE_*}.
     *
     * <p>Bản sao của {@code com.vnsearch.config.JwtRoleConverter} trong
     * {@code vnsearch-platform}. Trùng lặp CÓ CHỦ Ý: module này chạy trên
     * WebFlux và không dùng được {@code vnsearch-platform} (toàn bộ là servlet
     * filter). Hai bản phải giữ giống nhau — lệch nhau nghĩa là Gateway và
     * service hiểu khác nhau về vai trò của cùng một token, và hậu quả là
     * người dùng qua được cửa ngoài rồi bị từ chối ở trong.
     */
    static final class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                for (String role : roles) {
                    if (role != null && !role.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim()));
                    }
                }
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        }
    }
}
