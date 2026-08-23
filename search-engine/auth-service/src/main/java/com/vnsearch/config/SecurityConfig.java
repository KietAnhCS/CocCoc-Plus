package com.vnsearch.config;

import com.vnsearch.auth.SessionStore;
import jakarta.servlet.DispatcherType;
import com.vnsearch.auth.TokenAuthFilter;
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
 *   CONG KHAI                    DA DANG NHAP        VAI TRO ADMIN
 *   ───────────────────────      ──────────────      ─────────────────────────
 *   GET  /api/search             GET  /api/auth/me   POST /api/admin/crawl
 *   GET  /api/suggest            POST /api/auth/     POST /api/admin/reindex
 *   GET  /api/health                  logout         GET  /api/admin/stats
 *   GET  /api/images                                 GET  /api/admin/crawl/{id}/status
 *   GET  /api/feed                                   GET  /api/admin/analytics
 *   POST /api/events                                 POST /api/admin/analytics/reset
 *   POST /api/auth/register                          GET  /api/admin/users
 *   POST /api/auth/login                             POST /api/admin/users/{ten}/role
 *   GET  /actuator/health                            GET  /actuator/**  (con lai)
 *   GET  /actuator/prometheus
 * </pre>
 *
 * <p><b>Hai duong xac thuc, mot bang phan quyen.</b> Vai tro ADMIN duoc cap
 * boi MOT trong hai filter: {@link TokenAuthFilter} (nguoi that, dang nhap
 * bang tai khoan/mat khau) hoac {@link ApiKeyAuthFilter} (cong cu, header
 * {@code X-API-Key}). Bang tren khong quan tam vai tro den tu dau — do chinh
 * la diem cua viec phan quyen theo VAI TRO chu khong theo CO CHE dang nhap:
 * them mot cach xac thu nua sau nay khong phai sua mot dong nao trong bang.
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
    public SecurityFilterChain filterChain(HttpSecurity http, SessionStore sessions)
            throws Exception {
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
                        // Lan gui ERROR cua servlet container.
                        //
                        // VI SAO DONG NAY CAN THIET. Khi mot nguoi DA DANG NHAP
                        // goi endpoint khong du quyen, Spring Security nem
                        // AccessDeniedException va tra 403. Spring Boot sau do
                        // FORWARD noi bo toi /error de dung than phan hoi — va
                        // lan forward do di qua chuoi filter mot lan nua, luc
                        // nay SecurityContext DA BI XOA. /error khong nam trong
                        // danh sach nao nen roi vao denyAll() -> 401, va ma 403
                        // ban dau bi thay the.
                        //
                        // Hau qua khong chi la sai ma trang thai: giao dien
                        // thay 401 se day nguoi dung ve man hinh dang nhap, ho
                        // dang nhap lai thanh cong, roi lai bi day ve — mot
                        // vong lap khong loi thoat cho dung nhung nguoi da
                        // dang nhap dung nhung khong du quyen.
                        //
                        // Loi nay CHI lo ra khi chay that: MockMvc mac dinh
                        // khong thuc hien lan gui ERROR, nen bai kiem thu tich
                        // hop van thay 403 va van xanh.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Duong dan CONG KHAI. Them mot endpoint doc du lieu
                        // ma quen dong nay thi no tra 401 — dung mac dinh cua
                        // Spring Security (chan truoc, mo sau), va la ly do
                        // /api/images tra 401 o lan chay dau tien.
                        .requestMatchers("/api/search", "/api/suggest", "/api/health",
                                "/api/images", "/api/feed").permitAll()
                        // Chieu GHI so lieu su dung: cong khai co chu y. Moi
                        // nguoi dung deu phai bao duoc hanh vi, nen bat xac
                        // thuc o day dong nghia voi khong con so lieu nao. Chieu
                        // DOC (/api/admin/analytics) van can vai tro ADMIN — xem
                        // Javadoc cua EventController.
                        //
                        // Rang buoc theo PHUONG THUC, khong phai theo duong dan:
                        // chi POST duoc mo. Mot GET /api/events sau nay (neu co
                        // ai them) se KHONG tu dong thua ke quyen cong khai nay.
                        .requestMatchers(HttpMethod.POST, "/api/events").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Dang ky va dang nhap PHAI cong khai — day la cua duy
                        // nhat de mot nguoi chua co phien buoc vao.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register",
                                "/api/auth/login").permitAll()
                        // DANG XUAT cung cong khai, va day la phep sua sau review:
                        // truoc do no nam trong nhom .authenticated(), nen mot
                        // nguoi co token DA HET HAN bam "Dang xuat" se nhan 401 —
                        // dung luc ho muon don dep phien thi he thong tu choi.
                        // Javadoc cua AuthController.logout noi ro no tra 204 ke
                        // ca khi token khong con hieu luc; luat o day da mau
                        // thuan voi loi hua do.
                        //
                        // Mo ra khong them rui ro nao: handler chi thu hoi dung
                        // token duoc gui len, khong co token thi khong co gi de
                        // thu hoi. Con /api/auth/logout-all VAN can xac thuc vi
                        // no hanh dong tren TAI KHOAN chu khong tren mot token.
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                        // Nhung endpoint nay chi can DA DANG NHAP, khong phan
                        // biet vai tro: mot nguoi dung thuong van phai xem duoc
                        // ho la ai va van phai dang xuat duoc.
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                // TokenAuthFilter dat TRUOC ApiKeyAuthFilter: mot request mang
                // ca hai header thi phien CO DANH TINH thang, vi no ghi lai duoc
                // ai da goi. Ca hai filter deu chi hanh dong khi header cua minh
                // co mat nen chung khong giam len nhau.
                .addFilterBefore(new ApiKeyAuthFilter(key),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new TokenAuthFilter(sessions), ApiKeyAuthFilter.class)
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
