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
    private static final int SIZE_TOI_DA = 200;

    /** Trần độ dài một URL được ghi. Dài hơn thì gần như chắc chắn là rác. */
    private static final int URL_TOI_DA = 2048;
    private static final int TITLE_TOI_DA = 512;
    private static final int QUERY_TOI_DA = 200;

    private final VisitRepository visits;
    private final SearchQueryRepository queries;
    private final AuditLogger audit;
    private final Clock clock;

    public HistoryService(VisitRepository visits, SearchQueryRepository queries, AuditLogger audit) {
        this(visits, queries, audit, Clock.systemUTC());
    }

    HistoryService(VisitRepository visits, SearchQueryRepository queries, AuditLogger audit, Clock clock) {
        this.visits = visits;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
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
    public VisitDocument ghiLuotGhe(String username, String url, String title, boolean incognito) {
        if (incognito) {
            // Đường này lẽ ra không bao giờ tới đây: chế độ ẩn danh nghĩa là
            // máy khách KHÔNG gửi gì lên. Ghi log ở mức WARN để nếu nó xảy ra
            // thì đếm được, thay vì âm thầm lưu lại thứ người dùng tin là
            // không được lưu.
            log.warn("Từ chối ghi lịch sử cho một lượt ghé đánh dấu ẩn danh (tài khoản={})",
                    username);
            return null;
        }
        String urlSach = catNgan(url, URL_TOI_DA);
        if (urlSach == null || urlSach.isBlank()) {
            return null;
        }
        Instant bayGio = clock.instant();

        Optional<VisitDocument> daCo = visits.findByUsernameAndUrl(username, urlSach);
        if (daCo.isPresent()) {
            VisitDocument cu = daCo.get();
            return visits.save(new VisitDocument(cu.id(), username, urlSach,
                    catNgan(title, TITLE_TOI_DA), cu.host(), bayGio, cu.visitCount() + 1, false));
        }
        return visits.save(new VisitDocument(null, username, urlSach,
                catNgan(title, TITLE_TOI_DA), hostCua(urlSach), bayGio, 1, false));
    }

    /**
     * Ghi một truy vấn tìm kiếm.
     *
     * <p>Cũng gộp theo bản chuẩn hoá: gõ lại đúng một truy vấn chỉ cập nhật
     * thời điểm, không thêm dòng. Nhờ vậy phần gợi ý luôn hiện những truy vấn
     * <i>khác nhau</i>, thay vì mười bản sao của cùng một chữ.
     */
    public SearchQueryDocument ghiTruyVan(String username, String query, int resultCount) {
        String sach = catNgan(query, QUERY_TOI_DA);
        if (sach == null || sach.isBlank()) {
            return null;
        }
        String chuanHoa = chuanHoa(sach);
        Instant bayGio = clock.instant();

        return queries.findByUsernameAndNormalized(username, chuanHoa)
                .map(cu -> queries.save(new SearchQueryDocument(cu.id(), username, sach,
                        chuanHoa, resultCount, bayGio)))
                .orElseGet(() -> queries.save(new SearchQueryDocument(null, username, sach,
                        chuanHoa, resultCount, bayGio)));
    }

    // ------------------------------------------------------------ đọc

    public Page<VisitDocument> lichSu(String username, String tuKhoa,
                                      Instant from, Instant to, int trang, int soLuong) {
        Pageable pageable = PageRequest.of(Math.max(trang, 0), gioiHan(soLuong));

        if (tuKhoa != null && !tuKhoa.isBlank()) {
            return visits.timTheoTuKhoa(username, thoatRegex(tuKhoa), pageable);
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
    public List<SearchQueryDocument> goiY(String username, String tienTo, int soLuong) {
        if (tienTo == null || tienTo.isBlank()) {
            return List.of();
        }
        String mau = "^" + thoatRegex(chuanHoa(tienTo));
        return queries.goiYTheoTienTo(username, mau, PageRequest.of(0, gioiHan(soLuong)));
    }

    public Page<SearchQueryDocument> lichSuTimKiem(String username, int trang, int soLuong) {
        return queries.findByUsernameOrderBySearchedAtDesc(username,
                PageRequest.of(Math.max(trang, 0), gioiHan(soLuong)));
    }

    // ------------------------------------------------------------ xoá

    public boolean xoaMotLuotGhe(String username, String id) {
        boolean xoaDuoc = visits.deleteByIdAndUsername(id, username) > 0;
        if (xoaDuoc) {
            audit.record(username, "HISTORY_DELETE_ONE", "visits:" + id, "SUCCESS", null);
        }
        return xoaDuoc;
    }

    /**
     * Xoá theo khoảng thời gian — cái nút "Xoá dữ liệu duyệt web" gọi tới đây.
     *
     * <p>{@code from} rỗng nghĩa là "từ đầu": dùng {@link Instant#EPOCH} thay
     * vì một nhánh {@code if} riêng, để chỉ có MỘT đường đi qua hàm này. Hai
     * nhánh cho cùng một thao tác xoá là hai chỗ có thể sai, và cái sai ở đây
     * xoá dữ liệu của người dùng.
     */
    public long xoaTheoKhoang(String username, Instant from, Instant to) {
        Instant batDau = from == null ? Instant.EPOCH : from;
        Instant ketThuc = to == null ? clock.instant() : to;
        long soVisit = visits.deleteByUsernameAndVisitedAtBetween(username, batDau, ketThuc);
        long soQuery = queries.deleteByUsernameAndSearchedAtBetween(username, batDau, ketThuc);

        // Ghi lại MỌI lần xoá hàng loạt. Đây là dữ liệu cá nhân bị huỷ vĩnh
        // viễn: khi người dùng hỏi "vì sao lịch sử của tôi biến mất", dòng log
        // này là câu trả lời duy nhất.
        log.info("Xoá lịch sử của {}: {} lượt ghé, {} truy vấn, trong khoảng {} .. {}",
                username, soVisit, soQuery, batDau, ketThuc);
        audit.record(username, "HISTORY_DELETE_RANGE", "visits+searches:" + username, "SUCCESS",
                "deleted=" + (soVisit + soQuery));
        return soVisit + soQuery;
    }

    public long demLuotGhe(String username) {
        return visits.countByUsername(username);
    }

    // ------------------------------------------------------------ tiện ích

    private static int gioiHan(int soLuong) {
        return Math.min(Math.max(soLuong, 1), SIZE_TOI_DA);
    }

    private static String catNgan(String value, int toiDa) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= toiDa ? trimmed : trimmed.substring(0, toiDa);
    }

    private static String chuanHoa(String value) {
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
    private static String thoatRegex(String value) {
        return Pattern.quote(value);
    }

    private static String hostCua(String url) {
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
