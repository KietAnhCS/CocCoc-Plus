-- ===========================================================================
-- V1 — Bảng tài khoản của auth-service.
-- ===========================================================================
--
-- LƯỢC ĐỒ RIÊNG, KHÔNG DÙNG CHUNG VỚI SERVICE KHÁC. Bảng này nằm trong schema
-- `auth` chứ không nằm cạnh `documents` của search-service. Lý do không phải
-- gọn gàng mà là ngăn chặn: một lỗ hổng SQL injection ở bất kỳ service nào
-- khác cũng không được phép đọc tới bảng chứa hash mật khẩu. Tài khoản CSDL
-- của các service khác không có quyền trên schema này.
--
-- VÌ SAO DÙNG FLYWAY CHỨ KHÔNG PHẢI ddl-auto=update. Hibernate/JPA có thể tự
-- suy ra lược đồ từ lớp Java, và cám dỗ đó rất lớn. Ba lý do không:
--   1. Nó KHÔNG tạo ra lịch sử. Không ai trả lời được "cột này thêm vào lúc
--      nào, vì việc gì" — câu hỏi đầu tiên của mọi cuộc điều tra sự cố.
--   2. Nó KHÔNG lùi được. Một lần triển khai hỏng không có đường quay lại.
--   3. Nó tự ý sửa lược đồ ở môi trường thật. Với một hệ thống ngân hàng, mọi
--      thay đổi lược đồ phải là một tệp có thể đọc, duyệt và phê duyệt TRƯỚC
--      khi chạy — đó chính là tệp này.
--
-- QUY TẮC BẤT DI BẤT DỊCH: tệp migration đã chạy ở đâu đó thì KHÔNG BAO GIỜ
-- sửa nữa. Flyway lưu checksum; sửa một tệp cũ làm mọi môi trường đã chạy nó
-- báo lỗi và dừng khởi động. Muốn đổi thì thêm V2, V3.

CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth_users (
    -- Tên tài khoản là KHOÁ CHÍNH, không dùng id tự tăng.
    --
    -- Cân nhắc có thật: khoá tự tăng cho phép ĐỔI TÊN tài khoản mà không phá
    -- các tham chiếu. Nhưng ở hệ này, `sub` trong JWT chính là tên tài khoản,
    -- và ba service dữ liệu cá nhân phân vùng dữ liệu theo đúng chuỗi đó. Thêm
    -- một id nội bộ nghĩa là mọi service phải hỏi auth-service để dịch id sang
    -- tên — một lượt gọi mạng cho mỗi request, và một điểm chết chung mới.
    -- Đánh đổi: VnSearch không cho đổi tên tài khoản. Ghi rõ ở đây để lần sau
    -- không ai phải đoán vì sao.
    username        VARCHAR(32)  NOT NULL,

    -- BCrypt sinh chuỗi 60 ký tự. Để 100 cho khoảng thở khi đổi sang Argon2id
    -- (chuỗi dài hơn) mà không phải chạy migration đúng lúc đang có sự cố.
    password_hash   VARCHAR(100) NOT NULL,

    -- Lưu TÊN enum chứ không lưu số thứ tự. Lưu số thì chèn một vai trò mới
    -- vào giữa enum trong Java sẽ âm thầm đổi nghĩa của mọi hàng đã có — USER
    -- thành ADMIN mà không có một dòng lỗi nào.
    role            VARCHAR(16)  NOT NULL,

    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,

    CONSTRAINT pk_auth_users PRIMARY KEY (username),
    CONSTRAINT ck_auth_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Tên tài khoản KHÔNG phân biệt hoa thường, và ràng buộc đó được canh ở đây
-- chứ không ở tầng Java.
--
-- Canh trong Java (gọi toLowerCase trước khi ghi) trông đủ, nhưng nó chỉ đúng
-- với đường đi qua đúng đoạn mã đó. Một câu lệnh INSERT chạy tay lúc khắc phục
-- sự cố, một job nhập dữ liệu, hay một phiên bản mã cũ vẫn tạo được `Admin`
-- bên cạnh `admin`: hai hàng mà người dùng tin là một tài khoản, và người nào
-- đăng nhập vào hàng nào thì tuỳ câu truy vấn.
CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_users_username_lower
    ON auth_users (lower(username));

-- Trang quản trị sắp danh sách theo thời điểm tạo. Không có chỉ mục thì đó là
-- một lần quét toàn bảng cộng sắp xếp — chấp nhận được với 50 tài khoản, không
-- chấp nhận được với 50 nghìn.
CREATE INDEX IF NOT EXISTS ix_auth_users_created_at ON auth_users (created_at);

COMMENT ON TABLE auth_users IS
    'Tài khoản người dùng VnSearch. Chỉ auth-service được phép truy cập.';
COMMENT ON COLUMN auth_users.password_hash IS
    'Hash BCrypt (cost 10). KHÔNG BAO GIỜ ghi cột này ra log hay ra phản hồi API.';
