package com.vnsearch.controller;

import com.vnsearch.auth.Role;
import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
import com.vnsearch.config.AuditLogger;
import com.vnsearch.oauth.TokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Quản lý tài khoản — chỉ vai trò ADMIN.
 *
 * <p>Nằm dưới {@code /api/admin/**} nên thừa hưởng nguyên luật phân quyền của
 * {@code SecurityConfig}; không có một dòng kiểm tra quyền nào trong lớp này.
 *
 * <p><b>Hai bảo vệ riêng của việc đổi vai trò</b>, không đến từ tầng phân
 * quyền mà từ chính nghiệp vụ:
 * <ol>
 *   <li><b>Không tự hạ quyền chính mình.</b> Nếu người quản trị cuối cùng hạ
 *       vai trò của chính họ, hệ thống không còn ADMIN nào và không ai nâng
 *       lại được — một cánh cửa khoá từ bên trong. (Khoá API vẫn là lối vào dự
 *       phòng, nhưng dựa vào lối dự phòng cho một thao tác thường ngày là thiết
 *       kế tồi.)</li>
 *   <li><b>Hạ quyền phải đóng mọi phiên của người đó.</b> Không làm vậy thì
 *       người vừa bị hạ vẫn giữ một phiên mang vai trò ADMIN cho tới khi phiên
 *       hết hạn — tức là quyền bị thu hồi trên giấy nhưng còn hiệu lực thêm
 *       nhiều giờ.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserService users;
    private final TokenService tokens;
    private final AuditLogger audit;

    public AdminUserController(UserService users, TokenService tokens, AuditLogger audit) {
        this.users = users;
        this.tokens = tokens;
        this.audit = audit;
    }

    /**
     * Bốn con số về tài khoản, cho bảng điều khiển quản trị.
     *
     * <p><b>Vì sao khai lại ở đây thay vì dùng chung một lớp với
     * analytics-service.</b> Dùng chung một lớp DTO giữa hai service nghe tiết
     * kiệm, nhưng nó tạo ra một phụ thuộc biên dịch giữa hai tiến trình lẽ ra
     * độc lập: đổi một trường ở đây là buộc bên kia dựng lại và triển khai lại
     * cùng lúc — tức là đánh mất chính thứ mà việc tách service mua được.
     *
     * <p>Hợp đồng giữa hai bên là <b>JSON trên dây</b>, không phải một tệp
     * {@code .class}. Hai bản khai giống nhau ở hai bên là cái giá đúng, và nó
     * còn cho phép bên kia thêm trường mà không cần bên này biết.
     *
     * @param activeSessions số refresh token còn sống, tức số phiên đăng nhập
     *                       đang mở. Khác với "số người đang online": một người
     *                       mở ba thiết bị là ba phiên.
     */
    public record AccountStats(int total, int admins, int disabled, int activeSessions) {
    }

    public record RoleChange(@NotNull(message = "role không được để trống") Role role) {
    }

    /** Danh sách tài khoản — dạng công khai, KHÔNG có hash mật khẩu. */
    @GetMapping
    public List<User.PublicView> list() {
        return users.findAll().stream().map(User::toPublic).toList();
    }

    /**
     * Số liệu tổng hợp về tài khoản, cho bảng điều khiển quản trị.
     *
     * <p><b>Vì sao có endpoint riêng thay vì để analytics-service đọc thẳng CSDL
     * tài khoản.</b> Bảng điều khiển cần đúng bốn con số này, và cách nhanh
     * nhất để lấy chúng là cho analytics-service kết nối vào CSDL của
     * auth-service. Đó cũng là cách chắc chắn nhất để phá vỡ ranh giới service:
     * hai tiến trình dùng chung một lược đồ thì không tiến trình nào đổi được
     * lược đồ nữa, và một lỗi ở analytics-service chạm được tới bảng mật khẩu.
     *
     * <p>Một endpoint hẹp, chỉ trả về bốn số nguyên, giữ nguyên ranh giới: bên
     * kia không biết bảng tên gì, cột tên gì, và không đọc được gì ngoài bốn số
     * này.
     */
    @GetMapping("/stats")
    public AccountStats stats() {
        List<User> all = users.findAll();
        return new AccountStats(
                all.size(),
                (int) all.stream().filter(user -> user.role() == Role.ADMIN).count(),
                (int) all.stream().filter(user -> !user.enabled()).count(),
                tokens.activeSessionCount());
    }

    @PostMapping("/{username}/role")
    public ResponseEntity<User.PublicView> changeRole(
            @PathVariable String username,
            @Valid @RequestBody RoleChange request,
            Authentication authentication) throws IOException {

        if (authentication != null && authentication.getName().equalsIgnoreCase(username)
                && request.role() != Role.ADMIN) {
            return ResponseEntity.badRequest().build(); // xem Javadoc lớp, mục 1
        }

        User updated = users.changeRole(username, request.role());
        // Xem Javadoc lớp, mục 2. Đóng phiên cả khi NÂNG quyền: phiên cũ mang
        // vai trò cũ, nên người vừa được nâng sẽ không hiểu vì sao vẫn bị 401
        // — bắt đăng nhập lại là hành vi dễ hiểu hơn.
        tokens.logoutEverywhere(updated.username());
        audit.record(authentication == null ? null : authentication.getName(), "ROLE_CHANGE",
                "auth_users:" + username, "SUCCESS", "role=" + request.role());
        return ResponseEntity.ok(updated.toPublic());
    }

    @PostMapping("/{username}/disable")
    public ResponseEntity<User.PublicView> disable(@PathVariable String username,
                                                    Authentication authentication)
            throws IOException {
        if (authentication != null && authentication.getName().equalsIgnoreCase(username)) {
            return ResponseEntity.badRequest().build();
        }
        User updated = users.setEnabled(username, false);
        tokens.logoutEverywhere(updated.username());
        audit.record(authentication == null ? null : authentication.getName(), "ACCOUNT_DISABLE",
                "auth_users:" + username, "SUCCESS", null);
        return ResponseEntity.ok(updated.toPublic());
    }

    @PostMapping("/{username}/enable")
    public ResponseEntity<User.PublicView> enable(@PathVariable String username,
                                                   Authentication authentication)
            throws IOException {
        User updated = users.setEnabled(username, true);
        audit.record(authentication == null ? null : authentication.getName(), "ACCOUNT_ENABLE",
                "auth_users:" + username, "SUCCESS", null);
        return ResponseEntity.ok(updated.toPublic());
    }

    /**
     * Xoá hẳn một tài khoản.
     *
     * <p><b>{@code DELETE} chứ không phải {@code POST /delete}.</b> Phương thức
     * HTTP mô tả đúng việc đang làm, và nó là <i>idempotent</i>: gọi hai lần cho
     * cùng một tên thì lần sau trả {@code 404} chứ không gây thêm hậu quả nào.
     *
     * <p>Vẫn chặn tự xoá chính mình, cùng lý do với đổi vai trò — nhưng ở đây
     * hậu quả nặng hơn: người quản trị cuối cùng tự xoá thì không còn tài khoản
     * nào nâng lại được, và lối vào duy nhất còn lại là khoá API tĩnh.
     *
     * <p>Phiên của người bị xoá cũng bị đóng. Không đóng thì token cũ vẫn tra ra
     * một phiên hợp lệ mang vai trò cũ, trong khi tài khoản đứng sau nó đã biến
     * mất — một trạng thái không nên tồn tại.
     */
    @DeleteMapping("/{username}")
    public ResponseEntity<Void> delete(@PathVariable String username,
                                        Authentication authentication) throws IOException {
        if (authentication != null && authentication.getName().equalsIgnoreCase(username)) {
            return ResponseEntity.badRequest().build();
        }
        if (!users.delete(username)) {
            return ResponseEntity.notFound().build();
        }
        tokens.logoutEverywhere(username);
        audit.record(authentication == null ? null : authentication.getName(), "ACCOUNT_DELETE",
                "auth_users:" + username, "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }
}
