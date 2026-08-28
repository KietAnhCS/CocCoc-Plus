package com.vnsearch.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.List;
import java.util.UUID;

/**
 * Cặp khoá RSA dùng để ký access token, và tập khoá công khai (JWKS) mà các
 * service khác tải về để tự xác minh.
 *
 * <h2>Khoá đến từ đâu</h2>
 *
 * <p>Hai chế độ, và ranh giới giữa chúng là ranh giới giữa "chạy thử" và
 * "chạy thật":
 *
 * <pre>
 *   app.auth.jwk-path CÓ giá trị   đọc khoá từ tệp JSON (JWK)   → môi trường thật
 *   app.auth.jwk-path để TRỐNG     sinh khoá mới lúc khởi động  → dev và test
 * </pre>
 *
 * <p><b>Vì sao chế độ sinh mới không dùng được ở môi trường thật.</b> Mỗi lần
 * khởi động lại sinh một khoá khác, nên mọi access token đang lưu hành lập tức
 * hỏng — người dùng bị đá ra hàng loạt sau mỗi lần triển khai. Và với nhiều
 * bản sao chạy song song, mỗi bản sao ký bằng một khoá riêng: token do bản sao
 * A phát ra bị bản sao B từ chối, tạo ra lỗi 401 <i>ngẫu nhiên</i> — đúng loại
 * lỗi tốn nhiều ngày nhất để tìm ra.
 *
 * <p><b>Vì sao vẫn giữ chế độ sinh mới.</b> Để {@code docker compose up} và
 * {@code mvnw test} chạy được ngay mà không phải sinh khoá trước. Ranh giới
 * được canh bằng một dòng cảnh báo rõ ràng lúc khởi động, chứ không bằng niềm
 * tin rằng ai cũng đọc tài liệu.
 *
 * <h2>Vì sao RSA 2048 chứ không phải HMAC</h2>
 *
 * <p>HMAC (HS256) dùng CÙNG một bí mật để ký và để kiểm — nghĩa là mỗi service
 * muốn kiểm token đều phải cầm thứ dùng để <i>phát hành</i> token. Một service
 * bị chiếm là toàn hệ thống bị chiếm. Với RSA, bảy service kia chỉ cầm khoá
 * công khai: đọc được, không giả được.
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private static final int KEY_SIZE = 2048;

    private final RSAKey signingKey;

    public RsaKeyProvider(@Value("${app.auth.jwk-path:}") String jwkPath) throws IOException {
        this.signingKey = (jwkPath == null || jwkPath.isBlank())
                ? generateEphemeral()
                : loadFrom(Path.of(jwkPath));
    }

    /** Khoá riêng để ký. Không bao giờ rời khỏi tiến trình này. */
    public RSAPrivateKey privateKey() {
        try {
            return signingKey.toRSAPrivateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Khoá JWK không chứa phần riêng — không ký được", e);
        }
    }

    public RSAPublicKey publicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Khoá JWK hỏng", e);
        }
    }

    /** Định danh khoá, đi vào header {@code kid} của mỗi token. */
    public String keyId() {
        return signingKey.getKeyID();
    }

    /**
     * Tập khoá CÔNG KHAI, phơi ra ở {@code /oauth2/jwks}.
     *
     * <p>{@link RSAKey#toPublicJWK()} là dòng quan trọng nhất trong lớp này:
     * thiếu nó, khoá riêng được tuần tự hoá ra và <b>phát công khai qua HTTP</b>.
     * Nimbus không cảnh báo gì cả, và tệp JSON trả về trông vẫn đúng dạng JWKS
     * với người đọc lướt. Đây là loại lỗi mà một bài test phải canh — xem
     * {@code RsaKeyProviderTest}.
     */
    public JWKSet publicJwkSet() {
        return new JWKSet(List.of(signingKey.toPublicJWK()));
    }

    private RSAKey loadFrom(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        try {
            RSAKey key = RSAKey.parse(json);
            if (!key.isPrivate()) {
                throw new IllegalStateException("Tệp " + path
                        + " chỉ chứa khoá công khai; auth-service cần khoá riêng để ký.");
            }
            log.info("Đã nạp khoá ký JWT từ {} (kid={})", path, key.getKeyID());
            return key;
        } catch (ParseException e) {
            throw new IllegalStateException("Không đọc được JWK ở " + path, e);
        }
    }

    private RSAKey generateEphemeral() {
        KeyPair pair = newKeyPair();
        String kid = UUID.randomUUID().toString();
        log.warn("Đang dùng khoá ký JWT SINH TẠM (kid={}). Mọi token sẽ hỏng sau lần khởi"
                + " động lại kế tiếp, và nhiều bản sao sẽ từ chối token của nhau."
                + " Chỉ dùng cho dev/test — môi trường thật phải đặt app.auth.jwk-path.", kid);
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .build();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có RSA — không thể xảy ra", e);
        }
    }
}
