package com.vnsearch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Nhận DANH TÍNH do API Gateway truyền xuống, và chỉ tin nó khi request thật
 * sự đến từ Gateway.
 *
 * <h2>Vì sao lớp này tồn tại</h2>
 *
 * <p>Trong bản một khối, mỗi request tự mang {@code Authorization: Bearer
 * <token>} và tiến trình duy nhất tự tra token đó trong {@code SessionStore}.
 * Tách thành tám tiến trình thì kho phiên chỉ còn sống ở <b>một</b> nơi —
 * {@code auth-service}. Bảy tiến trình còn lại có hai lựa chọn:
 *
 * <pre>
 *   (a) mỗi service tự gọi auth-service cho MỖI request
 *   (b) Gateway gọi MỘT lần, rồi dán kết quả vào header
 * </pre>
 *
 * <p>Chọn (b). Với (a), một trang tìm kiếm chạm bốn service sẽ tạo bốn lượt
 * tra phiên cho cùng một người dùng, và auth-service trở thành điểm chết chung
 * của cả hệ thống: nó chậm thì mọi thứ chậm, nó sập thì mọi thứ sập — kể cả
 * những endpoint công khai vốn không cần biết người gọi là ai.
 *
 * <h2>Cái giá của (b), và cách trả</h2>
 *
 * <p>Header thì <b>ai cũng gửi được</b>. Nếu service tin bừa
 * {@code X-VnSearch-Role: ADMIN}, thì bất kỳ ai chạm được tới cổng 8082 đều
 * trở thành quản trị viên bằng một dòng {@code curl}. Đây không phải rủi ro lý
 * thuyết: trong Docker Compose và trong Kubernetes, mọi container cùng mạng
 * đều gọi thẳng được tới nhau, không đi qua Gateway.
 *
 * <p>Nên danh tính chỉ được tin khi request kèm {@link #HEADER_GATEWAY} khớp
 * với một bí mật dùng chung mà chỉ Gateway và các service biết. Ba chi tiết
 * đáng nói:
 *
 * <ol>
 *   <li><b>So sánh trong thời gian hằng định</b> ({@link MessageDigest#isEqual}).
 *       So bằng {@code equals} thoát ra ngay ở ký tự lệch đầu tiên, và thời
 *       gian phản hồi rò rỉ từng ký tự một cho kẻ đo đủ kiên nhẫn.</li>
 *   <li><b>Có header danh tính mà KHÔNG có bí mật hợp lệ thì bị từ chối
 *       thẳng</b>, chứ không phải bị bỏ qua trong im lặng. Bỏ qua thì request
 *       vẫn chạy tiếp như một người ẩn danh và không ai biết vừa có ai đó thử
 *       giả mạo; từ chối kèm một dòng log biến nó thành thứ đếm được.</li>
 *   <li><b>Không có header nào cả thì KHÔNG phải lỗi.</b> Đó là đường đi bình
 *       thường của các endpoint công khai ({@code /api/search},
 *       {@code /api/health}) và của các lượt gọi nội bộ giữa service. Việc
 *       quyết định endpoint nào cần đăng nhập là của
 *       {@link ServiceSecurityConfig}, không phải của lớp này.</li>
 * </ol>
 *
 * <h2>Vì sao không dùng JWT</h2>
 *
 * <p>JWT sẽ bỏ được bí mật dùng chung: mỗi service tự kiểm chữ ký. Nhưng
 * {@code SessionStore} hiện tại cho phép <b>thu hồi</b> một phiên ngay lập tức
 * ({@code logout-all}, khoá tài khoản), còn một JWT đã phát ra thì sống tới
 * lúc hết hạn dù tài khoản đã bị vô hiệu hoá. Đổi sang JWT là đổi một tính
 * năng an ninh đang có lấy một sơ đồ gọn hơn — không đáng, ở quy mô này.
 */
public class GatewayIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayIdentityFilter.class);

    /** Bí mật dùng chung, chứng minh request đi qua Gateway. */
    public static final String HEADER_GATEWAY = "X-VnSearch-Gateway";

    /** Tên đăng nhập của người dùng đứng sau request. */
    public static final String HEADER_USER = "X-VnSearch-User";

    /** Vai trò: {@code USER} hoặc {@code ADMIN}. */
    public static final String HEADER_ROLE = "X-VnSearch-Role";

    private final byte[] expectedSecret;

    public GatewayIdentityFilter(String sharedSecret) {
        this.expectedSecret = sharedSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String username = request.getHeader(HEADER_USER);
        String role = request.getHeader(HEADER_ROLE);
        boolean claimsIdentity = username != null && !username.isBlank();

        if (claimsIdentity && !fromGateway(request)) {
            // Không phải "thiếu quyền" mà là "anh không phải người anh nói".
            // Ghi ĐỦ để truy được nguồn, nhưng KHÔNG ghi giá trị bí mật mà bên
            // kia đã gửi — log là nơi bí mật rò rỉ nhiều nhất.
            log.warn("Từ chối danh tính giả mạo: {} tự xưng {}={} nhưng không có {} hợp lệ",
                    request.getRemoteAddr(), HEADER_USER, username, HEADER_GATEWAY);
            response.sendError(HttpStatus.UNAUTHORIZED.value(),
                    "Danh tính chỉ được chấp nhận khi đi qua API Gateway.");
            return;
        }

        if (claimsIdentity) {
            String authority = "ROLE_" + (role == null || role.isBlank() ? "USER" : role.trim());
            var authentication = new UsernamePasswordAuthenticationToken(
                    username, null, AuthorityUtils.createAuthorityList(authority));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        chain.doFilter(request, response);
    }

    private boolean fromGateway(HttpServletRequest request) {
        String presented = request.getHeader(HEADER_GATEWAY);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedSecret);
    }
}
