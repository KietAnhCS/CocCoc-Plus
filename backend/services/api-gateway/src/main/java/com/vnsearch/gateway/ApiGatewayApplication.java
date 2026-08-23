package com.vnsearch.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * api-gateway — cửa duy nhất mà máy khách nhìn thấy.
 *
 * <h2>Bốn việc, và chỉ bốn việc</h2>
 *
 * <pre>
 *   1. ĐỊNH TUYẾN      /api/search  -> search-service    (application.yaml)
 *   2. XÁC THỰC        kiểm chữ ký JWT một lần tại biên  (GatewaySecurityConfig)
 *   3. THU HỒI         tra denylist theo jti trên Redis  (TokenDenylistFilter)
 *   4. GIỚI HẠN TẦN SUẤT  theo NGƯỜI DÙNG, không theo IP (RateLimitConfig)
 * </pre>
 *
 * <h2>Việc mà nó KHÔNG làm, và vì sao</h2>
 *
 * <p><b>Không có logic nghiệp vụ.</b> Không ghép dữ liệu từ nhiều service,
 * không biến đổi thân phản hồi, không quyết định thứ tự kết quả. Cám dỗ rất
 * lớn — Gateway nhìn thấy mọi thứ nên chỗ nào cũng "tiện" đặt vào đây. Nhưng
 * một Gateway có nghiệp vụ là một khối duy nhất mới, chỉ khác là lần này nó
 * nằm trên đường đi của <i>toàn bộ</i> lưu lượng: nó hỏng thì cả tám service
 * đều không với tới được, và triển khai nó thì cả hệ thống ngừng.
 *
 * <p>Phép ghép dữ liệu duy nhất trong hệ này nằm ở
 * {@code AdminDashboardAssembler} của analytics-service — một service bình
 * thường, hỏng được mà không kéo theo ai.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
