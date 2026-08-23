package com.vnsearch.controller;

import com.vnsearch.auth.Role;
import com.vnsearch.auth.SessionStore;
import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
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
    private final SessionStore sessions;

    public AdminUserController(UserService users, SessionStore sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public record RoleChange(@NotNull(message = "role không được để trống") Role role) {
    }

    /** Danh sách tài khoản — dạng công khai, KHÔNG có hash mật khẩu. */
    @GetMapping
    public List<User.PublicView> list() {
        return users.findAll().stream().map(User::toPublic).toList();
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
        sessions.revokeAllFor(updated.username());
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
        sessions.revokeAllFor(updated.username());
        return ResponseEntity.ok(updated.toPublic());
    }

    @PostMapping("/{username}/enable")
    public ResponseEntity<User.PublicView> enable(@PathVariable String username)
            throws IOException {
        return ResponseEntity.ok(users.setEnabled(username, true).toPublic());
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
        sessions.revokeAllFor(username);
        return ResponseEntity.noContent().build();
    }
}
