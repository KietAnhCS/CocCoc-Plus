package com.vnsearch.controller;

import com.vnsearch.auth.Role;
import com.vnsearch.auth.SessionStore;
import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Đăng ký, đăng nhập, đăng xuất, và "tôi là ai".
 *
 * <p><b>Vì sao {@code /api/auth/**} tách khỏi {@code /api/admin/**}.</b> Hai
 * nhóm trả lời hai câu hỏi khác nhau: nhóm này là <i>cửa vào</i> (ai cũng phải
 * gõ được, kể cả người chưa có tài khoản), nhóm kia là <i>phòng trong</i> (chỉ
 * ADMIN). Gộp chung đường dẫn thì luật phân quyền phải viết theo từng endpoint
 * lẻ thay vì theo tiền tố — và mỗi luật lẻ là một chỗ có thể quên.
 *
 * <p><b>Token đi trong thân phản hồi, không đi trong cookie.</b> Cookie sẽ
 * được trình duyệt <i>tự động</i> đính kèm vào mọi request tới máy chủ, và
 * chính tính tự động đó là thứ làm nên tấn công CSRF — lúc đó phải dựng thêm
 * cả bộ chống CSRF. Token đặt thủ công vào header {@code Authorization} thì
 * không bao giờ tự đi theo, nên không có gì để giả mạo. Đây cũng là lý do
 * {@code SecurityConfig} tắt CSRF một cách có cơ sở chứ không phải cho tiện.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;
    private final SessionStore sessions;

    public AuthController(UserService users, SessionStore sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    /**
     * Thông tin đăng nhập.
     *
     * <p>Chặn độ dài ngay tại đây, TRƯỚC khi chuỗi tới BCrypt: băm là phép
     * tính cố ý chậm (~200 ms), nên một chuỗi khổng lồ gửi lặp lại là cách rẻ
     * tiền để làm nghẽn máy chủ.
     */
    public record Credentials(
            @NotBlank(message = "Tên tài khoản không được để trống")
            @Size(max = 32, message = "Tên tài khoản tối đa 32 ký tự")
            String username,

            @NotBlank(message = "Mật khẩu không được để trống")
            @Size(max = 200, message = "Mật khẩu tối đa 200 ký tự")
            String password) {
    }

    /** Phản hồi đăng nhập: token + đúng những gì giao diện cần để vẽ. */
    public record LoginResponse(String token, Instant expiresAt, User.PublicView user) {
    }

    /**
     * Đăng ký. Luôn tạo vai trò {@link Role#USER}.
     *
     * <p>Thân request <b>không có</b> trường {@code role} — không phải vì quên,
     * mà vì nếu có thì bất kỳ ai cũng tự cấp cho mình quyền quản trị bằng cách
     * thêm một dòng vào JSON. Muốn nâng vai trò thì phải qua
     * {@code POST /api/admin/users/{ten}/role}, và endpoint đó cần vai trò
     * ADMIN sẵn có.
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
        String token = sessions.open(user);
        return new LoginResponse(
                token,
                sessions.lookup(token).map(SessionStore.Session::expiresAt).orElse(null),
                user.toPublic());
    }

    /**
     * Đăng xuất — huỷ phiên <b>ngay</b>.
     *
     * <p>Đây là thứ mà một token JWT không tự làm được, và là lý do chính hệ
     * thống này dùng token mờ có trạng thái (xem {@code SessionStore}).
     *
     * <p>Trả {@code 204} kể cả khi token đã hết hạn hoặc không tồn tại: người
     * dùng bấm "đăng xuất" thì kết quả họ mong đợi là <i>đã đăng xuất</i>, và
     * báo lỗi cho một trạng thái vốn đã đúng chỉ gây bối rối.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = bearerToken(request);
        if (token != null) {
            sessions.revoke(token);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Token trong header {@code Authorization}, hoặc {@code null}.
     *
     * <p>Đọc lại từ request thay vì lấy từ {@link Authentication}: đối tượng
     * xác thực cố ý <b>không</b> mang theo token (nó là bí mật, và một thứ bí
     * mật nằm trong đối tượng được truyền khắp nơi thì sớm muộn cũng vào log).
     * Hai chỗ cần chính token — đăng xuất và đổi mật khẩu — thì đọc lại ở đây.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
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
     * Đổi mật khẩu, rồi <b>đóng mọi phiên khác</b>.
     *
     * <p>Giữ lại đúng phiên đang gọi: người vừa đổi mật khẩu không nên bị đá ra
     * khỏi chính thiết bị họ đang ngồi. Nhưng mọi phiên khác phải chết, vì lý
     * do phổ biến nhất để đổi mật khẩu là <i>nghi có người khác đang dùng tài
     * khoản của mình</i> — không đóng thì kẻ kia vẫn ở trong, và người dùng
     * tưởng mình đã an toàn.
     */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @Valid @RequestBody PasswordChange request,
            Authentication authentication,
            HttpServletRequest httpRequest) throws IOException {

        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        users.changePassword(authentication.getName(),
                request.currentPassword(), request.newPassword());

        int closed = sessions.revokeAllForExcept(authentication.getName(),
                bearerToken(httpRequest));
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "closedOtherSessions", closed));
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
        return Map.of("closedSessions", sessions.revokeAllFor(authentication.getName()));
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
                        "via", "session",
                        "user", user.toPublic()))
                .orElseGet(() -> Map.of(
                        "authenticated", true,
                        "via", "api-key",
                        "user", Map.of("username", name, "role", Role.ADMIN))));
    }
}
