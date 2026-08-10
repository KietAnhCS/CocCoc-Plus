package com.vnsearch.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Đăng ký, đăng nhập, đổi vai trò — toàn bộ luật về tài khoản nằm ở đây.
 *
 * <h2>Băm mật khẩu bằng BCrypt, không phải SHA-256</h2>
 *
 * <p>Đây là chỗ dễ làm sai nhất và hậu quả nặng nhất, nên nói kỹ. SHA-256 được
 * thiết kế để <b>nhanh</b> — một GPU phổ thông băm hàng tỉ chuỗi mỗi giây, nên
 * một tệp hash SHA-256 bị lộ đồng nghĩa với mọi mật khẩu yếu bị phá trong vài
 * phút. BCrypt được thiết kế để <b>chậm có kiểm soát</b>: tham số chi phí (ở
 * đây là {@value #BCRYPT_COST}) nhân đôi thời gian mỗi khi tăng một đơn vị, và
 * nó tự sinh <b>salt</b> nhúng ngay trong chuỗi hash.
 *
 * <p>Salt là thứ chặn <i>bảng tra ngược</i>: không có salt, hai người dùng đặt
 * cùng mật khẩu sẽ có cùng hash, và kẻ tấn công phá một lần được cả hai — lại
 * còn nhận ra ngay là họ trùng mật khẩu.
 *
 * <p>Chi phí {@value #BCRYPT_COST} tốn khoảng 100–250 ms mỗi lần băm trên máy
 * để bàn. Đó là <i>cố ý</i>: chi phí này không đáng kể với một người đăng nhập
 * (một lần mỗi phiên) nhưng nhân với hàng tỉ lần thử thì phá mật khẩu ngoại
 * tuyến trở nên vô vọng.
 *
 * <h2>Chống dò mật khẩu: khoá tạm theo tài khoản</h2>
 *
 * <p>{@code RateLimitFilter} giới hạn theo <b>địa chỉ</b>, nên nó không chặn
 * được kiểu tấn công ngược lại: một mạng botnet thử <i>một</i> mật khẩu phổ
 * biến trên <i>hàng nghìn</i> tài khoản, mỗi địa chỉ chỉ gửi vài request. Bộ
 * đếm ở đây giới hạn theo <b>tài khoản</b>, nên nó bịt đúng chỗ kia bỏ sót.
 *
 * <p>Khoá <i>tạm</i> ({@value #LOCKOUT_MINUTES} phút) chứ không khoá vĩnh
 * viễn: khoá vĩnh viễn biến một cuộc dò mật khẩu thành một cuộc <b>tấn công từ
 * chối dịch vụ</b> — kẻ xấu chỉ cần gõ sai vài lần trên tài khoản của người
 * khác là khoá được họ ra ngoài mãi mãi.
 *
 * <h2>Thông báo lỗi cố tình mơ hồ</h2>
 *
 * <p>Sai tên và sai mật khẩu trả về <b>cùng một thông báo</b>. Phân biệt hai
 * ca ("tài khoản không tồn tại" / "mật khẩu sai") biến trang đăng nhập thành
 * một công cụ <i>liệt kê tài khoản</i>: kẻ tấn công thử một danh sách tên và
 * biết chính xác tên nào có thật, rồi mới tập trung dò mật khẩu vào đó.
 */
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Tham số chi phí BCrypt — xem Javadoc lớp. */
    public static final int BCRYPT_COST = 12;

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCKOUT_MINUTES = 15;

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 200;

    /**
     * Tên tài khoản: chữ, số, gạch dưới, gạch ngang, dấu chấm; 3–32 ký tự.
     *
     * <p>Danh sách CHO PHÉP chứ không phải danh sách cấm. Cấm thì luôn thiếu
     * một ký tự nào đó chưa ai nghĩ tới — và tên tài khoản đi vào tên tệp, vào
     * log, vào JSON, nên mỗi ký tự lạ là một chỗ có thể diễn giải sai.
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,32}$");

    /** Lỗi nghiệp vụ của tầng tài khoản. Controller dịch sang mã HTTP. */
    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }

    /** Sai thông tin đăng nhập, hoặc tài khoản bị khoá tạm. */
    public static class InvalidCredentialsException extends AuthException {
        public InvalidCredentialsException(String message) {
            super(message);
        }
    }

    private final UserStore store;
    private final PasswordEncoder encoder;
    private final Clock clock;

    /** username -> số lần sai liên tiếp và thời điểm hết khoá. */
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    public UserService(UserStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        this.encoder = new BCryptPasswordEncoder(BCRYPT_COST);
    }

    // ------------------------------------------------------------------
    // Đăng ký
    // ------------------------------------------------------------------

    /**
     * Tạo tài khoản mới với vai trò {@link Role#USER}.
     *
     * <p><b>Không có cách nào để một người tự đăng ký thành ADMIN.</b> Vai trò
     * là tham số của {@link #createAccount} — hàm nội bộ — chứ không phải của
     * hàm này. Nhận vai trò từ thân request đăng ký là lỗ hổng leo thang quyền
     * kinh điển: chỉ cần thêm {@code "role":"ADMIN"} vào JSON là xong.
     */
    public User register(String username, String password) throws IOException {
        return createAccount(username, password, Role.USER);
    }

    /** Dùng cho tài khoản mồi và cho công cụ quản trị — vai trò do NƠI GỌI quyết định. */
    public User createAccount(String username, String password, Role role) throws IOException {
        String normalized = normalize(username);
        validateUsername(normalized);
        validatePassword(password);

        if (store.find(normalized).isPresent()) {
            // Ở đây thì nói thật là tên đã tồn tại: người đăng ký cần biết để
            // chọn tên khác. Đúng là nó cho phép dò xem tên nào đã có — nhưng
            // một trang đăng ký không nói điều đó thì không dùng được, và bản
            // thân việc biết một tên đã tồn tại không mở được cánh cửa nào.
            throw new AuthException("Tên tài khoản đã tồn tại: " + normalized);
        }

        User user = new User(normalized, encoder.encode(password), role,
                true, clock.instant(), null);
        store.save(user);
        log.info("Da tao tai khoan '{}' voi vai tro {}", forLog(normalized), role);
        return user;
    }

    // ------------------------------------------------------------------
    // Đăng nhập
    // ------------------------------------------------------------------

    /**
     * Kiểm tra thông tin đăng nhập.
     *
     * @throws InvalidCredentialsException khi sai tên, sai mật khẩu, tài khoản
     *                                     bị vô hiệu hoá, hoặc đang bị khoá tạm
     */
    public User authenticate(String username, String password) {
        String normalized = normalize(username);
        Instant now = clock.instant();

        Attempts attempts = failures.get(normalized);
        if (attempts != null && attempts.lockedUntil != null && now.isBefore(attempts.lockedUntil)) {
            long minutesLeft = Math.max(1,
                    Duration.between(now, attempts.lockedUntil).toMinutes() + 1);
            throw new InvalidCredentialsException(
                    "Tài khoản đang bị khoá tạm do sai nhiều lần. Thử lại sau "
                            + minutesLeft + " phút.");
        }

        Optional<User> found = store.find(normalized);
        if (found.isEmpty()) {
            // Vẫn băm một chuỗi giả để thời gian phản hồi của "tên không tồn
            // tại" giống "mật khẩu sai". Không làm vậy thì ca đầu trả về gần
            // như tức thì còn ca sau tốn ~200 ms, và chênh lệch đó tự nó là
            // một máy dò tên tài khoản — thông báo mơ hồ ở trên thành vô nghĩa.
            encoder.encode(password == null ? "" : password);
            recordFailure(normalized, now);
            throw new InvalidCredentialsException("Tên tài khoản hoặc mật khẩu không đúng.");
        }

        User user = found.get();
        if (!encoder.matches(password == null ? "" : password, user.passwordHash())) {
            recordFailure(normalized, now);
            log.warn("Dang nhap that bai cho '{}'", forLog(normalized));
            throw new InvalidCredentialsException("Tên tài khoản hoặc mật khẩu không đúng.");
        }
        if (!user.enabled()) {
            // Thông báo KHÁC ở đây là đúng: người này đã chứng minh biết mật
            // khẩu, nên nói cho họ biết vì sao vẫn không vào được.
            throw new InvalidCredentialsException("Tài khoản đã bị vô hiệu hoá.");
        }

        failures.remove(normalized);
        User updated = user.withLastLoginAt(now);
        try {
            store.save(updated);
        } catch (IOException e) {
            // Không ghi được mốc đăng nhập KHÔNG được phép chặn đăng nhập:
            // đây là thông tin phụ trợ, còn việc xác thực thì đã xong rồi.
            log.warn("Khong ghi duoc moc dang nhap cho '{}': {}", forLog(normalized), e.toString());
        }
        return updated;
    }

    private void recordFailure(String username, Instant now) {
        Attempts attempts = failures.computeIfAbsent(username, key -> new Attempts());
        synchronized (attempts) {
            attempts.count++;
            if (attempts.count >= MAX_FAILED_ATTEMPTS) {
                attempts.lockedUntil = now.plus(Duration.ofMinutes(LOCKOUT_MINUTES));
                attempts.count = 0;
                log.warn("Khoa tam tai khoan '{}' trong {} phut sau {} lan sai",
                        forLog(username), LOCKOUT_MINUTES, MAX_FAILED_ATTEMPTS);
            }
        }
    }

    /**
     * Đổi mật khẩu.
     *
     * <h2>Vì sao vẫn phải nhập mật khẩu HIỆN TẠI dù đã đăng nhập</h2>
     *
     * <p>Nghe thừa — người gọi đã có token hợp lệ rồi. Nhưng đó chính là kịch
     * bản cần chặn: <b>một chiếc token bị đánh cắp</b> (máy bỏ quên không khoá,
     * token bị lấy qua XSS). Không hỏi mật khẩu cũ thì kẻ cầm token đổi được
     * mật khẩu, và thao tác đó <i>khoá chính chủ nhân ra ngoài</i> — biến một
     * phiên bị lộ tạm thời thành mất tài khoản vĩnh viễn.
     *
     * <p>Mật khẩu hiện tại là thứ mà token không chứa, nên hỏi nó biến bước này
     * thành một lần <i>xác thực lại</i> thật sự.
     *
     * <p>Lần sai ở đây cũng tính vào bộ đếm khoá tạm, cùng lý do như đăng nhập:
     * nếu không, endpoint này thành một máy dò mật khẩu không giới hạn cho bất
     * kỳ ai có một token.
     */
    public User changePassword(String username, String currentPassword, String newPassword)
            throws IOException {
        String normalized = normalize(username);
        Instant now = clock.instant();

        User user = store.find(normalized).orElseThrow(() -> new AuthException(
                // Chu thich cho ca dung khoa API: principal luc do la
                // "admin-api-key", khong co tai khoan nao dung sau.
                "Không có tài khoản '" + normalized + "'. Phiên hiện tại có thể đang dùng"
                        + " khoá quản trị tĩnh — khoá đó không có mật khẩu để đổi."));

        Attempts attempts = failures.get(normalized);
        if (attempts != null && attempts.lockedUntil != null && now.isBefore(attempts.lockedUntil)) {
            throw new InvalidCredentialsException(
                    "Tài khoản đang bị khoá tạm do sai nhiều lần. Thử lại sau ít phút.");
        }

        if (!encoder.matches(currentPassword == null ? "" : currentPassword, user.passwordHash())) {
            recordFailure(normalized, now);
            throw new InvalidCredentialsException("Mật khẩu hiện tại không đúng.");
        }

        validatePassword(newPassword);
        if (encoder.matches(newPassword, user.passwordHash())) {
            // Chan mot cach lich su: doi mat khau thanh chinh no khong phai loi
            // ky thuat, nhung no cung khong lam duoc viec ma nguoi dung dinh lam.
            throw new AuthException("Mật khẩu mới phải khác mật khẩu hiện tại.");
        }

        failures.remove(normalized);
        User updated = user.withPasswordHash(encoder.encode(newPassword));
        store.save(updated);
        log.info("Tai khoan '{}' da doi mat khau", forLog(normalized));
        return updated;
    }

    // ------------------------------------------------------------------
    // Quản trị
    // ------------------------------------------------------------------

    public List<User> findAll() {
        return store.findAll();
    }

    public Optional<User> find(String username) {
        return store.find(normalize(username));
    }

    public User changeRole(String username, Role role) throws IOException {
        User user = store.find(normalize(username))
                .orElseThrow(() -> new AuthException("Không có tài khoản: " + username));
        User updated = user.withRole(role);
        store.save(updated);
        log.info("Doi vai tro cua '{}' thanh {}", forLog(updated.username()), role);
        return updated;
    }

    public User setEnabled(String username, boolean enabled) throws IOException {
        User user = store.find(normalize(username))
                .orElseThrow(() -> new AuthException("Không có tài khoản: " + username));
        User updated = user.withEnabled(enabled);
        store.save(updated);
        return updated;
    }

    /**
     * Xoá hẳn một tài khoản.
     *
     * <p><b>Khác {@link #setEnabled} ở chỗ không hồi lại được.</b> Vô hiệu hoá
     * giữ nguyên bản ghi (tên vẫn bị chiếm, bật lại là dùng tiếp); xoá thì mất
     * cả tên lẫn lịch sử đăng nhập, và một người khác đăng ký lại đúng tên đó
     * sẽ là <i>một tài khoản hoàn toàn mới</i> chứ không phải người cũ. Với số
     * liệu sử dụng đã ghi theo tên, điều này có nghĩa là hai người khác nhau
     * gộp chung một dòng trong bảng xếp hạng — nên vô hiệu hoá mới là mặc định
     * đúng, còn xoá dành cho việc dọn tài khoản rác.
     *
     * <p>Cũng xoá luôn bộ đếm khoá tạm: giữ lại thì tên vừa xoá còn mang theo
     * một "án treo" vô hình sang chủ mới.
     *
     * @return {@code true} nếu có tài khoản để xoá
     */
    public boolean delete(String username) throws IOException {
        String normalized = normalize(username);
        boolean removed = store.delete(normalized);
        if (removed) {
            failures.remove(normalized);
            log.info("Da xoa tai khoan '{}'", forLog(normalized));
        }
        return removed;
    }

    /** Số tài khoản — dùng để quyết định có tạo tài khoản mồi hay không. */
    public int count() {
        return store.count();
    }

    // ------------------------------------------------------------------
    // Kiểm tra đầu vào
    // ------------------------------------------------------------------

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Lam sach mot ten tai khoan TRUOC KHI ghi vao log.
     *
     * <h2>Vi sao can, du da co USERNAME_PATTERN</h2>
     *
     * <p>Ten di vao {@link #register} bi ep qua bieu thuc chinh quy, nhung ten
     * di vao {@link #changePassword}, {@link #changeRole} hay {@link #delete}
     * den tu <b>tham so duong dan</b> va chi qua {@link #normalize} (cat khoang
     * trang + ha chu thuong). Mot ten nhu {@code "a%0A2026-01-01 ERROR Da xoa
     * toan bo tai khoan"} se tao ra mot DONG LOG GIA hoan chinh — ke tan cong
     * viet duoc vao nhat ky cua chinh he thong dang ghi lai hanh vi cua ho.
     *
     * <p>Day la lo hong <i>log injection</i>, va no nguy hiem dung o cho khong
     * ai ngo: nhat ky la thu nguoi van hanh tin tuong nhat khi dieu tra su co.
     *
     * <p>Cat luon do dai: mot ten dai vai nghin ky tu khong lam hong gi nhung
     * lam trang log khong doc duoc.
     */
    private static String forLog(String username) {
        if (username == null) {
            return "(rong)";
        }
        String safe = username.replaceAll("[^a-zA-Z0-9._-]", "?");
        return safe.length() <= 64 ? safe : safe.substring(0, 64) + "...";
    }

    private static void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new AuthException(
                    "Tên tài khoản phải dài 3–32 ký tự và chỉ gồm chữ, số, dấu chấm, gạch ngang,"
                            + " gạch dưới.");
        }
    }

    /**
     * Luật mật khẩu: chỉ ràng buộc <b>độ dài</b>.
     *
     * <p>Không bắt "phải có chữ hoa, số và ký tự đặc biệt". Những luật đó nghe
     * chặt nhưng thực tế đẩy người dùng tới đúng một khuôn dễ đoán
     * ({@code Password1!}), trong khi một cụm bốn từ ngẫu nhiên dài 20 ký tự
     * vừa dễ nhớ hơn vừa khó phá hơn nhiều lần. Khuyến nghị của NIST từ 2017 là
     * ưu tiên độ dài và bỏ các luật thành phần.
     *
     * <p>Trần {@value #MAX_PASSWORD_LENGTH} ký tự là để chặn tấn công làm nghẽn
     * bằng cách gửi một chuỗi khổng lồ cho BCrypt băm. (BCrypt vốn chỉ dùng 72
     * byte đầu, nên chuỗi dài hơn cũng không tăng độ mạnh.)
     */
    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AuthException(
                    "Mật khẩu phải dài ít nhất " + MIN_PASSWORD_LENGTH + " ký tự.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new AuthException(
                    "Mật khẩu tối đa " + MAX_PASSWORD_LENGTH + " ký tự.");
        }
    }

    /** Bộ đếm sai liên tiếp cho một tài khoản. */
    private static final class Attempts {
        private int count;
        private Instant lockedUntil;
    }
}
