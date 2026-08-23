package com.vnsearch.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kho phiên đăng nhập: <b>token ngẫu nhiên</b> → thông tin người dùng.
 *
 * <h2>Vì sao token mờ (opaque) chứ không phải JWT</h2>
 *
 * <p>JWT là lựa chọn mặc định của nhiều dự án, nên việc <i>không</i> chọn nó
 * cần lý do rõ ràng:
 *
 * <table border="1">
 *   <caption>JWT và token mờ, so trên đúng bài toán này</caption>
 *   <tr><th></th><th>JWT</th><th>Token mờ (chọn cái này)</th></tr>
 *   <tr><td>Xác minh</td>
 *       <td>Không cần trạng thái — mọi máy chủ tự kiểm chữ ký</td>
 *       <td>Tra một bảng băm trong bộ nhớ</td></tr>
 *   <tr><td><b>Đăng xuất</b></td>
 *       <td><b>Không làm được ngay.</b> Token đã phát vẫn hợp lệ tới lúc hết
 *           hạn; muốn thu hồi phải có danh sách đen — tức là lại cần trạng
 *           thái, đúng thứ vừa tránh</td>
 *       <td>Xoá một dòng, hiệu lực tức thì</td></tr>
 *   <tr><td>Đổi vai trò</td>
 *       <td>Vai trò nằm trong token: hạ quyền một người không có tác dụng cho
 *           tới khi token cũ hết hạn</td>
 *       <td>Có tác dụng ở request tiếp theo</td></tr>
 *   <tr><td>Nhiều bản sao máy chủ</td>
 *       <td>Chạy được ngay</td>
 *       <td>Cần kho dùng chung (Redis) — <i>chưa</i> cần ở đây</td></tr>
 * </table>
 *
 * <p>Cái lợi duy nhất của JWT — xác minh không cần trạng thái — chỉ có giá trị
 * khi có nhiều dịch vụ hoặc nhiều bản sao. Hệ thống này là <b>một</b> tiến
 * trình phục vụ <b>một</b> ứng dụng khách. Đổi lại, ta mất khả năng thu hồi
 * tức thì và khả năng hạ quyền tức thì — hai thứ mà một trang quản trị điều
 * khiển được crawler thì thật sự cần.
 *
 * <p>Hệ quả phải chấp nhận, nói thẳng: <b>khởi động lại máy chủ là mọi người
 * bị đăng xuất.</b> Đúng chỗ để sửa khi cần là thay bảng băm này bằng Redis,
 * không phải đổi sang JWT.
 *
 * <h2>Token được sinh thế nào</h2>
 *
 * <p>256 bit từ {@link SecureRandom}, mã Base64-URL. Không dùng
 * {@code java.util.Random} hay {@code UUID.randomUUID()} làm token bí mật:
 * {@code Random} là bộ sinh giả ngẫu nhiên đoán được trạng thái sau vài mẫu,
 * còn UUIDv4 tuy dùng nguồn an toàn nhưng chỉ có 122 bit và mang cấu trúc cố
 * định. 256 bit ngẫu nhiên thật thì không gian tìm kiếm lớn tới mức dò là điều
 * không tưởng.
 *
 * <p>An toàn đa luồng hoàn toàn.
 */
public class SessionStore {

    /** Phiên sống tối đa bấy nhiêu giờ kể từ lúc đăng nhập. */
    public static final int SESSION_HOURS = 12;

    /**
     * Trần số phiên cùng lúc.
     *
     * <p>Mỗi lần đăng nhập thành công tạo một dòng, và không có gì bắt người
     * dùng phải đăng xuất. Không có trần thì bảng này lớn mãi theo số lần đăng
     * nhập — chậm nhưng vẫn là một lỗ rò bộ nhớ. Chạm trần thì dọn phiên hết
     * hạn trước; vẫn đầy thì từ chối phiên mới (đăng nhập báo lỗi rõ ràng, chứ
     * không âm thầm đẩy một người đang hoạt động ra ngoài).
     */
    public static final int MAX_SESSIONS = 10_000;

    private static final int TOKEN_BYTES = 32;

    /** Một phiên đang mở. */
    public record Session(String username, Role role, Instant createdAt, Instant expiresAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public SessionStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * Mở phiên mới.
     *
     * @return token — <b>giá trị này không bao giờ được ghi log</b>; nó tương
     *         đương mật khẩu cho tới lúc hết hạn
     */
    public String open(User user) {
        Instant now = clock.instant();
        if (sessions.size() >= MAX_SESSIONS) {
            purgeExpired(now);
            if (sessions.size() >= MAX_SESSIONS) {
                throw new IllegalStateException(
                        "Đã đạt giới hạn " + MAX_SESSIONS + " phiên đăng nhập cùng lúc.");
            }
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        sessions.put(token, new Session(user.username(), user.role(), now,
                now.plus(Duration.ofHours(SESSION_HOURS))));
        return token;
    }

    /**
     * Tra phiên theo token.
     *
     * <p>Phiên hết hạn bị <b>xoá ngay khi bị tra tới</b> thay vì chờ một tác vụ
     * dọn dẹp định kỳ: cách này không cần thêm luồng nào, và những phiên không
     * ai dùng tới thì cũng không tốn gì ngoài một dòng bảng — đã có
     * {@link #MAX_SESSIONS} chặn.
     */
    public Optional<Session> lookup(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Đăng xuất. Hiệu lực tức thì — đây là lý do chính chọn token mờ. */
    public boolean revoke(String token) {
        return token != null && sessions.remove(token) != null;
    }

    /**
     * Đóng mọi phiên của một tài khoản.
     *
     * <p>Cần khi hạ vai trò hoặc vô hiệu hoá tài khoản: nếu không, người vừa
     * bị hạ quyền vẫn giữ một phiên mang vai trò cũ cho tới lúc hết hạn.
     */
    public int revokeAllFor(String username) {
        if (username == null) {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().username().equalsIgnoreCase(username)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Đóng mọi phiên của một tài khoản <b>trừ một phiên</b>.
     *
     * <p>Dành cho việc đổi mật khẩu. Người vừa đổi mật khẩu thành công không
     * nên bị đá ra khỏi chính thiết bị họ đang ngồi — nhưng mọi phiên khác thì
     * phải chết, vì lý do phổ biến nhất để đổi mật khẩu là <i>nghi ngờ có
     * người khác đang dùng tài khoản của mình</i>. Đổi mật khẩu mà không đóng
     * phiên khác thì kẻ kia vẫn ở trong, và người dùng tưởng mình đã an toàn.
     *
     * @param keepToken phiên được giữ lại; {@code null} nghĩa là đóng tất cả
     * @return số phiên đã đóng
     */
    public int revokeAllForExcept(String username, String keepToken) {
        if (username == null) {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> entry = it.next();
            if (entry.getValue().username().equalsIgnoreCase(username)
                    && !entry.getKey().equals(keepToken)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Số phiên còn hiệu lực — cho bảng điều khiển quản trị. */
    public int activeCount() {
        purgeExpired(clock.instant());
        return sessions.size();
    }

    /** Danh sách phiên còn hiệu lực, KHÔNG kèm token. */
    public List<Session> activeSessions() {
        purgeExpired(clock.instant());
        return List.copyOf(sessions.values());
    }

    private void purgeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }
}
