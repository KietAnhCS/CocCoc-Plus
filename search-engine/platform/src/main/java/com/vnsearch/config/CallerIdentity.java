package com.vnsearch.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Ai đang gọi request này.
 *
 * <p>Ba service dữ liệu cá nhân (lịch sử, tải xuống, tuỳ chọn) đều bắt đầu mọi
 * hàm bằng cùng một câu hỏi, và câu trả lời sai ở đây là <b>rò rỉ dữ liệu giữa
 * các tài khoản</b> — người này thấy lịch sử duyệt web của người kia. Nên nó
 * được viết đúng một lần, ở đây, thay vì ba lần trong ba controller.
 *
 * <p><b>Không có giá trị mặc định.</b> Một hàm kiểu {@code currentUserOr("anonymous")}
 * nghe tiện, nhưng nó gộp mọi người chưa đăng nhập vào chung một hồ sơ
 * {@code anonymous} — và thế là lịch sử duyệt web của tất cả khách vãng lai
 * nằm chung một chỗ, ai cũng đọc được. {@link #required()} ném ngoại lệ thay
 * vì bịa ra một danh tính.
 */
public final class CallerIdentity {

    private CallerIdentity() {
    }

    /** Tên đăng nhập, nếu request mang danh tính hợp lệ do Gateway truyền xuống. */
    public static Optional<String> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String username) || username.isBlank()
                || "anonymousUser".equals(username)) {
            return Optional.empty();
        }
        return Optional.of(username);
    }

    /**
     * Như {@link #current()} nhưng ném khi không có ai.
     *
     * <p>Dùng ở những endpoint mà {@link ServiceSecurityConfig} đã bắt buộc
     * đăng nhập. Ngoại lệ ở đây nghĩa là chuỗi bảo mật vừa để lọt một request
     * lẽ ra phải chặn — một lỗi lập trình, không phải một tình huống của người
     * dùng — nên nó phải nổ chứ không được lặng lẽ trả về danh sách rỗng.
     */
    public static String required() {
        return current().orElseThrow(() -> new IllegalStateException(
                "Endpoint này cần danh tính nhưng SecurityContext trống. "
                        + "Kiểm tra lại luật trong ServiceSecurityConfig: nhiều khả năng "
                        + "đường dẫn vừa bị khai nhầm vào PublicEndpoints."));
    }

    /** Người gọi có vai trò quản trị hay không. */
    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
    }
}
