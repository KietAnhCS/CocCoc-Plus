package com.vnsearch.history;

import com.vnsearch.config.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Nghiệp vụ lịch sử: ghi, tìm, xoá.
 *
 * <p>Controller ở tầng trên chỉ dịch HTTP; mọi quyết định về dữ liệu nằm ở đây.
 * Ranh giới đó khiến các luật dưới đây kiểm thử được mà không cần dựng một
 * máy chủ web.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    /** Trần cứng cho mọi truy vấn phân trang, kể cả khi người gọi xin nhiều hơn. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Trần độ dài một URL được ghi. Dài hơn thì gần như chắc chắn là rác. */
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_QUERY_LENGTH = 200;

    private final VisitRepository visits;
    private final SearchQueryRepository queries;
    private final AuditLogger audit;
    private final Clock clock;

    public HistoryService(VisitRepository visits, SearchQueryRepository queries, AuditLogger audit) {
        this.visits = visits;
        this.queries = queries;
        this.audit = audit;
        this.clock = Clock.systemUTC();
    }

    // ------------------------------------------------------------ ghi

    /**
     * Ghi một lượt ghé thăm, GỘP với lần trước nếu cùng URL.
     *
     * <p><b>Vì sao gộp thay vì thêm dòng mới mỗi lần.</b> Một người mở lại
     * trang chủ báo mười lần trong ngày sẽ tạo mười bản ghi giống hệt nhau,
     * đẩy mọi thứ khác ra khỏi màn hình lịch sử. Trình duyệt thật cũng gộp, và
     * người dùng mong đợi điều đó.
     *
     * <p>Đổi lại: mất thứ tự chính xác của các lần ghé. Chấp nhận được — câu
     * hỏi người ta đặt ra với lịch sử là "tôi đã vào trang nào", không phải
     * "tôi vào lúc 9:03 hay 9:07".
     */
    public VisitDocument recordVisit(String username, String url, String title, boolean incognito) {
        if (incognito) {
            // Đường này lẽ ra không bao giờ tới đây: chế độ ẩn danh nghĩa là
            // máy khách KHÔNG gửi gì lên. Ghi log ở mức WARN để nếu nó xảy ra
            // thì đếm được, thay vì âm thầm lưu lại thứ người dùng tin là
            // không được lưu.
            log.warn("Từ chối ghi lịch sử cho một lượt ghé đánh dấu ẩn danh (tài khoản={})",
                    username);
            return null;
        }
        String cleanUrl = truncate(url, MAX_URL_LENGTH);
        if (cleanUrl == null || cleanUrl.isBlank()) {
            return null;
        }
        Instant now = clock.instant();

        Optional<VisitDocument> existing = visits.findByUsernameAndUrl(username, cleanUrl);
        if (existing.isPresent()) {
            VisitDocument previous = existing.get();
            return visits.save(new VisitDocument(previous.id(), username, cleanUrl,
                    truncate(title, MAX_TITLE_LENGTH), previous.host(), now, previous.visitCount() + 1, false));
        }
        return visits.save(new VisitDocument(null, username, cleanUrl,
                truncate(title, MAX_TITLE_LENGTH), hostOf(cleanUrl), now, 1, false));
    }

    /**
     * Ghi một truy vấn tìm kiếm.
     *
     * <p>Cũng gộp theo bản chuẩn hoá: gõ lại đúng một truy vấn chỉ cập nhật
     * thời điểm, không thêm dòng. Nhờ vậy phần gợi ý luôn hiện những truy vấn
     * <i>khác nhau</i>, thay vì mười bản sao của cùng một chữ.
     */
    public SearchQueryDocument recordSearch(String username, String query, int resultCount) {
        String clean = truncate(query, MAX_QUERY_LENGTH);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        String normalized = normalize(clean);
        Instant now = clock.instant();

        return queries.findByUsernameAndNormalized(username, normalized)
                .map(previous -> queries.save(new SearchQueryDocument(previous.id(), username, clean,
                        normalized, resultCount, now)))
                .orElseGet(() -> queries.save(new SearchQueryDocument(null, username, clean,
                        normalized, resultCount, now)));
    }

    // ------------------------------------------------------------ đọc

    public Page<VisitDocument> visitHistory(String username, String keyword,
                                            Instant from, Instant to, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clamp(size));

        if (keyword != null && !keyword.isBlank()) {
            return visits.searchByKeyword(username, quoteRegex(keyword), pageable);
        }
        if (from != null && to != null) {
            return visits.findByUsernameAndVisitedAtBetweenOrderByVisitedAtDesc(
                    username, from, to, pageable);
        }
        return visits.findByUsernameOrderByVisitedAtDesc(username, pageable);
    }

    /**
     * Gợi ý cho ô địa chỉ, lấy từ những gì người này đã tìm.
     *
     * <p>Tiền tố được <b>neo đầu dòng</b> ({@code ^}) và <b>thoát ký tự đặc
     * biệt</b> trước khi thành biểu thức chính quy. Bỏ phép thoát thì một
     * người gõ {@code (a+)+$} sẽ khiến Mongo chạy một biểu thức có độ phức tạp
     * hàm mũ — <i>ReDoS</i>, và nó làm treo cả tiến trình CSDL chứ không chỉ
     * request đó.
     */
    public List<SearchQueryDocument> suggest(String username, String prefix, int size) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        String pattern = "^" + quoteRegex(normalize(prefix));
        return queries.suggestByPrefix(username, pattern, PageRequest.of(0, clamp(size)));
    }

    public Page<SearchQueryDocument> searchHistory(String username, int page, int size) {
        return queries.findByUsernameOrderBySearchedAtDesc(username,
                PageRequest.of(Math.max(page, 0), clamp(size)));
    }

    // ------------------------------------------------------------ xoá

    public boolean deleteVisit(String username, String id) {
        boolean deleted = visits.deleteByIdAndUsername(id, username) > 0;
        if (deleted) {
            audit.record(username, "HISTORY_DELETE_ONE", "visits:" + id, "SUCCESS", null);
        }
        return deleted;
    }

    /**
     * Xoá theo khoảng thời gian — cái nút "Xoá dữ liệu duyệt web" gọi tới đây.
     *
     * <p>{@code from} rỗng nghĩa là "từ đầu": dùng {@link Instant#EPOCH} thay
     * vì một nhánh {@code if} riêng, để chỉ có MỘT đường đi qua hàm này. Hai
     * nhánh cho cùng một thao tác xoá là hai chỗ có thể sai, và cái sai ở đây
     * xoá dữ liệu của người dùng.
     */
    public long deleteRange(String username, Instant from, Instant to) {
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? clock.instant() : to;
        long visitCount = visits.deleteByUsernameAndVisitedAtBetween(username, start, end);
        long queryCount = queries.deleteByUsernameAndSearchedAtBetween(username, start, end);

        // Ghi lại MỌI lần xoá hàng loạt. Đây là dữ liệu cá nhân bị huỷ vĩnh
        // viễn: khi người dùng hỏi "vì sao lịch sử của tôi biến mất", dòng log
        // này là câu trả lời duy nhất.
        log.info("Xoá lịch sử của {}: {} lượt ghé, {} truy vấn, trong khoảng {} .. {}",
                username, visitCount, queryCount, start, end);
        audit.record(username, "HISTORY_DELETE_RANGE", "visits+searches:" + username, "SUCCESS",
                "deleted=" + (visitCount + queryCount));
        return visitCount + queryCount;
    }

    public long countVisits(String username) {
        return visits.countByUsername(username);
    }

    // ------------------------------------------------------------ tiện ích

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Biến một chuỗi do người dùng nhập thành chuỗi khớp <b>đúng theo nghĩa
     * đen</b> trong biểu thức chính quy.
     *
     * <p>{@link Pattern#quote} sinh {@code \\Q...\\E}, và Mongo hiểu cú pháp
     * đó. Đây là ranh giới giữa "tìm chữ (a+)+" và "chạy một biểu thức có độ
     * phức tạp hàm mũ trên máy chủ CSDL".
     */
    private static String quoteRegex(String value) {
        return Pattern.quote(value);
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "");
        } catch (URISyntaxException e) {
            // URL hỏng vẫn được ghi lại — người dùng có thể muốn xem lại chính
            // cái địa chỉ hỏng đó. Chỉ là không tách được host.
            return "";
        }
    }
}
