package com.vnsearch.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vnsearch.auth.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Phát hành access token — một JWT ký RS256.
 *
 * <h2>Nội dung token, và vì sao đúng những claim này</h2>
 *
 * <pre>
 *   iss   ai phát hành          service khác so khớp để không nhận token lạ
 *   sub   ai là chủ token       KHOÁ phân vùng dữ liệu cá nhân (xem CallerIdentity)
 *   aud   token dành cho ai     chặn "confused deputy": token của bên A không dùng ở bên B
 *   exp   hết hạn               15 phút — xem bên dưới
 *   iat   phát lúc nào          để tính tuổi token trong nhật ký kiểm toán
 *   jti   định danh token       để THU HỒI đúng một token, và để lần vết
 *   roles vai trò               JwtRoleConverter dịch thành ROLE_*
 * </pre>
 *
 * <p><b>KHÔNG có claim nào chứa dữ liệu cá nhân.</b> Không email, không họ
 * tên, không số điện thoại. JWT chỉ được <i>ký</i> chứ không được <i>mã hoá</i>:
 * bất kỳ ai cầm token đều đọc được phần thân bằng một lệnh base64. Mỗi trường
 * thêm vào đây là một trường bị lộ ở mọi nơi token đi qua — log của proxy,
 * lịch sử shell, ảnh chụp màn hình khi gỡ lỗi.
 *
 * <h2>Vì sao 15 phút</h2>
 *
 * <p>Access token không thu hồi được bằng cách xoá ở máy chủ — đó là cái giá
 * cố hữu của token tự chứng thực. Thời gian sống chính là <b>cửa sổ thiệt hại
 * tối đa</b> khi một token bị đánh cắp. 15 phút đủ ngắn để giới hạn thiệt hại,
 * đủ dài để không phải làm mới token giữa hai lần bấm chuột. Người dùng không
 * thấy con số này: refresh token lo phần gia hạn ngầm.
 */
@Component
public class AccessTokenIssuer {

    private final RsaKeyProvider keys;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration lifetime;

    // @Autowired tường minh vì lớp này có HAI hàm dựng. Spring chỉ tự chọn
    // được khi có đúng một; có hai thì nó đi tìm hàm dựng không tham số, không
    // thấy, và đổ ở lúc khởi động với "No default constructor found" — một
    // thông báo không hề gợi ý nguyên nhân thật.
    @Autowired
    public AccessTokenIssuer(RsaKeyProvider keys,
                             @Value("${app.auth.issuer:http://auth-service:8081}") String issuer,
                             @Value("${app.auth.audience:vnsearch-api}") String audience,
                             @Value("${app.auth.access-token-ttl:PT15M}") Duration lifetime) {
        this(keys, issuer, audience, lifetime, Clock.systemUTC());
    }

    /** Hàm dựng cho kiểm thử: đồng hồ điều khiển được để kiểm tra hết hạn. */
    AccessTokenIssuer(RsaKeyProvider keys, String issuer, String audience,
                      Duration lifetime, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    /** Access token vừa phát, kèm hạn dùng để giao diện biết lúc nào cần làm mới. */
    public record IssuedToken(String value, String tokenId, Instant expiresAt) {
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiry = now.plus(lifetime);
        String tokenId = UUID.randomUUID().toString();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user.username())
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(tokenId)
                .claim("roles", List.of(user.role().name()))
                .build();

        // `kid` trong header là thứ cho phép XOAY KHOÁ mà không làm hỏng token
        // cũ: bên kiểm tra dùng nó để chọn đúng khoá công khai trong JWKS.
        // Thiếu nó, mọi token phải được kiểm bằng cách thử lần lượt từng khoá,
        // và trong thời gian chuyển tiếp thì không ai biết token nào thuộc về
        // khoá nào.
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.keyId()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Không ký được access token", e);
        }
        return new IssuedToken(jwt.serialize(), tokenId, expiry);
    }

    public Duration accessTokenLifetime() {
        return lifetime;
    }
}
