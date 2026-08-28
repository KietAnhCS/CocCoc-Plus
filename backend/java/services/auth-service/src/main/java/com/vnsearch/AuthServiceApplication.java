package com.vnsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-service — máy chủ uỷ quyền của VnSearch.
 *
 * <h2>Vì sao {@code scanBasePackages} liệt kê tường minh</h2>
 *
 * <p>Mặc định, {@code @SpringBootApplication} quét toàn bộ gói chứa nó —
 * ở đây là {@code com.vnsearch} — và như vậy nó sẽ nạp luôn mọi bean nằm
 * trong {@code vnsearch-core} có mặt trên classpath: bộ dựng chỉ mục, bộ quản
 * lý crawl, dịch vụ gợi ý. Không cái nào trong số đó thuộc về một máy chủ xác
 * thực, và mỗi cái là một khoản bộ nhớ, một khoản thời gian khởi động, và một
 * bề mặt tấn công thêm vào đúng tiến trình <i>không được phép</i> có bề mặt
 * tấn công thừa.
 *
 * <p>Danh sách tường minh biến điều đó thành một quyết định phải viết ra chứ
 * không phải một tác dụng phụ của việc thêm một phụ thuộc.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",      // platform: CORS, lỗi, rate-limit + config riêng của service
        "com.vnsearch.controller",  // AuthController, AdminUserController
        "com.vnsearch.oauth"        // phát hành và thu hồi token
})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
