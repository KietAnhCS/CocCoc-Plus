package com.vnsearch.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Kho refresh token trên Redis — bản dùng ở môi trường thật.
 *
 * <h2>Bố cục khoá</h2>
 * <pre>
 *   rt:{băm}             -&gt; "{tài khoản}|{chuỗi}"  TTL = hạn refresh token
 *   rt:used:{băm}        -&gt; "1"                    TTL = 30 ngày
 *   rt:user:{tài khoản}  -&gt; SET các {băm} đang sống
 *   at:denied:{jti}      -&gt; "1"                    TTL = phần đời còn lại của access token
 * </pre>
 *
 * <h2>Vì sao lưu BĂM chứ không lưu token</h2>
 *
 * <p>Refresh token là một thông tin đăng nhập tương đương mật khẩu: ai cầm nó
 * cũng lấy được access token mới. Lưu nguyên văn nghĩa là một lần đọc trộm
 * Redis — hoặc một bản sao lưu Redis lọt ra ngoài — là chiếm được mọi phiên
 * đang mở. Lưu SHA-256 thì bản sao lưu đó vô dụng.
 *
 * <p>Dùng SHA-256 trần chứ không phải BCrypt, và đây là chỗ khác với mật khẩu:
 * BCrypt cố ý chậm để chống dò từ điển, nhưng refresh token là 32 byte ngẫu
 * nhiên — không có từ điển nào dò được, và cái chậm đó chỉ làm mỗi lần gia hạn
 * token tốn thêm 200 ms.
 *
 * <h2>Phát hiện dùng lại</h2>
 *
 * <p>Khác bản trong bộ nhớ, ở đây token đã dùng để lại dấu {@code rt:used:*}.
 * Nếu một token bị đổi lần thứ hai, ta biết chắc <b>có bản sao</b> — và huỷ
 * toàn bộ chuỗi. Người dùng thật phải đăng nhập lại; kẻ đánh cắp cũng vậy. Đây
 * là biện pháp duy nhất phát hiện được vụ đánh cắp refresh token mà không cần
 * người dùng báo cáo.
 */
