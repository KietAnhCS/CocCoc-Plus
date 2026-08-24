package com.vnsearch.downloads;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Truy cập bảng {@code downloads}.
 *
 * <h2>Hai bất biến mà lớp này giữ</h2>
 *
 * <ol>
 *   <li><b>Mọi câu lệnh dùng tham số ràng buộc</b>, không ghép chuỗi —
 *       <i>A03:2021 — Injection</i>. {@link JdbcClient} không có API nào nhận
 *       câu lệnh đã ghép sẵn, nên viết sai là phải cố tình.</li>
 *   <li><b>Mọi câu lệnh có điều kiện {@code username}</b>, kể cả khi đã biết
 *       {@code id}. Thiếu nó thì ai đoán được UUID sẽ đọc hoặc xoá được bản
 *       ghi của người khác — IDOR, <i>A01:2021</i>. Với UUID thì đoán rất khó,
 *       nhưng "khó đoán" không phải một cơ chế phân quyền: UUID lọt ra ngoài
 *       qua log, qua thanh địa chỉ, qua ảnh chụp màn hình.</li>
 * </ol>
 */
@Repository
public class DownloadRepository {

    private static final RowMapper<DownloadRecord> ROW_MAPPER = DownloadRepository::mapRow;

    private static final String COLUMNS = """
            id, username, source_url, file_name, mime_type, total_bytes, received_bytes,
            state, local_path, device_id, started_at, finished_at, updated_at
            """;

    private final JdbcClient jdbc;

    public DownloadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Thêm mới, hoặc cập nhật nếu id đã tồn tại — <b>idempotent</b>.
     *
     * <p>Trình duyệt bắt đầu một lượt tải rồi mất mạng và thử lại với cùng
     * UUID. Với {@code INSERT} thuần, lần thử thứ hai đổ vì trùng khoá chính,
     * và máy khách không có cách nào phân biệt "trùng vì đã ghi thành công"
     * với "trùng vì có lỗi thật". {@code ON CONFLICT DO UPDATE} biến lần thử
     * lại thành một phép ghi vô hại.
     *
     * <p>Điều kiện {@code WHERE username} trong nhánh {@code DO UPDATE} là
     * dòng dễ quên nhất và cũng quan trọng nhất: thiếu nó, một người biết UUID
     * của người khác sẽ GHI ĐÈ được bản ghi đó.
     */
    @Transactional
    public void save(DownloadRecord record) {
        jdbc.sql("""
                        INSERT INTO downloads
                               (id, username, source_url, file_name, mime_type, total_bytes,
                                received_bytes, state, local_path, device_id,
                                started_at, finished_at, updated_at)
                        VALUES (:id, :username, :sourceUrl, :fileName, :mimeType, :totalBytes,
                                :receivedBytes, :state, :localPath, :deviceId,
                                :startedAt, :finishedAt, now())
                        ON CONFLICT (id) DO UPDATE SET
                               received_bytes = EXCLUDED.received_bytes,
                               total_bytes    = COALESCE(EXCLUDED.total_bytes, downloads.total_bytes),
                               state          = EXCLUDED.state,
                               local_path     = COALESCE(EXCLUDED.local_path, downloads.local_path),
                               finished_at    = EXCLUDED.finished_at,
                               updated_at     = now()
                         WHERE downloads.username = EXCLUDED.username
                        """)
                .param("id", record.id())
                .param("username", record.username())
                .param("sourceUrl", record.sourceUrl())
                .param("fileName", record.fileName())
                .param("mimeType", record.mimeType())
                .param("totalBytes", record.totalBytes())
                .param("receivedBytes", record.receivedBytes())
                .param("state", record.state().name())
                .param("localPath", record.localPath())
                .param("deviceId", record.deviceId())
                .param("startedAt", Timestamp.from(record.startedAt()))
                .param("finishedAt", record.finishedAt() == null
                        ? null : Timestamp.from(record.finishedAt()))
                .update();
    }

    public Optional<DownloadRecord> find(UUID id, String username) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM downloads WHERE id = :id AND username = :username")
                .param("id", id)
                .param("username", username)
                .query(ROW_MAPPER)
                .optional();
    }

    /** Trang sổ tải xuống, mới nhất trước — khớp đúng chỉ mục {@code ix_downloads_user_started}. */
    public List<DownloadRecord> findByUser(String username, int offset, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM downloads
                        WHERE username = :username
                        ORDER BY started_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("username", username)
                .param("limit", limit)
                .param("offset", offset)
                .query(ROW_MAPPER)
                .list();
    }

    /** Những lượt còn đang chạy — dùng chỉ mục một phần {@code ix_downloads_dang_chay}. */
    public List<DownloadRecord> findActive(String username) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM downloads
                        WHERE username = :username
                          AND state IN ('IN_PROGRESS', 'PAUSED')
                        ORDER BY started_at DESC
                        """)
                .param("username", username)
                .query(ROW_MAPPER)
                .list();
    }

    @Transactional
    public boolean delete(UUID id, String username) {
        return jdbc.sql("DELETE FROM downloads WHERE id = :id AND username = :username")
                .param("id", id)
                .param("username", username)
                .update() > 0;
    }

    /**
     * Xoá sổ tải xuống của một người.
     *
     * <p>CHỈ xoá những mục đã kết thúc. Xoá cả mục đang tải sẽ để lại một lượt
     * tải chạy tiếp trên máy người dùng mà không còn dòng nào theo dõi nó —
     * tệp vẫn về đích, nhưng không ai biết nó ở đâu.
     */
    @Transactional
    public int deleteFinished(String username) {
        return jdbc.sql("""
                        DELETE FROM downloads
                         WHERE username = :username
                           AND state IN ('COMPLETED', 'CANCELLED', 'INTERRUPTED')
                        """)
                .param("username", username)
                .update();
    }

    public int count(String username) {
        return jdbc.sql("SELECT count(*) FROM downloads WHERE username = :username")
                .param("username", username)
                .query(Integer.class)
                .single();
    }

    private static DownloadRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DownloadRecord(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("source_url"),
                rs.getString("file_name"),
                rs.getString("mime_type"),
                // getLong tra ve 0 cho NULL, nen phai hoi wasNull() — neu
                // khong, "khong biet tong so byte" bien thanh "tep rong", va
                // giao dien hien 100% cho mot luot tai vua bat dau.
                longOrNull(rs, "total_bytes"),
                rs.getLong("received_bytes"),
                DownloadState.valueOf(rs.getString("state")),
                rs.getString("local_path"),
                rs.getString("device_id"),
                instantOrNull(rs, "started_at"),
                instantOrNull(rs, "finished_at"),
                instantOrNull(rs, "updated_at"));
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
