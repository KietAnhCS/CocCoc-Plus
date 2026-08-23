package com.vnsearch.config;

import com.vnsearch.auth.JsonUserStore;
import com.vnsearch.auth.PostgresUserStore;
import com.vnsearch.auth.Role;
import com.vnsearch.auth.UserService;
import com.vnsearch.auth.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;

/**
 * Dựng tầng tài khoản: kho tài khoản, dịch vụ, kho phiên, và tài khoản mồi.
 *
 * <p><b>Vì sao các bean này khai ở đây chứ không dùng {@code @Service} trên
 * từng lớp.</b> Chúng nhận {@link Clock} qua hàm dựng để kiểm thử điều khiển
 * được thời gian (khoá tạm tài khoản sau nhiều lần đăng nhập sai). Một lớp có
 * {@code @Service} thì Spring phải tự dựng nó, và khi đó {@code Clock} lại
 * phải thành một bean toàn cục — thứ ảnh hưởng tới mọi nơi khác chỉ vì hai lớp
 * cần nó. Dựng tường minh ở một chỗ thì các lớp {@code auth} vẫn là POJO thuần,
 * dùng được trong test mà không cần Spring.
 */
@Configuration
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    /**
     * Kho tài khoản — JSON hay PostgreSQL, chọn bằng {@code app.auth.store}.
     *
     * <pre>
     *   json      (mặc định)  một tệp, không cần dịch vụ ngoài  → dev, test, demo
     *   postgres              lược đồ do Flyway quản lý          → môi trường thật
     * </pre>
     *
     * <p><b>Vì sao mặc định là JSON.</b> Người vừa clone repo phải chạy được
     * ngay; bắt cài PostgreSQL trước khi thấy dòng mã đầu tiên hoạt động là
     * cách chắc chắn nhất để họ không thử. Nhưng mặc định đó KHÔNG được im
     * lặng: {@code JsonUserStore} với hai bản sao cùng chạy sẽ ghi đè lẫn nhau
     * và làm biến mất tài khoản vừa đăng ký, nên chỗ nào chạy thật cũng phải
     * đặt {@code app.auth.store=postgres}. Xem cảnh báo ở dưới.
     */
    @Bean
    public UserStore userStore(
            @Value("${app.auth.store:json}") String kind,
            @Value("${app.auth.users-path:data/users.json}") String path,
            ObjectProvider<JdbcClient> jdbcProvider) throws IOException {

        if ("postgres".equalsIgnoreCase(kind)) {
            JdbcClient jdbc = jdbcProvider.getIfAvailable();
            if (jdbc == null) {
                // Hỏng to thay vì âm thầm lùi về JSON. Lùi về JSON ở đây nghĩa
                // là một môi trường thật chạy với kho tài khoản sai mà không ai
                // biết, cho tới khi bản sao thứ hai khởi động và tài khoản bắt
                // đầu biến mất.
                throw new IllegalStateException(
                        "app.auth.store=postgres nhưng không có DataSource."
                                + " Kiểm tra spring.datasource.url.");
            }
            log.info("Kho tài khoản: PostgreSQL (lược đồ do Flyway quản lý)");
            return new PostgresUserStore(jdbc);
        }

        log.warn("Kho tài khoản: tệp JSON tại {}. Chỉ dùng cho dev/test —"
                + " hai bản sao cùng ghi sẽ đè lên nhau và làm mất tài khoản."
                + " Môi trường thật phải đặt app.auth.store=postgres.", path);
        return new JsonUserStore(path);
    }

    @Bean
    public UserService userService(UserStore userStore) {
        return new UserService(userStore, Clock.systemUTC());
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
