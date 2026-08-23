package com.vnsearch.oauth;

import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
import com.vnsearch.config.GlobalExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bề mặt <b>chuẩn OAuth2</b> của VnSearch.
 *
 * <pre>
 *   POST /oauth2/token                     RFC 6749 §4.3 và §6
 *   POST /oauth2/revoke                    RFC 7009
 *   GET  /oauth2/jwks                      RFC 7517
 *   GET  /.well-known/openid-configuration RFC 8414 (bản rút gọn)
 * </pre>
 *
 * <h2>Vì sao có cả lối này lẫn {@code /api/auth/login}</h2>
 *
 * <p>Hai lối phục vụ hai loại máy khách. {@code /api/auth/login} trả về đúng
 * dạng JSON mà trình duyệt VnSearch đã dùng từ trước, nên giao diện không phải
 * viết lại. {@code /oauth2/token} thì trả về đúng dạng mà <i>bất kỳ</i> thư
 * viện OAuth2 nào cũng đọc được — Postman, một service Spring khác, một ứng
 * dụng di động. Cả hai gọi xuống cùng một {@link TokenService}, nên không có
 * hai cơ chế phát hành token, chỉ có hai cách gói phản hồi.
 *
 * <h2>Về grant_type=password, và vì sao nó vẫn ở đây</h2>
 *
 * <p>OAuth 2.1 <b>bỏ</b> luồng "resource owner password credentials", vì nó bắt
 * ứng dụng khách cầm mật khẩu của người dùng — điều không chấp nhận được với
 * ứng dụng của <i>bên thứ ba</i>. Ở đây, ứng dụng khách là chính trình duyệt
 * VnSearch, do cùng một nhóm viết ra: nó đã gõ mật khẩu vào ô của chính nó rồi.
 *
 * <p>Đó là lý do <b>duy nhất</b> khiến luồng này chấp nhận được, và nó có hạn
 * dùng. Khi VnSearch mở API cho ứng dụng ngoài, luồng đúng là Authorization
 * Code + PKCE, và {@code grant_type=password} phải bị gỡ chứ không phải bị giữ
 * lại "cho tương thích" — một luồng cũ được giữ lại vì lười là một luồng cũ sẽ
 * bị dùng.
 */
@RestController
public class OAuth2Controller {

    private final UserService users;
    private final TokenService tokens;
    private final RsaKeyProvider keys;
    private final String issuer;

    public OAuth2Controller(UserService users, TokenService tokens, RsaKeyProvider keys,
                            @Value("${app.auth.issuer:http://auth-service:8081}") String issuer) {
        this.users = users;
        this.tokens = tokens;
        this.keys = keys;
        this.issuer = issuer;
    }

