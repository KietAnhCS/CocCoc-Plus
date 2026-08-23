package com.vnsearch.config;

import com.vnsearch.auth.JsonUserStore;
import com.vnsearch.auth.Role;
import com.vnsearch.auth.SessionStore;
import com.vnsearch.auth.UserService;
import com.vnsearch.auth.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;

/**
 * Dựng tầng tài khoản: kho tài khoản, dịch vụ, kho phiên, và tài khoản mồi.
 *
 * <p><b>Vì sao các bean này khai ở đây chứ không dùng {@code @Service} trên
 * từng lớp.</b> Cả ba lớp nhận {@link Clock} qua hàm dựng để kiểm thử điều
 * khiển được thời gian (hết hạn phiên, khoá tạm tài khoản). Một lớp có
 * {@code @Service} thì Spring phải tự dựng nó, và khi đó {@code Clock} lại
 * phải thành một bean toàn cục — thứ ảnh hưởng tới mọi nơi khác chỉ vì hai lớp
 * cần nó. Dựng tường minh ở một chỗ thì các lớp {@code auth} vẫn là POJO thuần,
 * dùng được trong test mà không cần Spring.
 */
@Configuration
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    @Bean
    public UserStore userStore(@Value("${app.auth.users-path:data/users.json}") String path)
            throws IOException {
        return new JsonUserStore(path);
    }

    @Bean
    public UserService userService(UserStore userStore) {
        return new UserService(userStore, Clock.systemUTC());
    }

    @Bean
    public SessionStore sessionStore() {
        return new SessionStore(Clock.systemUTC());
    }

    /**
     * Tạo tài khoản quản trị đầu tiên, nếu được khai báo và nếu chưa tồn tại.
     *
     * <h2>Ba lựa chọn, và vì sao chọn cái này</h2>
     *
     * <table border="1">
     *   <caption>Cách tạo admin đầu tiên</caption>
     *   <tr><th>Cách</th><th>Vấn đề</th></tr>
     *   <tr><td>Mật khẩu mặc định ghi trong mã ({@code admin/admin})</td>
     *       <td><b>Loại bỏ ngay.</b> Mọi bản triển khai đều có cùng một mật
     *           khẩu ai cũng biết, và phần lớn không ai đổi</td></tr>
     *   <tr><td>Người đăng ký ĐẦU TIÊN tự động thành admin</td>
     *       <td>Kẻ nào tìm thấy máy chủ trước chủ nhân thì chiếm được quyền
     *           quản trị. Một cửa sổ nhỏ nhưng mở toang</td></tr>
     *   <tr><td><b>Biến môi trường, không có mặc định</b> (chọn)</td>
     *       <td>Phải cấu hình thêm một bước — cái giá chấp nhận được</td></tr>
     * </table>
     *
     * <p><b>Thiếu cấu hình thì cảnh báo, KHÔNG chặn khởi động</b> — khác với
     * {@code ADMIN_API_KEY}. Hai thứ khác nhau ở chỗ: thiếu khoá API nghĩa là
     * các endpoint quản trị <i>không có gì bảo vệ</i> (phải chặn), còn thiếu
     * tài khoản mồi chỉ nghĩa là <i>chưa có ai đăng nhập được bằng tài
     * khoản</i> — máy tìm kiếm vẫn phục vụ người dùng bình thường, và khoá API
     * vẫn là lối vào quản trị. Chặn khởi động ở đây sẽ làm hỏng chức năng chính
     * vì một tính năng phụ chưa cấu hình.
     */
    @Bean
    public ApplicationRunner bootstrapAdmin(
            UserService userService,
            @Value("${app.auth.bootstrap-admin.username:admin}") String username,
            @Value("${app.auth.bootstrap-admin.password:}") String password) {
        return args -> {
            if (password == null || password.isBlank()) {
                if (userService.count() == 0) {
                    log.warn("Chua co tai khoan nao va cung chua khai bao"
                            + " app.auth.bootstrap-admin.password (bien moi truong"
                            + " BOOTSTRAP_ADMIN_PASSWORD). Chua the dang nhap bang tai khoan;"
                            + " van dung duoc X-API-Key cho cac endpoint quan tri.");
                }
                return;
            }
            if (userService.find(username).isPresent()) {
                // Đã có thì KHÔNG ghi đè: nếu ghi đè, mỗi lần khởi động sẽ đặt
                // lại mật khẩu về giá trị trong biến môi trường, và mọi lần
                // người quản trị tự đổi mật khẩu đều bị nuốt mất một cách âm thầm.
                log.info("Tai khoan quan tri moi '{}' da ton tai — khong ghi de", username);
                return;
            }
            userService.createAccount(username, password, Role.ADMIN);
            log.info("Da tao tai khoan quan tri moi '{}'. Hay doi mat khau sau lan dang nhap dau.",
                    username);
        };
    }
}
