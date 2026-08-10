package com.vnsearch.controller;

import com.vnsearch.analytics.AdminDashboard;
import com.vnsearch.analytics.UsageAnalyticsService;
import com.vnsearch.auth.Role;
import com.vnsearch.auth.SessionStore;
import com.vnsearch.auth.User;
import com.vnsearch.auth.UserService;
import com.vnsearch.service.SearchEngineFacade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Nguồn dữ liệu cho <b>bảng điều khiển quản trị</b> của browser-app.
 *
 * <p>Nằm dưới {@code /api/admin/**} nên thừa hưởng nguyên vẹn lớp bảo vệ đã có:
 * {@code SecurityConfig} yêu cầu vai trò {@code ADMIN}, và vai trò đó chỉ được
 * cấp bởi {@code ApiKeyAuthFilter} khi header {@code X-API-Key} khớp. Không có
 * một dòng kiểm tra quyền nào trong lớp này — và đó là điều đúng: <b>phân
 * quyền được khai báo ở một chỗ duy nhất</b>, còn controller thì không nhắc
 * lại. Mỗi lần nhắc lại là một chỗ nữa có thể quên khi luật đổi.
 *
 * <p><b>Vì sao tách khỏi {@link AdminController}.</b> Lớp kia <i>điều khiển</i>
 * hệ thống — chạy crawl, lập chỉ mục lại; lớp này chỉ <i>quan sát</i>. Hai
 * nhóm có mức rủi ro rất khác nhau: một sai sót ở lớp kia đi tải nội dung từ
 * Internet về, còn ở lớp này thì nhiều nhất là hiện sai một con số. Giữ chúng
 * riêng để nếu sau này cần vai trò "chỉ đọc số liệu" (một người vận hành xem
 * bảng nhưng không được phép khởi động crawl) thì chỉ việc đổi luật cho đúng
 * đường dẫn này.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@Validated
public class AdminAnalyticsController {

    /** Số dòng mặc định cho mỗi bảng xếp hạng. */
    private static final int DEFAULT_TOP = 10;

    private final UsageAnalyticsService analytics;
    private final SearchEngineFacade facade;
    private final UserService users;
    private final SessionStore sessions;

    public AdminAnalyticsController(UsageAnalyticsService analytics, SearchEngineFacade facade,
                                     UserService users, SessionStore sessions) {
        this.analytics = analytics;
        this.facade = facade;
        this.users = users;
        this.sessions = sessions;
    }

    /**
     * Toàn bộ số liệu cho bảng điều khiển.
     *
     * @param top số dòng mỗi bảng xếp hạng, {@code 1..50}. Chặn trên vì đây là
     *            tham số do bên gọi chọn và mỗi dòng thêm là một phần tử nữa
     *            phải tuần tự hoá; không chặn thì {@code ?top=1000000} biến một
     *            endpoint hiển thị thành một phép cấp phát lớn.
     */
    @GetMapping
    public AdminDashboard dashboard(
            @RequestParam(value = "top", defaultValue = "" + DEFAULT_TOP)
            @Min(value = 1, message = "top phai >= 1")
            @Max(value = 50, message = "top toi da 50")
            int top) {
        return new AdminDashboard(
                Instant.now(),
                analytics.snapshot(top),
                facade.getCorpusStats(),
                new AdminDashboard.IndexStats(
                        facade.getIndexedDocumentCount(),
                        facade.getTermCount(),
                        facade.getIndexSizeBytes(),
                        facade.getCacheHitRate(),
                        facade.getScorerName(),
                        facade.getBloomFilterBits()),
                accountStats());
    }

    private AdminDashboard.AccountStats accountStats() {
        List<User> all = users.findAll();
        return new AdminDashboard.AccountStats(
                all.size(),
                (int) all.stream().filter(user -> user.role() == Role.ADMIN).count(),
                (int) all.stream().filter(user -> !user.enabled()).count(),
                sessions.activeCount());
    }

    /**
     * Xoá sạch số liệu sử dụng.
     *
     * <p>Có nút này vì số liệu nằm trong bộ nhớ và một buổi thử nghiệm (hoặc
     * một lần demo) làm lệch hẳn mọi tỉ lệ. Chỉ xoá phần <i>traffic</i>: khối
     * crawl và khối chỉ mục là trạng thái dẫn xuất từ corpus, không phải thứ
     * lớp này được phép vứt đi.
     *
     * <p>{@code POST} chứ không phải {@code GET}: đây là thao tác có hậu quả,
     * và một thao tác có hậu quả không được nằm sau một đường dẫn mà trình
     * duyệt hay công cụ giám sát có thể gọi khi chỉ định đọc.
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        analytics.reset();
        return ResponseEntity.noContent().build();
    }
}