    /**
     * Phát hành token.
     *
     * <p>Nhận {@code application/x-www-form-urlencoded} chứ không phải JSON —
     * đây là điều RFC 6749 §4.3.2 quy định, và một máy chủ token gửi JSON sẽ
     * làm mọi thư viện OAuth2 chuẩn thất bại ngay ở bước đầu.
     */
    @PostMapping(value = "/oauth2/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") @NotBlank String grantType,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "refresh_token", required = false) String refreshToken) {

        TokenService.TokenPair pair = switch (grantType) {
            case "password" -> {
                if (username == null || password == null) {
                    throw new TokenService.InvalidGrantException(
                            "grant_type=password cần cả username lẫn password.");
                }
                User user = users.authenticate(username, password);
                yield tokens.issueFor(user);
            }
            case "refresh_token" -> {
                if (refreshToken == null) {
                    throw new TokenService.InvalidGrantException(
                            "grant_type=refresh_token cần tham số refresh_token.");
                }
                yield tokens.refresh(refreshToken);
            }
            default -> throw new UnsupportedGrantTypeException(grantType);
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", pair.accessToken());
        body.put("token_type", pair.tokenType());
        body.put("expires_in", pair.expiresIn());
        body.put("refresh_token", pair.refreshToken());

        // RFC 6749 §5.1 yêu cầu rõ hai header này. Không phải hình thức: thiếu
        // chúng, một proxy trên đường đi được phép lưu đệm phản hồi — và một
        // access token nằm trong bộ đệm dùng chung là một access token đã bị rò.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .body(body);
    }

    /**
     * Thu hồi token (RFC 7009).
     *
     * <p><b>Luôn trả 200</b>, kể cả khi token không tồn tại. RFC 7009 §2.2 quy
     * định như vậy, và lý do đúng với mọi hệ thống: phân biệt "đã thu hồi" với
     * "không tồn tại" cho phép dò xem token nào từng có thật.
     */
    @PostMapping(value = "/oauth2/revoke",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(@RequestParam("token") String token) {
        tokens.logout(token, null, null);
        return ResponseEntity.ok().build();
    }

    /**
     * Khoá CÔNG KHAI để các service khác tự xác minh chữ ký.
     *
     * <p>Endpoint này công khai một cách có chủ ý — đó là toàn bộ mục đích của
     * JWKS. Nội dung nó trả về không phải bí mật: khoá công khai chỉ dùng để
     * <i>kiểm</i>, không dùng để <i>ký</i>. Xem
     * {@link RsaKeyProvider#publicJwkSet()} về dòng mã bảo đảm điều đó.
     */
    @GetMapping(value = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }

    /**
     * Siêu dữ liệu máy chủ uỷ quyền (RFC 8414), bản rút gọn.
     *
     * <p>Có nó thì một máy khách chỉ cần biết địa chỉ gốc là tự tìm ra mọi
     * endpoint còn lại; không có nó thì mọi địa chỉ phải được chép tay vào cấu
     * hình của từng máy khách, và một lần đổi đường dẫn là một vòng đi sửa khắp
     * nơi.
     */
    @GetMapping(value = "/.well-known/openid-configuration",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("token_endpoint", issuer + "/oauth2/token");
        body.put("revocation_endpoint", issuer + "/oauth2/revoke");
        body.put("jwks_uri", issuer + "/oauth2/jwks");
        body.put("grant_types_supported", List.of("password", "refresh_token"));
        body.put("id_token_signing_alg_values_supported", List.of("RS256"));
        return body;
    }

    /** Mã lỗi {@code invalid_grant} của RFC 6749 §5.2. */
    @ExceptionHandler(TokenService.InvalidGrantException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidGrant(
            TokenService.InvalidGrantException e) {
        return oauthError(HttpStatus.BAD_REQUEST, "invalid_grant", e.getMessage());
    }

    @ExceptionHandler(UnsupportedGrantTypeException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedGrant(
            UnsupportedGrantTypeException e) {
        return oauthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type", e.getMessage());
    }

    /**
     * Thân lỗi theo RFC 6749 §5.2 ({@code error} + {@code error_description}),
     * KHÔNG theo dạng lỗi chung của {@link GlobalExceptionHandler}.
     *
     * <p>Đây là chỗ duy nhất trong hệ thống mà hai dạng thân lỗi cùng tồn tại,
     * và nó có lý do: máy khách của endpoint này là các thư viện OAuth2, chúng
     * đọc trường {@code error} chứ không đọc {@code message}. Ép chúng theo dạng
     * riêng của dự án nghĩa là không thư viện chuẩn nào hiểu nổi lỗi trả về.
     */
    private static ResponseEntity<Map<String, Object>> oauthError(
            HttpStatus status, String code, String description) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(Map.of("error", code, "error_description",
                        description == null ? "" : description));
    }

    /** {@code grant_type} không được hỗ trợ. */
    public static class UnsupportedGrantTypeException extends RuntimeException {
        public UnsupportedGrantTypeException(String grantType) {
            super("Không hỗ trợ grant_type=" + grantType
                    + ". Hỗ trợ: password, refresh_token.");
        }
    }
}
