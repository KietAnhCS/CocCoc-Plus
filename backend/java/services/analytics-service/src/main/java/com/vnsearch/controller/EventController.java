package com.vnsearch.controller;

import com.vnsearch.analytics.UsageAnalyticsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Nơi giao diện <b>báo lại</b> hành vi người dùng: mở ứng dụng, tìm kiếm, bấm
 * vào một kết quả.
 *
 * <h2>Vì sao endpoint này CÔNG KHAI trong khi số liệu nó sinh ra thì không</h2>
 *
 * <p>Đây là chỗ dễ nhầm nhất của cả tính năng, nên nói thẳng: hai chiều dữ liệu
 * có hai mức quyền khác nhau.
 *
 * <pre>
 *   GHI (POST /api/events)          ĐỌC (GET /api/admin/analytics)
 *   ──────────────────────          ──────────────────────────────
 *   ai cũng gọi được                cần X-API-Key, vai trò ADMIN
 *   vì mọi người dùng đều           vì số liệu tổng hợp phơi bày
 *   phải báo được hành vi           người dùng đang tìm gì
 * </pre>
 *
 * <p>Bắt endpoint ghi phải xác thực thì chỉ còn <i>quản trị viên</i> đóng góp
 * được số liệu — tức là không còn số liệu nào đáng đọc. Ngược lại, để endpoint
 * đọc mở ra thì bất kỳ ai cũng xem được toàn bộ truy vấn của mọi người dùng
 * khác. Ranh giới đúng nằm giữa hai chiều, không phải ở một trong hai.
 *
 * <h2>Hệ quả của việc mở chiều ghi, và những gì chặn nó lại</h2>
 *
 * <p>Endpoint công khai nghĩa là <b>số liệu ở đây không đáng tin để tính
 * tiền hay để ra quyết định pháp lý</b> — bất kỳ ai cũng gửi được sự kiện giả.
 * Nó đủ tin cho đúng việc nó phục vụ: nhìn xu hướng sử dụng của chính ứng dụng
 * mình. Ba thứ giữ cho nó không thành cửa tấn công:
 * <ol>
 *   <li>{@code RateLimitFilter} đã bọc sẵn toàn bộ {@code /api/*} — 120
 *       request/phút cho mỗi địa chỉ, không cần thêm gì ở đây;</li>
 *   <li>mọi chuỗi gửi lên đều bị chặn độ dài ngay tại lớp này (Bean Validation)
 *       <i>và</i> bị cắt lại lần nữa trong {@link UsageAnalyticsService} — lớp
 *       dịch vụ không tin lớp gọi nó, vì nó còn được gọi từ test và có thể từ
 *       chỗ khác sau này;</li>
 *   <li>mọi bảng thống kê đều có trần bộ nhớ, nên một luồng sự kiện rác chỉ
 *       làm số liệu nhiễu chứ không làm hết heap.</li>
 * </ol>
 *
 * <p>Không nhận và không ghi địa chỉ IP: bảng điều khiển đếm <i>phiên</i>, và
 * mã phiên là chuỗi ngẫu nhiên do máy khách sinh.
 */
@RestController
@RequestMapping("/api")
public class EventController {

    private final UsageAnalyticsService analytics;

    public EventController(UsageAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /**
     * Một sự kiện sử dụng.
     *
     * <p>Các trường phụ thuộc vào {@code type}: {@code search} dùng
     * {@code query}/{@code resultCount}/{@code tookMs}, {@code click} dùng
     * {@code url}/{@code position}, {@code visit} không dùng gì thêm. Dùng một
     * kiểu chung cho cả ba thay vì ba endpoint riêng vì phía gửi là một hàm duy
     * nhất, và ba đường dẫn gần giống nhau chỉ nhân ba chỗ phải nhớ cập nhật.
     *
     * @param sessionId chuỗi ngẫu nhiên do máy khách sinh; không nhận dạng người dùng
     * @param tookMs    độ trễ máy chủ đã báo trong phản hồi tìm kiếm
     */
    public record EventRequest(
            @NotBlank(message = "type khong duoc de trong")
            @Size(max = 16, message = "type khong hop le")
            String type,

            @Size(max = 64, message = "sessionId toi da 64 ky tu")
            String sessionId,

            @Size(max = 200, message = "query toi da 200 ky tu")
            String query,

            @Size(max = 500, message = "url toi da 500 ky tu")
            String url,

            Integer position,
            Integer resultCount,
            Long tookMs) {
    }

    /**
     * @return {@code 204} khi đã ghi nhận, {@code 400} khi {@code type} lạ.
     *         Không trả về thân phản hồi: phía gửi là một lời gọi "bắn rồi
     *         quên", không đọc gì cả, nên mọi byte trả về đều là byte thừa.
     */
    @PostMapping("/events")
    public ResponseEntity<Void> record(@Valid @RequestBody EventRequest request,
                                        Authentication authentication) {
        // Danh tính lấy từ NGỮ CẢNH BẢO MẬT, không phải từ thân request. Nếu
        // để máy khách tự khai tên mình trong JSON thì bất kỳ ai cũng gán được
        // hành vi cho người khác — một endpoint công khai mà tin lời tự khai
        // thì bảng "ai dùng nhiều" trở thành thứ giả mạo được bằng một dòng curl.
        String username = authentication == null ? null : authentication.getName();

        String type = request.type().trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "visit" -> analytics.recordVisit(request.sessionId(), username);
            case "search" -> analytics.recordSearch(
                    request.sessionId(),
                    username,
                    request.query(),
                    request.resultCount() == null ? 0 : request.resultCount(),
                    request.tookMs() == null ? -1 : request.tookMs());
            case "click" -> analytics.recordClick(
                    request.sessionId(),
                    username,
                    request.url(),
                    request.position() == null ? 0 : request.position());
            // Kiểu lạ bị TỪ CHỐI chứ không âm thầm bỏ qua: một phiên bản giao
            // diện mới gửi sai tên sự kiện sẽ thấy 400 ngay khi thử, thay vì
            // thấy một bảng điều khiển trống rỗng vài tuần sau và không hiểu vì sao.
            default -> {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.noContent().build();
    }
}
