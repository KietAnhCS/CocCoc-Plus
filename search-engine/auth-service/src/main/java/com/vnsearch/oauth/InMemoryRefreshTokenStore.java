package com.vnsearch.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kho refresh token nằm trong bộ nhớ tiến trình.
 *
 * <p><b>Chỉ dùng cho test và cho lần chạy thử đầu tiên</b>, và lớp này nói
 * thẳng điều đó bằng một dòng cảnh báo lúc khởi động. Hai giới hạn không thể
 * vá được: khởi động lại là mất sạch (mọi người bị đăng xuất), và với nhiều
 * bản sao thì mỗi bản sao có một kho riêng nên refresh token cấp ở bản sao A
 * bị bản sao B từ chối.
 *
 * <p><b>Vì sao vẫn tồn tại thay vì bắt mọi thứ dùng Redis.</b> Bắt buộc phải
 * có Redis mới chạy được dòng mã đầu tiên là một rào cản thật: {@code mvnw
 * test} sẽ cần một container, và người muốn thử dự án trong năm phút sẽ bỏ
 * cuộc. Ranh giới rõ ràng cộng một cảnh báo to là đánh đổi đúng.
 */
@Component
@ConditionalOnProperty(name = "app.auth.refresh-store", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRefreshTokenStore.class);

    private record Entry(String username, String family, Instant expiresAt) {
    }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> deniedAccessTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public InMemoryRefreshTokenStore() {
        this(Clock.systemUTC());
    }

    InMemoryRefreshTokenStore(Clock clock) {
        this.clock = clock;
        log.warn("Refresh token đang lưu TRONG BỘ NHỚ tiến trình. Khởi động lại sẽ đăng xuất"
                + " mọi người, và nhiều bản sao sẽ không nhận token của nhau."
                + " Môi trường thật phải đặt app.auth.refresh-store=redis.");
    }

    @Override
    public String issue(String username, String family, Duration ttl) {
        purgeExpired();
        String token = randomToken();
        tokens.put(token, new Entry(username,
                family == null ? UUID.randomUUID().toString() : family,
                clock.instant().plus(ttl)));
        return token;
    }

    @Override
    public Optional<Grant> consume(String token) {
        Entry entry = tokens.remove(token);
        if (entry == null) {
            // Không tồn tại. Có thể là token bịa, có thể là token ĐÃ DÙNG rồi —
            // và bản cài này không phân biệt được, vì nó xoá hẳn khi dùng. Bản
            // Redis giữ lại dấu vết để phát hiện dùng lại; xem
            // RedisRefreshTokenStore. Ở đây chỉ từ chối.
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Grant(entry.username(), entry.family()));
    }

    @Override
    public void revoke(String token) {
        tokens.remove(token);
    }

    @Override
    public int revokeAllFor(String username) {
        int before = tokens.size();
        tokens.entrySet().removeIf(entry -> entry.getValue().username().equals(username));
        return before - tokens.size();
    }

    @Override
    public void denyAccessToken(String tokenId, Duration remainingLifetime) {
        if (remainingLifetime.isNegative() || remainingLifetime.isZero()) {
            return; // đã hết hạn rồi thì không còn gì để chặn
        }
        deniedAccessTokens.put(tokenId, clock.instant().plus(remainingLifetime));
    }

    @Override
    public boolean isAccessTokenDenied(String tokenId) {
        Instant until = deniedAccessTokens.get(tokenId);
        if (until == null) {
            return false;
        }
        if (until.isBefore(clock.instant())) {
            deniedAccessTokens.remove(tokenId);
            return false;
        }
        return true;
    }

    @Override
    public int activeSessionCount() {
        purgeExpired();
        return tokens.size();
    }

    /**
     * Dọn token đã hết hạn.
     *
     * <p>Gọi lúc CẤP token mới chứ không bằng một luồng nền định kỳ: một luồng
     * nền là một thứ nữa phải khởi động, phải dừng đúng lúc, và phải nhớ tồn
     * tại. Dọn kèm theo thao tác ghi thì tần suất dọn tự tỉ lệ thuận với tần
     * suất sinh rác — đúng thứ mình muốn — và không có gì để quên tắt.
     */
    private void purgeExpired() {
        Instant now = clock.instant();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        deniedAccessTokens.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
