package com.vnsearch.controller;

import com.vnsearch.auth.Role;
import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
import com.vnsearch.oauth.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Đăng ký, đăng nhập, gia hạn, đăng xuất, và "tôi là ai".
 *
 * <p><b>Vì sao {@code /api/auth/**} tách khỏi {@code /api/admin/**}.</b> Hai
 * nhóm trả lời hai câu hỏi khác nhau: nhóm này là <i>cửa vào</i> (ai cũng phải
 * gõ được, kể cả người chưa có tài khoản), nhóm kia là <i>phòng trong</i> (chỉ
 * ADMIN). Gộp chung đường dẫn thì luật phân quyền phải viết theo từng endpoint
 * lẻ thay vì theo tiền tố — và mỗi luật lẻ là một chỗ có thể quên.
 *
 * <p><b>Quan hệ với {@code /oauth2/token}.</b> Lối này là bản gói lại cho ứng
 * dụng VnSearch: cùng một {@link TokenService}, chỉ khác vỏ JSON. Xem Javadoc
 * của {@code OAuth2Controller} về việc vì sao có hai lối vào cho cùng một cơ
 * chế.
 *
 * <p><b>Token đi trong thân phản hồi, không đi trong cookie.</b> Cookie sẽ được
 * trình duyệt <i>tự động</i> đính kèm vào mọi request tới máy chủ, và chính
 * tính tự động đó là thứ làm nên tấn công CSRF — lúc đó phải dựng thêm cả bộ
 * chống CSRF. Token đặt thủ công vào header {@code Authorization} thì không bao
 * giờ tự đi theo, nên không có gì để giả mạo. Đây cũng là lý do
 * {@code AuthSecurityConfig} tắt CSRF một cách có cơ sở chứ không phải cho tiện.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;
    private final TokenService tokens;

    public AuthController(UserService users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /**
     * Thông tin đăng nhập.
     *
     * <p>Chặn độ dài ngay tại đây, TRƯỚC khi chuỗi tới BCrypt: băm là phép tính
     * cố ý chậm (~200 ms), nên một chuỗi khổng lồ gửi lặp lại là cách rẻ tiền
     * để làm nghẽn máy chủ.
     */
    public record Credentials(
            @NotBlank(message = "Tên tài khoản không được để trống")
            @Size(max = 32, message = "Tên tài khoản tối đa 32 ký tự")
            String username,

            @NotBlank(message = "Mật khẩu không được để trống")
            @Size(max = 200, message = "Mật khẩu tối đa 200 ký tự")
            String password) {
    }

    /**
     * Phản hồi đăng nhập.
     *
     * <p>Trường {@code token} giữ nguyên tên cũ để giao diện hiện có không phải
     * sửa; {@code refreshToken} là phần thêm vào. Một lần đổi tên trường ở đây
     * đổi lấy việc phải phát hành lại ứng dụng máy khách — cái giá không tương
     * xứng với lợi ích của một cái tên đẹp hơn.
     */
    public record LoginResponse(String token, String refreshToken, Instant expiresAt,
                                User.PublicView user) {
    }

    /** Thân request gia hạn. */
    public record RefreshRequest(
            @NotBlank(message = "Thiếu refresh token")
            String refreshToken) {
    }

    /**
     * Đăng ký. Luôn tạo vai trò {@link Role#USER}.
     *
     * <p>Thân request <b>không có</b> trường {@code role} — không phải vì quên,
     * mà vì nếu có thì bất kỳ ai cũng tự cấp cho mình quyền quản trị bằng cách
     * thêm một dòng vào JSON. Đây là <i>mass assignment</i>, một dạng của
     * <i>A04:2021 — Insecure Design</i>. Muốn nâng vai trò thì phải qua
     * {@code POST /api/admin/users/{tên}/role}, và endpoint đó cần vai trò ADMIN
     * sẵn có.
     */
    @PostMapping("/register")
    public ResponseEntity<User.PublicView> register(@Valid @RequestBody Credentials request)
            throws IOException {
        User created = users.register(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toPublic());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody Credentials request) {
        User user = users.authenticate(request.username(), request.password());
        TokenService.TokenPair pair = tokens.issueFor(user);
        return new LoginResponse(pair.accessToken(), pair.refreshToken(), pair.expiresAt(),
                user.toPublic());
    }

    /**
     * Gia hạn: đổi refresh token lấy cặp token mới.
     *
     * <p>Giao diện gọi endpoint này khi access token sắp hết hạn, <b>ngầm</b> —
     * người dùng không thấy gì. Refresh token cũ chết ngay tại đây (xoay vòng),
     * nên nếu nó xuất hiện lần nữa thì hệ thống biết có bản sao và huỷ cả chuỗi.
     * Xem {@code RedisRefreshTokenStore}.
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        TokenService.TokenPair pair = tokens.refresh(request.refreshToken());
        User user = users.find(pair.username()).orElseThrow(() ->
                new TokenService.InvalidGrantException("Tài khoản không còn tồn tại."));
        return new LoginResponse(pair.accessToken(), pair.refreshToken(), pair.expiresAt(),
                user.toPublic());
    }

    /**
     * Đăng xuất tại thiết bị này.
     *
     * <p>Huỷ refresh token và đưa access token đang cầm vào danh sách thu hồi.
     * Thiếu vế thứ hai thì "đăng xuất" chỉ có nghĩa là "không gia hạn nữa", và
     * token hiện tại vẫn dùng được thêm 15 phút — đúng khoảng thời gian mà
     * người vừa bấm nút tin rằng mình đã an toàn.
     *
     * <p>Trả {@code 204} kể cả khi token đã hết hạn hoặc không tồn tại: người
     * dùng bấm "đăng xuất" thì kết quả họ mong đợi là <i>đã đăng xuất</i>, và
     * báo lỗi cho một trạng thái vốn đã đúng chỉ gây bối rối.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request,
                                       Authentication authentication) {
        String refreshToken = request == null ? null : request.refreshToken();
        Jwt jwt = jwtOf(authentication);
        tokens.logout(refreshToken,
                jwt == null ? null : jwt.getId(),
                jwt == null ? null : jwt.getExpiresAt());
        return ResponseEntity.noContent().build();
    }

    /** Thân request đổi mật khẩu. */
    public record PasswordChange(
            @NotBlank(message = "Mật khẩu hiện tại không được để trống")
            @Size(max = 200, message = "Mật khẩu tối đa 200 ký tự")
            String currentPassword,

            @NotBlank(message = "Mật khẩu mới không được để trống")
            @Size(max = 200, message = "Mật khẩu tối đa 200 ký tự")
            String newPassword) {
    }

    /**
     * Đổi mật khẩu, rồi <b>đóng mọi phiên</b>.
     *
     * <p>Lý do phổ biến nhất để đổi mật khẩu là <i>nghi có người khác đang dùng
     * tài khoản của mình</i>. Không đóng phiên thì kẻ kia vẫn ở trong, và người
     * dùng tưởng mình đã an toàn.
     *
     * <p>Khác bản trước, ở đây <b>không giữ lại</b> phiên đang gọi. Với refresh
     * token xoay vòng thì "giữ lại phiên hiện tại" nghĩa là giữ lại một refresh
     * token đã tồn tại từ trước lúc đổi mật khẩu — tức là đúng thứ có thể đã bị
     * đánh cắp. Giao diện xử lý việc này bằng cách đăng nhập lại ngay bằng mật
     * khẩu mới, nên người dùng không bị đá ra màn hình đăng nhập.
     */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @Valid @RequestBody PasswordChange request,
            Authentication authentication) throws IOException {

        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        users.changePassword(authentication.getName(),
                request.currentPassword(), request.newPassword());

        int closed = tokens.logoutEverywhere(authentication.getName());
        Jwt jwt = jwtOf(authentication);
        if (jwt != null) {
            tokens.denyAccessToken(jwt.getId(), jwt.getExpiresAt());
        }
        return ResponseEntity.ok(Map.of("status", "OK", "closedSessions", closed));
    }

    /**
     * Đăng xuất khỏi <b>mọi</b> thiết bị, kể cả thiết bị đang gọi.
     *
     * <p>Khác {@link #logout}: nút kia chỉ đóng phiên tại đây. Nút này dành cho
     * lúc người dùng nghi ngờ phiên của mình bị lộ ở nơi khác — và khi đó thứ
     * họ cần là <i>không còn phiên nào sống sót</i>, kể cả những phiên họ không
     * nhớ đã mở ở đâu.
     */
    @PostMapping("/logout-all")
    public Map<String, Object> logoutEverywhere(Authentication authentication) {
        if (authentication == null) {
            return Map.of("closedSessions", 0);
        }
        int closed = tokens.logoutEverywhere(authentication.getName());
        Jwt jwt = jwtOf(authentication);
        if (jwt != null) {
            tokens.denyAccessToken(jwt.getId(), jwt.getExpiresAt());
        }
        return Map.of("closedSessions", closed);
    }

    /**
     * "Tôi là ai" — giao diện gọi lúc khởi động để khôi phục trạng thái đăng nhập.
     *
     * <p>Nguồn sự thật là <b>máy chủ</b>, không phải thứ giao diện nhớ trong
     * {@code localStorage}. Nếu tin bản sao ở máy khách, một người đã bị hạ
     * quyền vẫn thấy giao diện quản trị đầy đủ cho tới lần gọi API đầu tiên
     * thất bại — trông như một lỗi, và tệ hơn, che mất việc quyền đã bị thu hồi.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String name = authentication.getName();
        // Phiên do API key cấp không có tài khoản đứng sau — nói thật điều đó
        // thay vì bịa ra một người dùng tên "admin-api-key".
        return ResponseEntity.ok(users.find(name)
                .map(user -> Map.<String, Object>of(
                        "authenticated", true,
                        "via", "jwt",
                        "user", user.toPublic()))
                .orElseGet(() -> Map.of(
                        "authenticated", true,
                        "via", "api-key",
                        "user", Map.of("username", name, "role", Role.ADMIN))));
    }

    private static Jwt jwtOf(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }
}
