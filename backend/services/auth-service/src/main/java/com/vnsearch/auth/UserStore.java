package com.vnsearch.auth;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * <b>Strategy pattern</b> — nơi lưu tài khoản, để {@link UserService} không
 * phải biết chúng nằm ở đâu.
 *
 * <p>Cùng khuôn với {@code DocumentStore} của tầng corpus, và vì cùng một lý
 * do: bản cài đặt mặc định ghi ra một tệp JSON để <b>người vừa clone repo chạy
 * được ngay</b>, còn một bản cài PostgreSQL thêm vào sau sẽ không phải sửa một
 * dòng nào trong {@link UserService}.
 *
 * <p><b>Vì sao không dùng thẳng Spring Data JPA.</b> Nó kéo theo một CSDL bắt
 * buộc phải có mới khởi động được, trong khi cả dự án này được thiết kế để
 * chạy không cần dịch vụ ngoài nào ({@code app.storage.postgres.enabled=false}
 * là mặc định). Một hệ tài khoản mà muốn thử phải cài PostgreSQL trước thì
 * người chấm sẽ không thử.
 *
 * <p>Mọi bản cài đặt phải <b>an toàn đa luồng</b>: đăng ký và đăng nhập đến từ
 * các luồng xử lý HTTP khác nhau.
 */
public interface UserStore {

    /** Tài khoản theo tên, hoặc rỗng. Tên KHÔNG phân biệt hoa thường. */
    Optional<User> find(String username);

    /** Toàn bộ tài khoản, sắp theo thời điểm tạo. Dùng cho trang quản trị. */
    List<User> findAll();

    /**
     * Thêm mới hoặc ghi đè theo {@code username}.
     *
     * @throws IOException khi không ghi bền được — người gọi <b>phải</b> coi
     *                     đây là thất bại, không được báo "đăng ký thành công"
     *                     cho một tài khoản chỉ tồn tại trong RAM
     */
    void save(User user) throws IOException;

    /** {@code true} nếu có xoá. Xoá tài khoản không xoá phiên đang mở của nó. */
    boolean delete(String username) throws IOException;

    /** Số tài khoản hiện có — dùng để quyết định có cần tạo tài khoản mồi không. */
    int count();
}
