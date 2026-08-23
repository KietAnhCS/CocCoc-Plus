package com.vnsearch.auth;

/**
 * Vai trò của một tài khoản. <b>Chỉ có hai</b>, và đó là một quyết định.
 *
 * <p><b>Vì sao không làm hệ quyền chi tiết (permission-based).</b> Một hệ
 * thống quyền "đúng bài" cho phép gán từng quyền lẻ ({@code CRAWL_START},
 * {@code ANALYTICS_READ}, {@code USER_MANAGE}) rồi gom thành nhóm. Nó đúng khi
 * có nhiều loại người vận hành khác nhau. Ở đây thì không: mọi endpoint quản
 * trị đều được dùng bởi cùng một người, và một hệ quyền chi tiết chỉ tạo ra
 * một tầng cấu hình mà không ai cấu hình khác đi. Hai vai trò mô tả đúng thực
 * tế; thêm vai trò thứ ba (ví dụ {@code VIEWER} — xem số liệu nhưng không
 * được chạy crawl) là việc của lúc thật sự có người cần nó.
 *
 * <p><b>Vì sao có tiền tố {@code ROLE_}.</b> Spring Security quy ước
 * {@code hasRole("ADMIN")} sẽ đi tìm quyền tên {@code ROLE_ADMIN}. Ghép tiền
 * tố ở một chỗ duy nhất tại đây, thay vì rải chuỗi {@code "ROLE_ADMIN"} khắp
 * các filter — nơi một lần gõ thiếu tiền tố cho ra một tài khoản <i>trông như
 * có quyền</i> mà mọi request đều 403.
 */
public enum Role {

    /** Người dùng thường: tìm kiếm, xem kết quả. Không thấy số liệu của ai khác. */
    USER,

    /** Quản trị: thêm quyền điều khiển crawler và đọc toàn bộ số liệu sử dụng. */
    ADMIN;

    /** Tên quyền theo quy ước Spring Security. */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Phân giải từ chuỗi, KHÔNG ném ngoại lệ.
     *
     * <p>Chuỗi này đến từ tệp JSON trên đĩa — thứ con người sửa được bằng tay.
     * Một giá trị lạ (gõ nhầm, phiên bản cũ) không được phép làm ứng dụng chết
     * lúc khởi động; hạ về {@link #USER} là hướng an toàn: mất quyền thì người
     * thật sẽ báo ngay, còn <i>thừa</i> quyền thì không ai phát hiện.
     *
     * <p>{@code @JsonCreator} để <b>Jackson cũng đi qua đây</b>. Không có nó,
     * Jackson tự phân giải enum và ném ngoại lệ khi gặp giá trị lạ — nghĩa là
     * một ký tự thừa trong {@code users.json} sẽ làm ứng dụng không khởi động
     * được, và toàn bộ đoạn Javadoc ở trên chỉ đúng trên giấy.
     *
     * <p>Nó cũng là điểm vào khi Jackson đọc thân request
     * {@code POST /api/admin/users/{ten}/role}. Ở đó, một vai trò gõ sai bị
     * hiểu thành {@code USER} — vẫn là hướng an toàn (không ai vô tình được
     * nâng quyền), và endpoint trả về vai trò thật sự đã đặt nên người gọi
     * nhìn thấy ngay.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static Role parse(String raw) {
        if (raw == null) {
            return USER;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
