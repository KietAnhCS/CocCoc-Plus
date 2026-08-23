-- ===========================================================================
-- V1 — Tuỳ chọn người dùng.
-- ===========================================================================
--
-- MỘT DÒNG CHO MỘT NGƯỜI, và toàn bộ tuỳ chọn nằm trong một cột JSONB.
--
-- VÌ SAO KHÔNG PHẢI MỖI TUỲ CHỌN MỘT CỘT. Cách đó cho kiểu dữ liệu chặt và
-- truy vấn được từng trường — nghe đúng hơn hẳn. Nhưng nó đổi lấy một thứ đắt:
-- MỖI tuỳ chọn mới của giao diện trở thành một tệp migration, một lần triển
-- khai CSDL, và một lần đồng bộ giữa hai nhóm. Giao diện trình duyệt sẽ còn
-- thêm hàng chục tuỳ chọn (chủ đề, cỡ chữ, trang chủ, công cụ tìm mặc định,
-- ngôn ngữ, hiện thanh dấu trang...), và không tuỳ chọn nào trong số đó được
-- truy vấn hay tổng hợp — chúng chỉ được ĐỌC NGUYÊN KHỐI cho đúng một người.
--
-- VÌ SAO KHÔNG PHẢI MỖI TUỲ CHỌN MỘT DÒNG (bảng khoá-giá trị). Nó tránh được
-- migration, nhưng đọc toàn bộ tuỳ chọn của một người thành N dòng phải ghép
-- lại trong Java, và ghi một khối thành N câu lệnh phải bọc trong giao dịch.
-- Với JSONB thì đọc là một dòng, ghi là một câu lệnh nguyên tử.
--
-- VÌ SAO JSONB CHỨ KHÔNG PHẢI JSON hay TEXT:
--   * jsonb KIỂM TRA cú pháp lúc ghi — một chuỗi hỏng bị chặn ngay, thay vì
--     nằm im tới lúc ai đó đọc và ứng dụng nổ;
--   * jsonb lưu ở dạng nhị phân đã phân tích, nên đọc không phải phân tích lại;
--   * jsonb hỗ trợ toán tử `||` để GỘP hai khối — đây chính là thứ làm cho
--     phép cập nhật từng phần (PATCH) trở thành một câu lệnh.
-- `json` thuần lưu nguyên văn (kể cả khoảng trắng thừa) và không có `||`.

CREATE TABLE IF NOT EXISTS user_settings (
    username    VARCHAR(32)  NOT NULL,

    -- Toàn bộ tuỳ chọn. Mặc định là đối tượng RỖNG chứ không phải NULL: nhờ
    -- vậy phép gộp `settings || :moi` luôn chạy được, còn với NULL thì kết quả
    -- của phép gộp cũng là NULL — và toàn bộ tuỳ chọn của người dùng biến mất
    -- sau đúng một lần cập nhật. Đây là hành vi của SQL với NULL mà rất dễ
    -- quên, và hậu quả ở đây là mất dữ liệu chứ không phải một lỗi ồn ào.
    settings    JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- Đếm số lần sửa, tăng mỗi lần ghi.
    --
    -- Dùng cho ĐỒNG BỘ NHIỀU THIẾT BỊ: máy khách gửi kèm phiên bản nó đang
    -- giữ, và máy chủ từ chối nếu phiên bản đó đã cũ. Không có nó thì hai máy
    -- cùng sửa sẽ ghi đè lẫn nhau âm thầm — người dùng đổi chủ đề trên máy A,
    -- máy B đồng bộ lên và xoá mất thay đổi đó.
    version     BIGINT       NOT NULL DEFAULT 1,

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_settings PRIMARY KEY (username),

    -- Chặn khối JSON khổng lồ. Không có trần thì một máy khách hỏng (hoặc một
    -- người cố tình) có thể nhét vài megabyte vào một dòng, và mọi lượt đọc
    -- tuỳ chọn sau đó đều kéo về ngần ấy dữ liệu. 64 KB đủ cho hàng nghìn tuỳ
    -- chọn thật.
    CONSTRAINT ck_user_settings_size CHECK (pg_column_size(settings) <= 65536),

    -- PHẢI là một đối tượng JSON, không phải mảng hay số.
    -- Thiếu ràng buộc này thì một máy khách gửi `[1,2,3]` sẽ ghi được, và phép
    -- gộp `||` sau đó cho ra kết quả vô nghĩa thay vì báo lỗi.
    CONSTRAINT ck_user_settings_object CHECK (jsonb_typeof(settings) = 'object')
);

COMMENT ON TABLE user_settings IS
    'Tuỳ chọn giao diện của người dùng. Chỉ settings-service được truy cập.';
COMMENT ON COLUMN user_settings.version IS
    'Tăng mỗi lần ghi. Máy khách gửi kèm để phát hiện xung đột giữa nhiều thiết bị.';
