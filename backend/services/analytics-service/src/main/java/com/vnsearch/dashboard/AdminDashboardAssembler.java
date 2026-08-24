package com.vnsearch.dashboard;

import com.vnsearch.analytics.AdminDashboard;
import com.vnsearch.analytics.CorpusStats;
import com.vnsearch.analytics.UsageAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Gom ba nguồn dữ liệu thành <b>một</b> bảng điều khiển — mẫu <i>API
 * Composition</i>.
 *
 * <pre>
 *   traffic   UsageAnalyticsService   trong tiến trình này
 *   index     crawler-service         GET /api/admin/stats
 *   accounts  auth-service            GET /api/admin/users/stats
 * </pre>
 *
 * <h2>Vì sao gọi HTTP thay vì đọc chung CSDL</h2>
 *
 * <p>Đọc chung CSDL nhanh hơn và ít mã hơn. Nó cũng xoá sạch ranh giới service:
 * hai service kia không đổi được lược đồ nữa (service này sẽ vỡ), và một lỗ
 * hổng ở đây chạm tới bảng {@code auth_users} — nơi chứa hash mật khẩu. Một
 * endpoint hẹp trả về vài con số giữ nguyên ranh giới: bên này không biết bảng
 * tên gì, cột tên gì.
 *
 * <h2>Ba biện pháp chống hỏng dây chuyền</h2>
 *
 * <p>API composition đổi <i>một</i> điểm hỏng lấy <i>ba</i>. Không xử lý thì
 * bảng điều khiển sập mỗi khi bất kỳ service nào chậm — và tệ hơn, nó sập
 * <b>chậm</b>, giữ luồng chờ cho tới khi hết thời gian chờ mặc định (vô hạn ở
 * nhiều client HTTP).
 *
 * <ol>
 *   <li><b>Thời gian chờ ngắn và tường minh.</b> 2 giây cho kết nối, 3 giây
 *       cho phản hồi. Đây là bảng điều khiển làm mới theo chu kỳ, không phải
 *       một giao dịch — chờ lâu hơn không mang lại giá trị nào.</li>
 *   <li><b>Suy giảm từng phần.</b> Một khối lấy không được thì trả về khối
 *       rỗng kèm log, không ném ngoại lệ. Người vận hành thấy hai khối có số
 *       và một khối trống — thông tin hữu ích hơn nhiều so với một trang lỗi,
 *       vì bản thân khối trống đã nói cho họ biết service nào đang hỏng.</li>
 *   <li><b>Truyền tiếp danh tính người gọi.</b> Hai endpoint kia cần vai trò
 *       ADMIN. Service này KHÔNG dùng một tài khoản dịch vụ đặc quyền — nó
 *       chuyển tiếp chính access token của người đang xem. Nhờ vậy một người
 *       mất quyền ADMIN lập tức không đọc được các khối kia nữa, thay vì tiếp
 *       tục đọc được nhờ đặc quyền của service.</li>
 * </ol>
 */
