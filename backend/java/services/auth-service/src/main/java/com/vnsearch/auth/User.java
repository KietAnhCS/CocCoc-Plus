package com.vnsearch.auth;

import java.time.Instant;

/**
 * Một tài khoản.
 *
 * <p><b>Trường {@code passwordHash} — và vì sao không có trường
 * {@code password}.</b> Mật khẩu thô không tồn tại ở bất kỳ đâu trong hệ thống
 * quá vài mili giây: nó đi từ request vào {@link UserService#authenticate},
 * được băm để so sánh, rồi biến mất cùng khung ngăn xếp. Không có trường nào
 * giữ nó, nên không có đường nào để nó vô tình bị ghi log, bị tuần tự hoá vào
 * JSON, hay hiện ra trong một thông báo lỗi.
 *
 * <p><b>{@link #toPublic()} là ranh giới ra ngoài.</b> Bản ghi này KHÔNG bao
 * giờ được trả thẳng qua REST — nó chứa hash. Hash BCrypt không đảo ngược
 * được, nhưng phơi nó ra cho phép kẻ tấn công dò ngoại tuyến với tốc độ tuỳ ý,
 * và tiết lộ luôn tham số chi phí. Mọi endpoint trả về {@link PublicView}.
 *
 * @param username     định danh, chữ thường, duy nhất
 * @param passwordHash BCrypt, đã kèm salt bên trong chuỗi
 * @param enabled      {@code false} = khoá tài khoản mà không xoá dữ liệu
 * @param lastLoginAt  {@code null} khi chưa đăng nhập lần nào
 */
public record User(
        String username,
        String passwordHash,
        Role role,
        boolean enabled,
        Instant createdAt,
        Instant lastLoginAt) {

    /** Dạng an toàn để gửi ra ngoài: giống {@link User} nhưng KHÔNG có hash. */
    public record PublicView(
            String username,
            Role role,
            boolean enabled,
            Instant createdAt,
            Instant lastLoginAt) {
    }

    public PublicView toPublic() {
        return new PublicView(username, role, enabled, createdAt, lastLoginAt);
    }

    public User withRole(Role newRole) {
        return new User(username, passwordHash, newRole, enabled, createdAt, lastLoginAt);
    }

    public User withEnabled(boolean nowEnabled) {
        return new User(username, passwordHash, role, nowEnabled, createdAt, lastLoginAt);
    }

    public User withLastLoginAt(Instant at) {
        return new User(username, passwordHash, role, enabled, createdAt, at);
    }

    public User withPasswordHash(String newHash) {
        return new User(username, newHash, role, enabled, createdAt, lastLoginAt);
    }
}