@Component
@ConditionalOnProperty(name = "app.auth.refresh-store", havingValue = "redis")
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRefreshTokenStore.class);

    private static final String TOKEN_PREFIX = "rt:";
    private static final String USED_PREFIX = "rt:used:";
    private static final String USER_PREFIX = "rt:user:";
    private static final String DENIED_PREFIX = "at:denied:";

    /** Dấu "đã dùng" phải sống lâu hơn refresh token, nếu không thì hết hạn xong
     *  là dấu vết biến mất và phép phát hiện dùng lại mất tác dụng. */
    private static final Duration USED_MARKER_TTL = Duration.ofDays(30);

    private static final String SEPARATOR = "|";

    /** Trần số khoá duyệt trong một lần đếm phiên — xem {@link #activeSessionCount()}. */
    private static final int SCAN_LIMIT = 10_000;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String issue(String username, String family, Duration ttl) {
        String token = randomToken();
        String hash = hash(token);
        String chain = family == null ? UUID.randomUUID().toString() : family;

        redis.opsForValue().set(TOKEN_PREFIX + hash, username + SEPARATOR + chain, ttl);
        redis.opsForSet().add(USER_PREFIX + username, hash);
        // Tập theo người dùng cũng phải có hạn, nếu không nó phình mãi với
        // những băm đã hết hạn từ lâu — Redis xoá khoá token nhưng không tự dọn
        // tên nó khỏi tập.
        redis.expire(USER_PREFIX + username, ttl.plusDays(1));
        return token;
    }

    @Override
    public Optional<Grant> consume(String token) {
        String hash = hash(token);

        if (Boolean.TRUE.equals(redis.hasKey(USED_PREFIX + hash))) {
            String stored = redis.opsForValue().get(TOKEN_PREFIX + hash);
            String username = stored == null ? null : usernameOf(stored);
            log.warn("Refresh token bị DÙNG LẠI (tài khoản={}). Nhiều khả năng token đã bị"
                    + " sao chép — huỷ toàn bộ phiên của tài khoản này.", username);
            if (username != null) {
                revokeAllFor(username);
            }
            return Optional.empty();
        }

        String stored = redis.opsForValue().get(TOKEN_PREFIX + hash);
        if (stored == null || stored.indexOf(SEPARATOR) < 0) {
            return Optional.empty();
        }

        // Đánh dấu ĐÃ DÙNG trước khi trả về, không phải sau. setIfAbsent là
        // phép toán nguyên tử của Redis, nên khi hai request song song cùng
        // mang một token, chỉ đúng MỘT request đi tiếp. Đánh dấu sau khi trả về
        // sẽ để lọt cả hai — và đó chính là lỗ hổng mà phép xoay vòng token
        // sinh ra để bịt.
        Boolean firstUse = redis.opsForValue()
                .setIfAbsent(USED_PREFIX + hash, "1", USED_MARKER_TTL);
        if (!Boolean.TRUE.equals(firstUse)) {
            return Optional.empty();
        }

        String username = usernameOf(stored);
        String family = stored.substring(stored.indexOf(SEPARATOR) + 1);
        redis.delete(TOKEN_PREFIX + hash);
        redis.opsForSet().remove(USER_PREFIX + username, hash);
        return Optional.of(new Grant(username, family));
    }

    @Override
    public void revoke(String token) {
        String hash = hash(token);
        String stored = redis.opsForValue().get(TOKEN_PREFIX + hash);
        redis.delete(TOKEN_PREFIX + hash);
        if (stored != null && stored.indexOf(SEPARATOR) >= 0) {
            redis.opsForSet().remove(USER_PREFIX + usernameOf(stored), hash);
        }
    }

    @Override
    public int revokeAllFor(String username) {
        Set<String> hashes = redis.opsForSet().members(USER_PREFIX + username);
        if (hashes == null || hashes.isEmpty()) {
            return 0;
        }
        for (String hash : hashes) {
            redis.delete(TOKEN_PREFIX + hash);
        }
        redis.delete(USER_PREFIX + username);
        return hashes.size();
    }

    @Override
    public void denyAccessToken(String tokenId, Duration remainingLifetime) {
        if (remainingLifetime.isNegative() || remainingLifetime.isZero()) {
            return;
        }
        // TTL bằng đúng phần đời còn lại: danh sách thu hồi tự dọn, và nó không
        // bao giờ lớn hơn số token phát ra trong 15 phút gần nhất.
        redis.opsForValue().set(DENIED_PREFIX + tokenId, "1", remainingLifetime);
    }

    @Override
    public boolean isAccessTokenDenied(String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(DENIED_PREFIX + tokenId));
    }

    /**
     * Đếm refresh token còn sống bằng SCAN + SCARD.
     *
     * <p><b>Vì sao KHÔNG dùng {@code KEYS rt:user:*}.</b> {@code KEYS} chặn
     * Redis trong suốt thời gian duyệt — với một tiến trình đơn luồng phục vụ
     * cả tám service, đó là một lần dừng toàn hệ thống. {@code SCAN} duyệt theo
     * lô và nhả quyền giữa các lô.
     *
     * <p><b>Vì sao có trần {@link #SCAN_LIMIT}.</b> Con số này chỉ để hiện trên
     * bảng điều khiển. Không có trần thì với một triệu người dùng, một lần mở
     * bảng điều khiển kéo về một triệu khoá — trả giá lớn cho một con số mà
     * người xem chỉ liếc qua. Chạm trần thì trả về đúng con số đếm được và ghi
     * log; hiện "hơn 10.000" vẫn trả lời đúng câu hỏi mà người vận hành đang
     * hỏi.
     */
    @Override
    public int activeSessionCount() {
        int total = 0;
        int scanned = 0;
        ScanOptions options = ScanOptions.scanOptions().match(USER_PREFIX + "*").count(256).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && scanned < SCAN_LIMIT) {
                Long size = redis.opsForSet().size(cursor.next());
                total += size == null ? 0 : size.intValue();
                scanned++;
            }
        }
        if (scanned >= SCAN_LIMIT) {
            log.info("Đếm phiên dừng ở trần {} khoá — con số trả về là cận dưới.", SCAN_LIMIT);
        }
        return total;
    }

    private static String usernameOf(String stored) {
        return stored.substring(0, stored.indexOf(SEPARATOR));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256 — không thể xảy ra", e);
        }
    }
}