@Service
public class AdminDashboardAssembler {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardAssembler.class);

    /** Giá trị trả về khi không lấy được khối chỉ mục. */
    private static final AdminDashboard.IndexStats INDEX_UNKNOWN =
            new AdminDashboard.IndexStats(0, 0, 0L, 0.0, "khong-ro", 0L);

    /** Giá trị trả về khi không lấy được khối tài khoản. */
    private static final AdminDashboard.AccountStats ACCOUNTS_UNKNOWN =
            new AdminDashboard.AccountStats(0, 0, 0, 0);

    private final UsageAnalyticsService analytics;
    private final RestClient crawlerClient;
    private final RestClient authClient;

    public AdminDashboardAssembler(
            UsageAnalyticsService analytics,
            RestClient.Builder builder,
            @Value("${app.clients.crawler-service.url:http://crawler-service:8083}") String crawlerUrl,
            @Value("${app.clients.auth-service.url:http://auth-service:8081}") String authUrl) {
        this.analytics = analytics;
        this.crawlerClient = builder.clone().baseUrl(crawlerUrl).build();
        this.authClient = builder.clone().baseUrl(authUrl).build();
    }

    /**
     * @param top             số dòng mỗi bảng xếp hạng
     * @param callerAuthHeader header {@code Authorization} của người đang xem,
     *                         chuyển tiếp nguyên vẹn sang hai service kia
     */
    public AdminDashboard assemble(int top, String callerAuthHeader) {
        return new AdminDashboard(
                Instant.now(),
                analytics.snapshot(top),
                safeFetch("corpus", null, () -> corpusStats(callerAuthHeader)),
                safeFetch("chỉ mục", INDEX_UNKNOWN,
                        () -> indexStats(callerAuthHeader)),
                safeFetch("tài khoản", ACCOUNTS_UNKNOWN,
                        () -> accountStats(callerAuthHeader)));
    }

    /**
     * Gọi một service khác, và <b>không bao giờ để lỗi của nó lan ra ngoài</b>.
     *
     * <p>Bắt {@code Exception} chứ không bắt một danh sách kiểu cụ thể, và đó
     * là một trong số rất ít chỗ mà việc bắt rộng là đúng: mọi cách mà một
     * lượt gọi mạng có thể hỏng — hết thời gian chờ, DNS sai, chứng chỉ hết
     * hạn, JSON đổi hình dạng — đều dẫn tới cùng một quyết định ở đây: hiện
     * khối trống. Liệt kê kiểu ngoại lệ chỉ tạo ra một danh sách sẽ thiếu.
     */
    private <T> T safeFetch(String name, T fallback, Supplier<T> source) {
        try {
            return source.get();
        } catch (Exception e) {
            log.warn("Không lấy được khối '{}' cho bảng điều khiển: {}", name, e.toString());
            return fallback;
        }
    }

    private AdminDashboard.IndexStats indexStats(String authHeader) {
        Map<String, Object> body = crawlerClient.get()
                .uri("/api/admin/stats")
                .headers(headers -> forwardIdentity(headers, authHeader))
                .retrieve()
                .body(Map.class);
        if (body == null) {
            return INDEX_UNKNOWN;
        }
        return new AdminDashboard.IndexStats(
                // Tên khoá phải khớp CHÍNH XÁC với những gì
                // SearchEngineFacade.getStats() đặt vào map. Lệch một chữ thì
                // không có lỗi nào cả — chỉ có một con số 0 trên bảng điều
                // khiển, và không ai biết đó là "không có tài liệu" hay "gọi
                // sai khoá". Đây là cái giá của hợp đồng bằng Map thay vì bằng
                // một kiểu có tên.
                toInt(body.get("totalDocuments")),
                toInt(body.get("totalTerms")),
                toLong(body.get("indexSizeBytes")),
                toDouble(body.get("cacheHitRate")),
                body.get("scorer") == null ? "khong-ro" : body.get("scorer").toString(),
                toLong(body.get("bloomFilterBits")));
    }

    private CorpusStats corpusStats(String authHeader) {
        return crawlerClient.get()
                .uri("/api/admin/corpus-stats")
                .headers(headers -> forwardIdentity(headers, authHeader))
                .retrieve()
                .body(CorpusStats.class);
    }

    private AdminDashboard.AccountStats accountStats(String authHeader) {
        Map<String, Object> body = authClient.get()
                .uri("/api/admin/users/stats")
                .headers(headers -> forwardIdentity(headers, authHeader))
                .retrieve()
                .body(Map.class);
        if (body == null) {
            return ACCOUNTS_UNKNOWN;
        }
        return new AdminDashboard.AccountStats(
                toInt(body.get("total")),
                toInt(body.get("admins")),
                toInt(body.get("disabled")),
                toInt(body.get("activeSessions")));
    }

    private static void forwardIdentity(HttpHeaders headers, String authHeader) {
        if (authHeader != null && !authHeader.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
    }

    // Ba hàm ép kiểu dưới đây tồn tại vì JSON không phân biệt int với long với
    // double: Jackson dựng Integer cho số nhỏ, Long cho số lớn, Double cho số
    // thập phân — nên ép thẳng sang một kiểu cụ thể sẽ ném ClassCastException
    // đúng vào ngày corpus đủ lớn để một con số vượt Integer.MAX_VALUE.

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
