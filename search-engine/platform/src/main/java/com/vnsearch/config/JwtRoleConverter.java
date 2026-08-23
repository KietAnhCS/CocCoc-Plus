package com.vnsearch.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Dịch claim {@code roles} trong JWT thành {@code GrantedAuthority} của Spring
 * Security.
 *
 * <h2>Vì sao phải viết tay</h2>
 *
 * <p>Mặc định, Spring Security đọc quyền từ claim {@code scope} (hoặc
 * {@code scp}) và gắn tiền tố {@code SCOPE_}. Nhưng {@code scope} trong OAuth2
 * mô tả <b>ứng dụng khách được phép làm gì</b>, còn {@code roles} mô tả
 * <b>người dùng là ai</b> — hai khái niệm khác nhau, và trộn chúng là một lỗi
 * phân quyền chờ xảy ra: một client được cấp scope rộng sẽ vô tình leo thang
 * thành quản trị viên.
 *
 * <p>Toàn bộ luật phân quyền trong hệ thống này viết theo VAI TRÒ
 * ({@code hasRole("ADMIN")}), nên chuyển đổi ở đây phải sinh ra đúng dạng mà
 * {@code hasRole} mong đợi: tiền tố {@code ROLE_}. Quên tiền tố này là lỗi
 * kinh điển — luật {@code hasRole("ADMIN")} sẽ không bao giờ khớp, và mọi
 * endpoint quản trị trả 403 cho cả quản trị viên thật.
 *
 * <h2>Vì sao không tin claim mà không kiểm</h2>
 *
 * <p>Không cần kiểm ở đây: bộ giải mã JWT đã xác minh <b>chữ ký RS256</b> bằng
 * khoá công khai lấy từ JWKS của auth-service <i>trước</i> khi converter này
 * được gọi. Một token có claim {@code roles: ["ADMIN"]} nhưng chữ ký sai thì
 * không bao giờ tới được dòng lệnh đầu tiên của lớp này.
 */
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Tên claim chứa danh sách vai trò. auth-service ghi vào đúng claim này. */
    public static final String ROLES_CLAIM = "roles";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // getClaimAsStringList trả về null khi claim vắng mặt — token của một
        // ứng dụng khách (client credentials) không có người dùng nào đứng sau
        // nên không có vai trò, và đó là trường hợp hợp lệ, không phải lỗi.
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim()));
                }
            }
        }

        // Tên hiển thị lấy từ `sub`. Đây cũng là khoá mà ba service dữ liệu cá
        // nhân dùng để phân vùng dữ liệu — xem CallerIdentity.
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
