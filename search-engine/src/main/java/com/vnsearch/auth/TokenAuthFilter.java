package com.vnsearch.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Xác thực bằng <b>token phiên</b> trong header
 * {@code Authorization: Bearer <token>}.
 *
 * <p>Đây là đường của <b>con người</b>: đăng nhập bằng tài khoản/mật khẩu rồi
 * dùng token. Đường còn lại — {@code ApiKeyAuthFilter} với header
 * {@code X-API-Key} — là của <b>công cụ</b>: {@code curl}, job định kỳ,
 * healthcheck. Hai đường tồn tại song song có chủ ý:
 *
 * <pre>
 *   Ai gọi          Cơ chế                Đặc điểm
 *   ───────────     ──────────────────    ─────────────────────────────
 *   con người       tài khoản + phiên     có danh tính, thu hồi được,
 *                   Authorization: Bearer hết hạn sau 12 giờ, ghi được
 *                                         "ai đã làm gì"
 *   công cụ         khoá tĩnh             không danh tính, không hết hạn,
 *                   X-API-Key             đổi bằng cách khởi động lại
 * </pre>
 *
 * <p><b>Vì sao giữ cả hai thay vì bỏ khoá API đi.</b> Khoá tĩnh là thứ duy
 * nhất dùng được ở những nơi không có ai ngồi đăng nhập: script triển khai,
 * job cron, và — quan trọng nhất — <b>lối vào dự phòng khi kho tài khoản
 * hỏng</b>. Một hệ thống mà cách duy nhất để vào là đăng nhập, và tệp tài
 * khoản vừa hỏng, là một hệ thống tự khoá mình ra ngoài.
 *
 * <p><b>Thứ tự filter quan trọng.</b> Filter này chạy TRƯỚC
 * {@code ApiKeyAuthFilter}. Cả hai đều chỉ hành động khi header của mình có
 * mặt, nên chúng không giẫm lên nhau; nhưng nếu một request mang cả hai
 * header, phiên có danh tính sẽ thắng — và đó là lựa chọn đúng, vì nó ghi lại
 * được <i>ai</i> đã gọi.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final SessionStore sessions;

    public TokenAuthFilter(SessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        Optional<SessionStore.Session> session = extractToken(request).flatMap(sessions::lookup);

        session.ifPresent(value -> SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        value.username(), null,
                        AuthorityUtils.createAuthorityList(value.role().authority()))));

        // Token sai hoặc hết hạn: KHÔNG ghi log giá trị token, và không chặn ở
        // đây. Cứ đi tiếp không mang danh tính; tầng phân quyền của
        // SecurityConfig sẽ quyết định 401 hay cho qua. Nhờ vậy một token hết
        // hạn vẫn tìm kiếm được bình thường thay vì chặn cả những đường công khai.
        chain.doFilter(request, response);
    }

    private static Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return Optional.empty();
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
