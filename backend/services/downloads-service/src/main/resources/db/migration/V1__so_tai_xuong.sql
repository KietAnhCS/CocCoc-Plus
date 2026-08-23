-- ===========================================================================
-- V1 — Sổ tải xuống.
-- ===========================================================================
--
-- Lược đồ RIÊNG của downloads-service. Không service nào khác có quyền trên
-- schema này — cùng nguyên tắc với auth-service: một lỗ hổng ở service khác
-- không được phép đọc dữ liệu ở đây.
--
-- QUY TẮC BẤT DI BẤT DỊCH: tệp migration đã chạy ở đâu đó thì KHÔNG BAO GIỜ
-- sửa nữa. Flyway lưu checksum; sửa một tệp cũ làm mọi môi trường đã chạy nó
-- báo lỗi và dừng khởi động. Muốn đổi thì thêm V2, V3.

CREATE TABLE IF NOT EXISTS downloads (
    -- UUID do MÁY KHÁCH sinh, không phải khoá tự tăng của CSDL.
    --
    -- Lý do là tính IDEMPOTENT. Trình duyệt bắt đầu một lượt tải rồi mất mạng
    -- giữa chừng; nó thử lại. Với khoá tự tăng, lần thử thứ hai tạo ra một
    -- dòng thứ hai và người dùng thấy cùng một tệp hai lần trong danh sách.
    -- Với UUID do máy khách sinh, lần thử thứ hai mang đúng id cũ và
    -- ON CONFLICT biến nó thành một phép cập nhật.
    id              UUID         NOT NULL,

    username        VARCHAR(32)  NOT NULL,

    -- Địa chỉ nguồn. TEXT chứ không VARCHAR(n): URL thật có thể rất dài, và
    -- một giới hạn đoán bừa sẽ cắt cụt đúng những URL mà người dùng cần nhất
    -- (liên kết tải có chữ ký, có token).
    source_url      TEXT         NOT NULL,

    file_name       VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(255),

    -- Tổng byte. NULL là hợp lệ và có nghĩa: máy chủ không gửi Content-Length,
    -- nên KHÔNG BIẾT tệp lớn bao nhiêu. Điền 0 thay cho NULL sẽ khiến giao
    -- diện hiện "đã tải 0%" mãi mãi cho một lượt tải đang chạy bình thường.
    total_bytes     BIGINT,
    received_bytes  BIGINT       NOT NULL DEFAULT 0,

    -- Trạng thái, ràng buộc ở tầng CSDL chứ không chỉ trong Java.
    --
    -- Một enum Java chặn được mã của chính mình, nhưng không chặn được một câu
    -- UPDATE chạy tay lúc khắc phục sự cố, hay một phiên bản mã cũ còn chạy
    -- song song trong lúc triển khai cuốn chiếu. Ràng buộc ở đây thì không
    -- đường nào lách được.
    state           VARCHAR(16)  NOT NULL,

    -- Đường dẫn trên máy người dùng. CÓ THỂ NULL: chế độ ẩn danh và những lượt
    -- tải bị huỷ trước khi ghi tệp đều không có đường dẫn.
    --
    -- LƯU Ý VỀ QUYỀN RIÊNG TƯ: đây là dữ liệu cá nhân (nó lộ ra cấu trúc thư
    -- mục, tên người dùng hệ điều hành). Nó KHÔNG bao giờ được trả về cho ai
    -- ngoài chủ sở hữu, và không bao giờ được ghi ra log.
    local_path      TEXT,

    -- Thiết bị nào đã tải. Cho phép giao diện hiện "đã tải trên máy khác" thay
    -- vì mở một đường dẫn không tồn tại trên máy hiện tại.
    device_id       VARCHAR(64),

    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_downloads PRIMARY KEY (id),

    CONSTRAINT ck_downloads_state
        CHECK (state IN ('IN_PROGRESS', 'PAUSED', 'COMPLETED', 'CANCELLED', 'INTERRUPTED')),

    -- Số byte không bao giờ âm, và không vượt quá tổng khi tổng đã biết.
    -- Một phép cập nhật sai thứ tự (gói tin đến trễ) sẽ bị chặn tại đây thay
    -- vì tạo ra một thanh tiến độ 137%.
    CONSTRAINT ck_downloads_received CHECK (received_bytes >= 0),
    CONSTRAINT ck_downloads_total CHECK (total_bytes IS NULL OR total_bytes >= received_bytes),

    -- Đã xong thì phải có thời điểm xong, và ngược lại. Không có ràng buộc này
    -- thì một mục "COMPLETED" mà finished_at NULL sẽ khiến mọi phép sắp xếp
    -- theo thời gian hoàn tất bỏ sót nó.
    CONSTRAINT ck_downloads_finished
        CHECK ((state IN ('COMPLETED', 'CANCELLED', 'INTERRUPTED')) = (finished_at IS NOT NULL))
);

-- Truy vấn duy nhất mà giao diện chạy: "danh sách tải xuống của tôi, mới
-- nhất trước". Chỉ mục phải khớp CHÍNH XÁC hình dạng đó, kể cả chiều sắp xếp.
CREATE INDEX IF NOT EXISTS ix_downloads_user_started
    ON downloads (username, started_at DESC);

-- Lọc "đang tải" để hiện thanh tiến độ. Chỉ mục MỘT PHẦN: chỉ đánh chỉ mục
-- những dòng đang chạy, vốn luôn là một nhúm nhỏ. Chỉ mục đầy đủ trên cột
-- `state` sẽ phình theo toàn bộ lịch sử tải xuống để phục vụ một truy vấn chỉ
-- quan tâm tới vài dòng.
CREATE INDEX IF NOT EXISTS ix_downloads_dang_chay
    ON downloads (username)
    WHERE state IN ('IN_PROGRESS', 'PAUSED');

COMMENT ON TABLE downloads IS
    'Sổ tải xuống của trình duyệt VnSearch. Chỉ downloads-service được truy cập.';
COMMENT ON COLUMN downloads.local_path IS
    'Dữ liệu cá nhân: lộ cấu trúc thư mục máy người dùng. KHÔNG ghi ra log.';
