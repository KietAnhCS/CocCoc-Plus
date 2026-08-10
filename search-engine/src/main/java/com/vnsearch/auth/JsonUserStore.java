package com.vnsearch.auth;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link UserStore} lưu tài khoản trong <b>một tệp JSON</b>, giữ bản sao trong
 * bộ nhớ để đọc không chạm đĩa.
 *
 * <h2>Vì sao đọc từ bộ nhớ, ghi xuống đĩa</h2>
 *
 * <p>Đọc xảy ra ở <b>mỗi</b> request có xác thực; ghi chỉ xảy ra khi đăng ký,
 * đổi vai trò, hoặc đăng nhập thành công. Tỉ lệ đọc/ghi hàng nghìn trên một,
 * nên đọc phải là một phép tra bảng băm, không phải một lần mở tệp.
 *
 * <h2>Ghi nguyên tử: tệp tạm rồi đổi tên</h2>
 *
 * <p>Ghi thẳng đè lên {@code users.json} có một cửa sổ chết người: nếu tiến
 * trình bị giết (hoặc mất điện) giữa lúc ghi, tệp còn lại là JSON <b>cụt</b> —
 * và lần khởi động sau không đọc được nó, tức là <b>mất toàn bộ tài khoản</b>.
 * Ghi ra {@code users.json.tmp} rồi {@code move} đè lên: phép đổi tên là
 * nguyên tử ở mức hệ thống tệp, nên tệp đích luôn hoặc là bản cũ nguyên vẹn,
 * hoặc là bản mới nguyên vẹn — không bao giờ là một bản dở dang.
 *
 * <h2>Ghi cả tệp mỗi lần thay đổi</h2>
 *
 * <p>Đây là đánh đổi có ý thức, và nó chỉ đúng vì <b>số tài khoản rất nhỏ</b>
 * (hàng chục, không phải hàng triệu). Với quy mô đó, ghi lại toàn bộ tệp là
 * vài chục kilobyte và đơn giản hơn hẳn một định dạng ghi thêm có chỉ mục.
 * Nếu số tài khoản lên tới hàng nghìn, đây chính là chỗ phải đổi sang
 * PostgreSQL — và nhờ {@link UserStore} là một interface, đổi chỉ là thêm một
 * lớp.
 *
 * <h2>Tên tài khoản không phân biệt hoa thường</h2>
 *
 * <p>Khoá của bảng là tên đã hạ về chữ thường. Không làm vậy thì {@code Admin}
 * và {@code admin} là hai tài khoản khác nhau — một cái bẫy vừa gây khó chịu
 * (đăng nhập sai vì phím Caps) vừa nguy hiểm (kẻ xấu đăng ký {@code Admin} để
 * mạo danh).
 */
public final class JsonUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(JsonUserStore.class);

    private final Path path;
    private final ObjectMapper mapper;
    private final Map<String, User> users = new ConcurrentHashMap<>();

    public JsonUserStore(String filePath) throws IOException {
        this.path = Path.of(filePath);
        this.mapper = new ObjectMapper()
                // Instant không tuần tự hoá được nếu thiếu module này — và lỗi
                // chỉ lộ ra lúc CHẠY, ở lần đăng ký đầu tiên.
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Tệp do phiên bản sau có thể có thêm trường; một trường lạ
                // không được phép làm hỏng cả kho tài khoản.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    private void load() throws IOException {
        if (!Files.exists(path)) {
            log.info("Chua co tep tai khoan tai {} — se tao khi co dang ky dau tien", path);
            return;
        }
        List<User> loaded = mapper.readValue(path.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, User.class));
        for (User user : loaded) {
            if (user.username() != null && user.passwordHash() != null) {
                users.put(key(user.username()), user);
            } else {
                // Bỏ qua bản ghi hỏng thay vì để nó thành một tài khoản không
                // đăng nhập được nhưng vẫn chiếm tên.
                log.warn("Bo qua mot ban ghi tai khoan thieu truong trong {}", path);
            }
        }
        log.info("Da nap {} tai khoan tu {}", users.size(), path);
    }

    @Override
    public Optional<User> find(String username) {
        return username == null ? Optional.empty() : Optional.ofNullable(users.get(key(username)));
    }

    @Override
    public List<User> findAll() {
        List<User> all = new ArrayList<>(users.values());
        all.sort(Comparator.comparing(User::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    @Override
    public synchronized void save(User user) throws IOException {
        User previous = users.put(key(user.username()), user);
        try {
            persist();
        } catch (IOException e) {
            // Khôi phục bộ nhớ về đúng trạng thái đã ghi bền được. Không làm
            // vậy thì bảng trong RAM nói có tài khoản, tệp trên đĩa nói không,
            // và lần khởi động sau người dùng "mất" tài khoản vừa tạo.
            if (previous == null) {
                users.remove(key(user.username()));
            } else {
                users.put(key(user.username()), previous);
            }
            throw e;
        }
    }

    @Override
    public synchronized boolean delete(String username) throws IOException {
        User removed = users.remove(key(username));
        if (removed == null) {
            return false;
        }
        try {
            persist();
        } catch (IOException e) {
            users.put(key(username), removed);
            throw e;
        }
        return true;
    }

    @Override
    public int count() {
        return users.size();
    }

    /** Ghi ra tệp tạm rồi đổi tên — xem Javadoc lớp. */
    private void persist() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), findAll());
        try {
            Files.move(temp, path,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Một số hệ thống tệp (chia sẻ mạng, vài cấu hình Windows) không
            // hứa nguyên tử. Vẫn phải ghi được — chỉ là mất bảo đảm đó.
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
