package com.vnsearch.oauth;

import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Nơi duy nhất phát hành và thu hồi token.
 *
 * <p>Cả {@code AuthController} (đường của ứng dụng VnSearch) lẫn
 * {@code OAuth2Controller} (đường chuẩn OAuth2 cho ứng dụng bên thứ ba) đều gọi
 * xuống đây. <b>Hai lối vào, một cơ chế.</b> Nếu mỗi controller tự ghép token
 * theo cách của mình thì sớm muộn hai đường sẽ lệch nhau — chẳng hạn một đường
 * quên ghi refresh token vào kho, và người dùng đi bằng đường đó không đăng
 * xuất được. Loại lệch này không lộ ra trong test đơn vị của từng controller.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final UserService users;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenStore refreshTokens;
    private final Duration refreshTokenTtl;

    public TokenService(UserService users,
                        AccessTokenIssuer accessTokens,
                        RefreshTokenStore refreshTokens,
                        @Value("${app.auth.refresh-token-ttl:P30D}") Duration refreshTokenTtl) {
        this.users = users;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /**
     * Cặp token trả về cho máy khách.
     *
     * <p>Tên trường theo đúng RFC 6749 §5.1 ({@code access_token},
     * {@code token_type}, {@code expires_in}, {@code refresh_token}) chứ không
     * đặt tên theo ý mình. Một thư viện OAuth2 bất kỳ ở phía máy khách đọc
     * được ngay, không cần lớp chuyển đổi — và đó là toàn bộ giá trị của việc
     * theo chuẩn thay vì tự nghĩ ra một dạng phản hồi riêng.
     */
    public record TokenPair(String username, String accessToken, String refreshToken,
                            Instant expiresAt, String tokenType, long expiresIn) {
    }

    /** Cấp cặp token mới sau khi đã xác thực tài khoản và mật khẩu. */
    public TokenPair issueFor(User user) {
        return build(user, null);
    }

    /**
     * Đổi refresh token lấy cặp token mới (xoay vòng).
     *
     * @throws InvalidGrantException khi token không hợp lệ, đã dùng, hoặc tài
     *                               khoản đứng sau nó không còn hoạt động
     */
    public TokenPair refresh(String refreshToken) {
        RefreshTokenStore.Grant grant = refreshTokens.consume(refreshToken)
                .orElseThrow(() -> new InvalidGrantException(
                        "Refresh token không hợp lệ hoặc đã được dùng."));

        // Tra lại tài khoản thay vì tin dữ liệu trong token cũ. Giữa hai lần
        // gia hạn, tài khoản có thể đã bị KHOÁ hoặc bị HẠ QUYỀN — và nếu chỉ
        // chép lại vai trò cũ thì một quản trị viên vừa bị thu quyền vẫn tiếp
        // tục được cấp token ADMIN mới cho tới khi refresh token hết hạn, tức
        // là tới ba mươi ngày sau.
        User user = users.find(grant.username())
                .orElseThrow(() -> new InvalidGrantException("Tài khoản không còn tồn tại."));
        if (!user.enabled()) {
            refreshTokens.revokeAllFor(user.username());
            throw new InvalidGrantException("Tài khoản đã bị vô hiệu hoá.");
        }
        return build(user, grant.family());
    }

    /**
     * Đăng xuất tại một thiết bị.
     *
     * <p>Huỷ refresh token (nếu máy khách gửi kèm) và đưa access token hiện tại
     * vào danh sách thu hồi. Thiếu vế thứ hai thì "đăng xuất" chỉ có nghĩa là
     * "không gia hạn nữa", và token đang cầm vẫn dùng được thêm 15 phút — đúng
     * khoảng thời gian mà người bấm nút đăng xuất tin rằng mình đã an toàn.
     */
    public void logout(String refreshToken, String accessTokenId, Instant accessTokenExpiry) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revoke(refreshToken);
        }
        denyAccessToken(accessTokenId, accessTokenExpiry);
    }

    /** Đăng xuất khỏi mọi thiết bị. Trả về số phiên đã đóng. */
    public int logoutEverywhere(String username) {
        int closed = refreshTokens.revokeAllFor(username);
        log.info("Đã đóng {} phiên của tài khoản {}", closed, username);
        return closed;
    }

    /** Đưa một access token vào danh sách thu hồi cho tới lúc nó hết hạn. */
    public void denyAccessToken(String tokenId, Instant expiry) {
        if (tokenId == null || expiry == null) {
            return;
        }
        refreshTokens.denyAccessToken(tokenId, Duration.between(Instant.now(), expiry));
    }

    public boolean isAccessTokenDenied(String tokenId) {
        return refreshTokens.isAccessTokenDenied(tokenId);
    }

    /**
     * Số phiên đăng nhập đang mở — CHỈ cho bảng điều khiển quản trị.
     *
     * <p>Không gọi nó trên đường chạy của một request thường: ở bản Redis, đây
     * là một phép duyệt không gian khoá. Xem
     * {@code RedisRefreshTokenStore#activeSessionCount()}.
     */
    public int activeSessionCount() {
        return refreshTokens.activeSessionCount();
    }

    private TokenPair build(User user, String family) {
        AccessTokenIssuer.IssuedToken access = accessTokens.issue(user);
        String refresh = refreshTokens.issue(user.username(), family, refreshTokenTtl);
        return new TokenPair(user.username(), access.value(), refresh, access.expiresAt(),
                "Bearer", accessTokens.accessTokenLifetime().toSeconds());
    }

    /**
     * Tương ứng lỗi {@code invalid_grant} của RFC 6749 §5.2.
     *
     * <p><b>Thông báo cố ý không nói rõ vì sao.</b> "Token không hợp lệ hoặc đã
     * được dùng" gộp ba tình huống khác nhau (bịa, hết hạn, dùng lại) làm một.
     * Phân biệt chúng cho người gọi biết là tặng kẻ tấn công một kênh dò: gửi
     * token đoán bừa rồi đọc thông báo để biết token nào từng tồn tại.
     */
    public static class InvalidGrantException extends RuntimeException {
        public InvalidGrantException(String message) {
            super(message);
        }
    }
}
