package com.vnsearch.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Kho tài khoản trên PostgreSQL — bản dùng ở môi trường thật.
 *
 * <p>Lược đồ do Flyway quản lý theo phiên bản; xem
 * {@code src/main/resources/db/migration/V1__tai_khoan.sql}. Ba tính chất mà
 * bản {@link JsonUserStore} không thể có, và là lý do tồn tại của lớp này:
 *
 * <ol>
 *   <li><b>Nhiều bản sao dùng chung một sự thật.</b> Với tệp JSON, hai bản sao
 *       auth-service ghi đè lẫn nhau và tài khoản vừa đăng ký biến mất.</li>
 *   <li><b>Ghi có giao dịch.</b> Đổi vai trò rồi cập nhật thời điểm sửa là hai
 *       thao tác; với tệp JSON, một lần treo máy ở giữa để lại tệp viết dở.</li>
 *   <li><b>Ràng buộc do CSDL canh, không do mã canh.</b> {@code UNIQUE} trên
 *       tên tài khoản chặn hai lượt đăng ký song song cùng một tên — thứ mà
 *       một phép kiểm tra {@code if (exists)} trong Java <i>không bao giờ</i>
 *       chặn được, vì giữa lúc kiểm và lúc ghi luôn có một khe hở.</li>
 * </ol>
 *
 * <h2>Về SQL injection</h2>
 *
 * <p>Mọi câu lệnh ở đây dùng <b>tham số ràng buộc</b> ({@code :ten}), không
 * ghép chuỗi. Đây là biện pháp chống <i>A03:2021 — Injection</i>, và nó phải
 * là thói quen chứ không phải một lần rà soát: chỉ cần một câu lệnh ghép chuỗi
 * lọt vào một lớp truy cập dữ liệu là toàn bộ nỗ lực còn lại thành vô nghĩa.
 * Cách giữ thói quen ở đây là {@link JdbcClient} — nó không có API nào nhận
 * một câu lệnh đã ghép sẵn tham số, nên viết sai là phải cố tình.
 *
 * <h2>Về so khớp tên tài khoản</h2>
 *
 * <p>Tên KHÔNG phân biệt hoa thường, và điều đó được canh ở <b>tầng CSDL</b>
 * bằng một chỉ mục duy nhất trên {@code lower(username)}. Canh ở tầng Java —
 * gọi {@code toLowerCase()} trước khi ghi — thì một câu lệnh {@code INSERT}
 * chạy tay, hoặc một phiên bản mã cũ, vẫn tạo được {@code Admin} bên cạnh
 * {@code admin}. Hai tài khoản mà người dùng tin là một.
 */
public class PostgresUserStore implements UserStore {

    private static final RowMapper<User> ROW_MAPPER = PostgresUserStore::mapRow;

    private final JdbcClient jdbc;

    public PostgresUserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT username, password_hash, role, enabled, created_at, last_login_at
                          FROM auth_users
                         WHERE lower(username) = lower(:username)
                        """)
                .param("username", username)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public List<User> findAll() {
        return jdbc.sql("""
                        SELECT username, password_hash, role, enabled, created_at, last_login_at
                          FROM auth_users
                         ORDER BY created_at
                        """)
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * Thêm mới hoặc ghi đè.
     *
     * <p>{@code ON CONFLICT ... DO UPDATE} chứ không phải "kiểm tra rồi chọn
     * INSERT hay UPDATE": phương án sau có một khe hở giữa hai câu lệnh, và
     * hai lượt đăng ký cùng tên gửi lên đồng thời sẽ cùng thấy "chưa tồn tại"
     * rồi cùng {@code INSERT}. Một câu lệnh nguyên tử của CSDL đóng khe hở đó.
     *
     * <p>Xung đột bắt trên {@code lower(username)} chứ không trên
     * {@code username} — đúng cột mà chỉ mục duy nhất được tạo trên đó.
     */
    @Override
    @Transactional
    public void save(User user) {
        try {
            jdbc.sql("""
                            INSERT INTO auth_users
                                   (username, password_hash, role, enabled, created_at, last_login_at)
                            VALUES (:username, :hash, :role, :enabled, :createdAt, :lastLoginAt)
                            ON CONFLICT (lower(username)) DO UPDATE SET
                                   password_hash = EXCLUDED.password_hash,
                                   role          = EXCLUDED.role,
                                   enabled       = EXCLUDED.enabled,
                                   last_login_at = EXCLUDED.last_login_at
                            """)
                    .param("username", user.username())
                    .param("hash", user.passwordHash())
                    .param("role", user.role().name())
                    .param("enabled", user.enabled())
                    .param("createdAt", Timestamp.from(user.createdAt()))
                    .param("lastLoginAt", user.lastLoginAt() == null
                            ? null : Timestamp.from(user.lastLoginAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            // Không thể xảy ra với ON CONFLICT ở trên, trừ khi lược đồ bị sửa
            // tay. Bọc lại thành thông báo nói đúng nguyên nhân, thay vì để
            // một ngoại lệ của tầng JDBC nổi lên tận controller.
            throw new IllegalStateException(
                    "Tên tài khoản đã tồn tại: " + user.username(), e);
        }
    }

    @Override
    @Transactional
    public boolean delete(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return jdbc.sql("DELETE FROM auth_users WHERE lower(username) = lower(:username)")
                .param("username", username)
                .update() > 0;
    }

    @Override
    public int count() {
        return jdbc.sql("SELECT count(*) FROM auth_users")
                .query(Integer.class)
                .single();
    }

    private static User mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new User(
                rs.getString("username"),
                rs.getString("password_hash"),
                // Role.parse hạ về USER khi gặp giá trị lạ. Ở đây nó là
                // lưới an toàn cho trường hợp một hàng được sửa tay trong CSDL:
                // hướng an toàn là MẤT quyền, không phải được thêm quyền.
                Role.parse(rs.getString("role")),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("created_at")),
                lastLogin == null ? null : lastLogin.toInstant());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    /** Chuẩn hoá tên để đối chiếu — cùng quy tắc với chỉ mục trong CSDL. */
    public static String normalize(String username) {
        return username == null ? null : username.toLowerCase(Locale.ROOT);
    }
}
