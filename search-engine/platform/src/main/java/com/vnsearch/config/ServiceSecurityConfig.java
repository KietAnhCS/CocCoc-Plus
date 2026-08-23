package com.vnsearch.config;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Chuỗi bảo mật MẶC ĐỊNH của một service nội bộ — một <b>OAuth2 Resource
 * Server</b> tự kiểm chữ ký JWT.
 *
 * <p>Sáu service dùng nguyên lớp này; chỉ {@code auth-service} tự viết chuỗi
 * riêng, vì nó là <b>nguồn phát</b> token chứ không phải bên tiêu thụ token.
 *
 * <h2>Vì sao JWT chứ không phải "Gateway đảm bảo hộ"</h2>
 *
 * <p>Phương án đầu tiên viết cho hệ này là: Gateway tra phiên một lần rồi dán
 * kết quả vào header {@code X-VnSearch-User}, các service phía sau tin header
 * đó khi request kèm một bí mật dùng chung. Nó chạy được, nhưng có ba khuyết
 * điểm mà một hệ thống ngân hàng không chấp nhận:
 *
 * <ol>
 *   <li><b>Một bí mật, tám nơi giữ.</b> Rò rỉ ở bất kỳ đâu trong tám tiến
 *       trình là toàn quyền giả mạo mọi danh tính. Đây đúng là
 *       <i>A02:2021 — Cryptographic Failures</i> và
 *       <i>A07 — Identification and Authentication Failures</i>.</li>
 *   <li><b>Không chứng minh được nguồn gốc.</b> Header do Gateway viết ra thì
 *       không mang chữ ký của ai; nhật ký kiểm toán chỉ nói "service tin rằng
 *       người này là X", không nói được <i>vì sao</i> nó tin.</li>
 *   <li><b>Mọi lối vào phải đi qua Gateway.</b> Một job nội bộ muốn gọi
 *       history-service thì hoặc phải vòng ra Gateway, hoặc phải cầm bí mật —
 *       tức là leo lên đặc quyền cao nhất chỉ để làm một việc nhỏ.</li>
 * </ol>
 *
 * <p>Với JWT ký RS256, mỗi service tự lấy <b>khoá công khai</b> từ JWKS của
 * auth-service và tự xác minh. Không service nào giữ khoá ký; rò rỉ một service
 * không cho phép phát hành token. Nhật ký kiểm toán ghi được cả {@code jti} —
 * định danh duy nhất của chính token đã dùng.
 *
 * <h2>Cái giá của JWT: thu hồi</h2>
 *
 * <p>Một token đã phát ra thì sống tới lúc hết hạn, kể cả khi tài khoản vừa bị
 * khoá. Ba biện pháp trả cái giá đó, dùng đồng thời:
 *
 * <pre>
 *   access token sống 15 phút     cửa sổ thiệt hại tối đa là 15 phút
 *   refresh token xoay vòng       dùng lại một refresh cũ = phát hiện đánh cắp
 *   denylist theo jti (Redis)     thu hồi tức thì khi cần, TTL bằng hạn token
 * </pre>
 *
 * <p>Denylist đặt ở Gateway chứ không ở đây — xem {@code TokenDenylistFilter}
 * trong module gateway. Đặt ở từng service nghĩa là tám tiến trình cùng hỏi
 * Redis cho mỗi request, và mỗi tiến trình là một chỗ có thể quên hỏi.
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
 * <h2>Vì sao vẫn giữ khoá API bên cạnh JWT</h2>
 *
 * <pre>
 *   Ai gọi        Cơ chế                    Đặc điểm
 *   ───────────   ───────────────────────   ──────────────────────────────
 *   con người     JWT (Bearer)              có danh tính, hết hạn 15 phút,
 *                                           thu hồi được, ghi audit được
 *   công cụ       X-API-Key                 không danh tính, không hết hạn,
 *                                           đổi bằng cách khởi động lại
 * </pre>
 *
 * <p>Khoá tĩnh là thứ duy nhất dùng được ở những nơi không có ai ngồi đăng
 * nhập: script triển khai, job cron, và — quan trọng nhất — <b>lối vào dự
 * phòng khi auth-service hỏng</b>. Một hệ thống mà cách duy nhất để vào là
 * xin token, và nơi cấp token vừa hỏng, là một hệ thống tự khoá mình ra ngoài.
 * Đổi lại, nó chỉ mở đúng {@code /api/admin/**} và mọi lượt dùng đều bị ghi
 * lại kèm cảnh báo.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "app.security.default-chain", havingValue = "true",
        matchIfMissing = true)
public class ServiceSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceSecurityConfig.class);

    /** Độ dài tối thiểu chấp nhận được cho một khoá tĩnh. */
    private static final int MIN_KEY_LENGTH = 16;

    @Value("${app.security.admin-api-key:}")
    private String adminApiKey;

    /**
     * Service này có <b>bắt buộc</b> phải có khoá quản trị không.
     *
     * <p>{@code true} ở crawler-service và analytics-service: chúng có
     * {@code /api/admin/**} điều khiển crawler và đọc số liệu vận hành.
     * {@code false} ở history/downloads/settings: chúng không có endpoint quản
     * trị nào, và bắt người triển khai sinh thêm một khoá không dùng tới chỉ
     * tạo thêm một bí mật nữa để rò rỉ.
     */
    @Value("${app.security.require-admin-api-key:false}")
    private boolean requireAdminApiKey;

    @Bean
    public SecurityFilterChain serviceFilterChain(HttpSecurity http,
                                                  ObjectProvider<PublicEndpoints> publicEndpoints)
            throws Exception {

        List<RequestMatcher> publicMatchers = publicEndpoints.stream()
                .flatMap(endpoints -> endpoints.matchers().stream())
                .toList();

        http
                // Không có cookie phiên nào để giả mạo: token đi trong header
                // Authorization, và trình duyệt không tự đính header đó vào
                // request chéo trang. Tắt CSRF ở một API stateless là đúng;
                // tắt nó ở một ứng dụng dùng cookie thì không.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // A05:2021 — Security Misconfiguration. Bốn header này rẻ và
                // chặn được bốn lớp tấn công khác nhau, nên đặt mặc định cho
                // mọi service thay vì để từng service tự nhớ.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {})
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000L)))

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

                // Kiểm chữ ký JWT bằng khoá công khai lấy từ JWKS của
                // auth-service. Địa chỉ JWKS khai ở
                // spring.security.oauth2.resourceserver.jwt.jwk-set-uri;
                // Spring tự làm bộ nhớ đệm và tự nạp lại khi khoá xoay vòng.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoleConverter())))

                // Trả 401 trần thay vì chuyển hướng tới trang đăng nhập — đây
                // là API, không có trang đăng nhập nào để chuyển hướng tới.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        if (requireAdminApiKey || !adminApiKey.isBlank()) {
            String key = requireAdminApiKey();
            log.info("Bảo vệ /api/admin/** bằng API key ({} ký tự) trong header {}",
                    key.length(), ApiKeyAuthFilter.HEADER);
            http.addFilterBefore(new ApiKeyAuthFilter(key),
                    UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    /**
     * Kiểm tra khoá NGAY lúc khởi động, trước khi nhận request đầu tiên.
     *
     * <p>Không có khoá thì ứng dụng <b>không khởi động</b>. Lựa chọn này có
     * chủ ý: phương án còn lại — sinh một khoá ngẫu nhiên rồi in ra log — nghe
     * thân thiện hơn nhưng tạo ra một hệ thống <i>có vẻ</i> đang chạy bình
     * thường trong khi không ai biết khoá là gì, và lần triển khai sau lại
     * sinh khoá khác. Hỏng to còn hơn hỏng âm thầm.
     */
    private String requireAdminApiKey() {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu app.security.admin-api-key (biến môi trường ADMIN_API_KEY). "
                            + "Các endpoint /api/admin/** của service này điều khiển crawler "
                            + "hoặc phơi số liệu vận hành, nên KHÔNG được phép chạy mà không "
                            + "có khoá. Sinh khoá: openssl rand -hex 32");
        }
        if (adminApiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("app.security.admin-api-key quá ngắn ("
                    + adminApiKey.length() + " ký tự, tối thiểu " + MIN_KEY_LENGTH + ").");
        }
        return adminApiKey;
    }

}
