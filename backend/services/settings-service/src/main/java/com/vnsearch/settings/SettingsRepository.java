package com.vnsearch.settings;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Truy cập bảng {@code user_settings}.
 *
 * <h2>Toàn bộ khối tuỳ chọn đi qua như một chuỗi JSON</h2>
 *
 * <p>Lớp này KHÔNG phân tích JSON. Nó nhận một chuỗi đã được tầng trên xác
 * nhận là hợp lệ và giao cho PostgreSQL. Ranh giới đó có chủ ý: phân tích ở
 * hai nơi nghĩa là hai bộ luật có thể lệch nhau, và bên nào đúng thì không ai
 * biết.
 *
 * <p>Phép kiểm cuối cùng nằm ở CSDL ({@code ck_user_settings_object},
 * {@code ck_user_settings_size}) — nơi không đường nào lách được, kể cả một
 * câu {@code UPDATE} chạy tay lúc khắc phục sự cố.
 */
@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tuỳ chọn hiện tại kèm số phiên bản. */
    public record Snapshot(String json, long version, Instant updatedAt) {
    }

    public Optional<Snapshot> doc(String username) {
        return jdbc.sql("""
                        SELECT settings::text AS settings, version, updated_at
                          FROM user_settings
                         WHERE username = :username
                        """)
                .param("username", username)
                .query((rs, rowNum) -> new Snapshot(
                        rs.getString("settings"),
                        rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /**
     * GỘP khối mới vào khối đang có (ngữ nghĩa PATCH), tăng {@code version}.
     *
     * <p>Toán tử {@code ||} của jsonb gộp hai đối tượng ở <b>mức trên cùng</b>:
     * khoá trùng thì bên phải thắng, khoá mới được thêm, khoá cũ không nhắc tới
     * thì giữ nguyên. Đó đúng là điều một PATCH nên làm.
     *
     * <p><b>Giới hạn phải biết:</b> phép gộp KHÔNG đệ quy. Gửi
     * {@code {"theme":{"dark":true}}} sẽ thay thế TOÀN BỘ đối tượng
     * {@code theme}, không chỉ khoá {@code dark}. Với một khối tuỳ chọn phẳng
     * thì điều đó không thành vấn đề, và giữ nó phẳng là quy ước của API này.
     * Ngày nào tuỳ chọn cần lồng nhau, phải chuyển sang {@code jsonb_set} theo
     * từng đường dẫn — và đó là một API khác.
     *
     * <p><b>Khoá lạc quan.</b> {@code expectedVersion} khác {@code null} thì
     * câu lệnh chỉ ghi khi phiên bản còn khớp. Trả về rỗng nghĩa là thiết bị
     * khác đã ghi trước — máy khách phải đọc lại và gộp, chứ không được ghi đè.
     */
    @Transactional
    public Optional<Snapshot> gop(String username, String jsonMoi, Long expectedVersion) {
        int soDong = jdbc.sql("""
                        INSERT INTO user_settings (username, settings, version)
                        VALUES (:username, :settings::jsonb, 1)
                        ON CONFLICT (username) DO UPDATE SET
                               settings   = user_settings.settings || EXCLUDED.settings,
                               version    = user_settings.version + 1,
                               updated_at = now()
                         WHERE :expectedVersion::bigint IS NULL
                            OR user_settings.version = :expectedVersion::bigint
                        """)
                .param("username", username)
                .param("settings", jsonMoi)
                .param("expectedVersion", expectedVersion)
                .update();

        // 0 dòng: hoặc xung đột phiên bản, hoặc (không thể xảy ra) mất dòng.
        // Trả rỗng để tầng trên dịch thành 409, thay vì báo thành công cho một
        // phép ghi chưa xảy ra.
        return soDong == 0 ? Optional.empty() : doc(username);
    }

    /**
     * THAY THẾ toàn bộ khối (ngữ nghĩa PUT).
     *
     * <p>Khác {@link #gop}: mọi khoá không có trong {@code jsonMoi} đều biến
     * mất. Dùng cho nút "Khôi phục cài đặt gốc" và cho lần đồng bộ đầu tiên
     * của một thiết bị mới.
     */
    @Transactional
    public Optional<Snapshot> thayThe(String username, String jsonMoi, Long expectedVersion) {
        int soDong = jdbc.sql("""
                        INSERT INTO user_settings (username, settings, version)
                        VALUES (:username, :settings::jsonb, 1)
                        ON CONFLICT (username) DO UPDATE SET
                               settings   = EXCLUDED.settings,
                               version    = user_settings.version + 1,
                               updated_at = now()
                         WHERE :expectedVersion::bigint IS NULL
                            OR user_settings.version = :expectedVersion::bigint
                        """)
                .param("username", username)
                .param("settings", jsonMoi)
                .param("expectedVersion", expectedVersion)
                .update();
        return soDong == 0 ? Optional.empty() : doc(username);
    }

    /**
     * Xoá một khoá khỏi khối tuỳ chọn.
     *
     * <p>Toán tử {@code -} của jsonb nhận tên khoá và trả về khối không có
     * khoá đó. Tham số vẫn đi qua phép ràng buộc, nên một tên khoá kiểu
     * {@code '; DROP TABLE} chỉ là một tên khoá không tồn tại.
     */
    @Transactional
    public Optional<Snapshot> xoaKhoa(String username, String khoa) {
        jdbc.sql("""
                        UPDATE user_settings
                           SET settings   = settings - :khoa,
                               version    = version + 1,
                               updated_at = now()
                         WHERE username = :username
                        """)
                .param("username", username)
                .param("khoa", khoa)
                .update();
        return doc(username);
    }

    @Transactional
    public void xoaHet(String username) {
        jdbc.sql("DELETE FROM user_settings WHERE username = :username")
                .param("username", username)
                .update();
    }

    /**
     * Bọc một chuỗi thành {@code PGobject} kiểu {@code jsonb}.
     *
     * <p>Không dùng tới trong các câu lệnh ở trên vì chúng đã ép kiểu bằng
     * {@code ::jsonb} trong chính SQL. Giữ lại như một tiện ích cho câu lệnh
     * nào cần truyền jsonb ở vị trí mà phép ép kiểu không viết được.
     */
    static PGobject jsonb(String json) throws SQLException {
        PGobject object = new PGobject();
        object.setType("jsonb");
        object.setValue(json);
        return object;
    }
}
