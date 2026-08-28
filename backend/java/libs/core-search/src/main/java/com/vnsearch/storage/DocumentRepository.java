package com.vnsearch.storage;

import com.vnsearch.model.WebDocument;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kho lưu trữ tài liệu trên PostgreSQL, truy cập bằng JDBC thuần.
 *
 * <p><b>Vai trò trong kiến trúc:</b> CSDL chỉ lưu tài liệu thô và đồ thị
 * liên kết. Chỉ mục đảo, Trie gợi ý, LRU cache và PageRank vẫn do đồ án tự
 * cài đặt và nằm trong bộ nhớ. Khi khởi động, hệ thống đọc tài liệu từ đây
 * rồi DỰNG LẠI chỉ mục trong RAM — chứ không đẩy việc tìm kiếm cho CSDL.
 *
 * <p><b>Vì sao dùng JDBC thuần thay vì JPA/Hibernate:</b> ba lý do. Thứ
 * nhất, câu lệnh SQL hiện nguyên văn trong mã nguồn nên đưa vào báo cáo
 * được, còn JPA sinh SQL ngầm. Thứ hai, thao tác chính ở đây là ghi hàng
 * loạt hàng chục nghìn bản ghi, mà JDBC batch nhanh hơn hẳn việc ORM quản
 * lý vòng đời từng entity. Thứ ba, tránh kéo theo tự động cấu hình
 * DataSource của Spring Boot — nhờ vậy ứng dụng vẫn chạy được bình thường
 * khi không có CSDL, điều kiện cần để bộ test và bản demo nhanh không bị
 * phụ thuộc hạ tầng.
 *
 * <p>Ghi theo lô {@link #BATCH_SIZE} bản ghi: gửi từng câu INSERT một sẽ
 * tốn một vòng khứ hồi mạng cho mỗi bản ghi, với 5.000 tài liệu và gần
 * 400.000 liên kết thì chi phí đó chi phối toàn bộ thời gian nạp.
 */
public class DocumentRepository implements AutoCloseable {

    public static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/vnsearch";
    public static final String DEFAULT_USER = "vnsearch";
    public static final String DEFAULT_PASSWORD = "vnsearch";

    private static final int BATCH_SIZE = 500;

    private final Connection connection;

    public DocumentRepository(String jdbcUrl, String user, String password) throws SQLException {
        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
    }

    public static DocumentRepository connectDefault() throws SQLException {
        return new DocumentRepository(DEFAULT_URL, DEFAULT_USER, DEFAULT_PASSWORD);
    }

    /** Xoá sạch dữ liệu cũ (outlinks bị xoá theo nhờ ON DELETE CASCADE). */
    public void deleteAll() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE TABLE documents CASCADE");
        }
    }

    /**
     * Ghi toàn bộ tài liệu và liên kết vào CSDL trong MỘT giao dịch duy nhất.
     *
     * <p>Gói trong một giao dịch để việc nạp có tính nguyên tử: nếu đứt giữa
     * chừng thì không để lại một corpus dở dang mà chỉ mục dựng trên đó sẽ
     * sai lệch một cách âm thầm.
     */
    public void saveAll(List<WebDocument> documents) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            insertDocuments(documents);
            insertOutlinks(documents);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void insertDocuments(List<WebDocument> documents) throws SQLException {
        String sql = """
                INSERT INTO documents (doc_id, url, title, meta_description, body_text, crawled_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (doc_id) DO UPDATE SET
                    url = EXCLUDED.url,
                    title = EXCLUDED.title,
                    meta_description = EXCLUDED.meta_description,
                    body_text = EXCLUDED.body_text,
                    crawled_at = EXCLUDED.crawled_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            for (WebDocument doc : documents) {
                statement.setInt(1, doc.getDocId());
                statement.setString(2, doc.getUrl());
                statement.setString(3, doc.getTitle());
                statement.setString(4, doc.getMetaDescription());
                statement.setString(5, doc.getBodyText());
                statement.setTimestamp(6, doc.getCrawledAt() == null
                        ? null : Timestamp.from(doc.getCrawledAt()));
                statement.addBatch();
                if (++pending % BATCH_SIZE == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private void insertOutlinks(List<WebDocument> documents) throws SQLException {
        String sql = "INSERT INTO outlinks (from_doc_id, to_url) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            for (WebDocument doc : documents) {
                for (String outlink : doc.getOutlinks()) {
                    statement.setInt(1, doc.getDocId());
                    statement.setString(2, outlink);
                    statement.addBatch();
                    if (++pending % BATCH_SIZE == 0) {
                        statement.executeBatch();
                    }
                }
            }
            statement.executeBatch();
        }
    }

    /**
     * Đọc toàn bộ tài liệu kèm liên kết, sắp xếp theo doc_id tăng dần.
     *
     * <p>Thứ tự tăng dần là BẮT BUỘC: {@code InvertedIndex} chỉ giữ được bất
     * biến "posting list sắp xếp theo docId" khi các tài liệu được nạp theo
     * đúng thứ tự đó, và chính bất biến ấy cho phép giao posting list bằng
     * two-pointer O(m+n) thay vì phải sắp xếp lại mỗi lần truy vấn.
     */
    public List<WebDocument> findAll() throws SQLException {
        Map<Integer, WebDocument> byId = new LinkedHashMap<>();
        String docSql = """
                SELECT doc_id, url, title, meta_description, body_text, crawled_at
                FROM documents ORDER BY doc_id
                """;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(docSql)) {
            while (rs.next()) {
                WebDocument doc = new WebDocument();
                doc.setDocId(rs.getInt("doc_id"));
                doc.setUrl(rs.getString("url"));
                doc.setTitle(rs.getString("title"));
                doc.setMetaDescription(rs.getString("meta_description"));
                doc.setBodyText(rs.getString("body_text"));
                Timestamp crawledAt = rs.getTimestamp("crawled_at");
                doc.setCrawledAt(crawledAt == null ? null : crawledAt.toInstant());
                doc.setOutlinks(new ArrayList<>());
                byId.put(doc.getDocId(), doc);
            }
        }

        // Đọc liên kết bằng MỘT truy vấn cho toàn bộ corpus rồi ghép trong bộ
        // nhớ, thay vì mỗi tài liệu một truy vấn — tránh đúng lỗi N+1 kinh điển
        // (5.011 tài liệu sẽ thành 5.012 lượt khứ hồi tới CSDL).
        String linkSql = "SELECT from_doc_id, to_url FROM outlinks ORDER BY from_doc_id";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(linkSql)) {
            while (rs.next()) {
                WebDocument doc = byId.get(rs.getInt("from_doc_id"));
                if (doc != null) {
                    doc.getOutlinks().add(rs.getString("to_url"));
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    public int countDocuments() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM documents")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public long countOutlinks() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM outlinks")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Kích thước (byte) của bảng documents kèm mọi chỉ mục của nó. */
    public long totalRelationSizeBytes(String relation) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_total_relation_size(?)")) {
            statement.setString(1, relation);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Kích thước (byte) của riêng một chỉ mục — dùng để so với chỉ mục tự cài. */
    public long indexSizeBytes(String indexName) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_relation_size(?)")) {
            statement.setString(1, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * Tìm kiếm bằng full-text search sẵn có của PostgreSQL (chỉ mục GIN).
     * Đây là ĐỐI CHỨNG để so với chỉ mục đảo tự cài, không phải đường đi
     * phục vụ người dùng.
     *
     * @return danh sách URL xếp theo điểm {@code ts_rank} giảm dần
     */
    public List<String> searchWithGin(String queryText, int limit) throws SQLException {
        String sql = """
                SELECT url, ts_rank(tsv, plainto_tsquery('simple', ?)) AS rank
                FROM documents
                WHERE tsv @@ plainto_tsquery('simple', ?)
                ORDER BY rank DESC
                LIMIT ?
                """;
        List<String> urls = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, queryText);
            statement.setString(2, queryText);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    urls.add(rs.getString("url"));
                }
            }
        }
        return urls;
    }

    public Instant now() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT now()")) {
            return rs.next() ? rs.getTimestamp(1).toInstant() : Instant.now();
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
