package com.vnsearch.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/**
 * Ai đang gọi request này.
 *
 * <p>Ba service dữ liệu cá nhân (lịch sử, tải xuống, tuỳ chọn) đều bắt đầu mọi
 * hàm bằng cùng một câu hỏi, và câu trả lời sai ở đây là <b>rò rỉ dữ liệu giữa
 * các tài khoản</b> — người này thấy lịch sử duyệt web của người kia. Đó là
 * <i>A01:2021 — Broken Access Control</i>, hạng mục đứng đầu OWASP Top 10 và
 * cũng là lỗi phổ biến nhất trong các hệ thống nhiều người dùng. Nên nó được
 * viết đúng một lần, ở đây, thay vì ba lần trong ba controller.
 *
 * <p><b>Không có giá trị mặc định.</b> Một hàm kiểu
 * {@code currentUserOr("anonymous")} nghe tiện, nhưng nó gộp mọi người chưa
 * đăng nhập vào chung một hồ sơ {@code anonymous} — và thế là lịch sử duyệt
 * web của tất cả khách vãng lai nằm chung một chỗ, ai cũng đọc được.
 * {@link #required()} ném ngoại lệ thay vì bịa ra một danh tính.
 *
 * <p><b>Danh tính lấy từ claim {@code sub} của JWT, không phải từ tham số
 * request.</b> Khác biệt này là toàn bộ vấn đề: một endpoint nhận
 * {@code ?username=} rồi trả lịch sử của tên đó cho phép bất kỳ ai đọc dữ liệu
 * của bất kỳ ai — đúng định nghĩa IDOR. Claim {@code sub} thì được chữ ký RS256
 * bảo vệ và người gọi không sửa được.
 */
public final class CallerIdentity {

    private CallerIdentity() {
    }

    /** Tên đăng nhập, lấy từ claim {@code sub} của JWT đã xác minh chữ ký. */
    public static Optional<String> current() {
        return jwt().map(Jwt::getSubject).filter(subject -> !subject.isBlank());
    }

    /**
     * Như {@link #current()} nhưng ném khi không có ai.
     *
     * <p>Dùng ở những endpoint mà {@link ServiceSecurityConfig} đã bắt buộc
     * đăng nhập. Ngoại lệ ở đây nghĩa là chuỗi bảo mật vừa để lọt một request
     * lẽ ra phải chặn — một lỗi lập trình, không phải một tình huống của người
     * dùng — nên nó phải nổ chứ không được lặng lẽ trả về danh sách rỗng. Một
     * danh sách rỗng trông giống hệt "người này chưa có dữ liệu gì", và cái
     * giống nhau đó che mất lỗ hổng.
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

    /**
     * Định danh duy nhất của <b>chính token</b> đang dùng (claim {@code jti}).
     *
     * <p>Đây là thứ mà nhật ký kiểm toán cần và tên đăng nhập không thay thế
     * được: khi một tài khoản có dấu hiệu bị chiếm, câu hỏi không phải "ai đã
     * làm" mà là "<i>phiên nào</i> đã làm" — để tách hành vi của chủ tài khoản
     * khỏi hành vi của kẻ đang cầm token đánh cắp, rồi thu hồi đúng token đó
     * mà không đá chủ tài khoản ra ngoài.
     */
    public static Optional<String> tokenId() {
        return jwt().map(Jwt::getId).filter(id -> id != null && !id.isBlank());
    }

    private static Optional<Jwt> jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(token.getToken());
        }
        return Optional.empty();
    }
}
