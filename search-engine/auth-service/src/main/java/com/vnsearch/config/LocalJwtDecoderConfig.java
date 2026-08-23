package com.vnsearch.config;

import com.vnsearch.oauth.RsaKeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;

import java.util.List;

/**
 * Bộ giải mã JWT của <b>riêng</b> auth-service: dùng khoá công khai có sẵn
 * trong tiến trình, không tải JWKS qua HTTP.
 *
 * <h2>Vì sao khác bảy service kia</h2>
 *
 * <p>Các service khác lấy khoá công khai từ {@code /oauth2/jwks} của
 * auth-service — chúng không có cách nào khác. auth-service thì <i>tự giữ</i>
 * cặp khoá, nên bắt nó gọi HTTP tới chính nó là ba vấn đề cùng lúc:
 *
 * <ol>
 *   <li><b>Vòng phụ thuộc lúc khởi động.</b> Chuỗi bảo mật cần
 *       {@code JwtDecoder}, {@code JwtDecoder} cần JWKS, JWKS được phục vụ bởi
 *       cổng HTTP mà chuỗi bảo mật đang bảo vệ.</li>
 *   <li><b>Không chạy được trong kiểm thử.</b> Địa chỉ
 *       {@code http://auth-service:8081} chỉ phân giải được trong mạng
 *       container; bài test MockMvc sẽ đổ ở lần xác minh token đầu tiên.</li>
 *   <li><b>Một lượt gọi mạng vô nghĩa</b> cho mỗi lần làm mới bộ nhớ đệm khoá,
 *       tới đúng cái máy chủ đang gọi.</li>
 * </ol>
 *
 * <h2>Hai phép kiểm KHÔNG được bỏ</h2>
 *
 * <p>{@code NimbusJwtDecoder.withPublicKey()} chỉ kiểm <b>chữ ký</b> và
 * <b>hạn dùng</b>. Phải thêm tay:
 *
 * <ul>
 *   <li>{@code iss} — chặn token do một máy chủ khác ký bằng khoá của nó;</li>
 *   <li>{@code aud} — chặn "confused deputy": một token cấp cho ứng dụng khác
 *       (audience khác) không được dùng lại ở đây. Spring KHÔNG kiểm
 *       {@code aud} mặc định, và đây là chỗ hay bị bỏ sót nhất khi tự dựng
 *       resource server.</li>
 * </ul>
 */
@Configuration
public class LocalJwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(RsaKeyProvider keys,
                                 @Value("${app.auth.issuer}") String issuer,
                                 @Value("${app.auth.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator(audience)));
        return decoder;
    }

    /**
     * Token phải ghi đúng {@code aud}.
     *
     * <p>Không có phép kiểm này thì một access token do chính hệ thống phát ra
     * cho <i>mục đích khác</i> — ví dụ một token cấp cho một tích hợp bên
     * ngoài — vẫn mở được mọi endpoint ở đây. Chữ ký hợp lệ, hạn còn, người
     * dùng có thật; chỉ có điều nó không được cấp cho cái cửa này.
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> {
            List<String> claimed = jwt.getAudience();
            if (claimed != null && claimed.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token không dành cho đối tượng '" + audience + "' (claim "
                            + JwtClaimNames.AUD + " = " + claimed + ")",
                    null));
        };
    }
}
