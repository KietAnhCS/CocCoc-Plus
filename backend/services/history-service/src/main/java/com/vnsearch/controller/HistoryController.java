package com.vnsearch.controller;

import com.vnsearch.config.CallerIdentity;
import com.vnsearch.history.HistoryService;
import com.vnsearch.history.SearchQueryDocument;
import com.vnsearch.history.VisitDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Lịch sử duyệt web và lịch sử tìm kiếm của <b>chính người đang gọi</b>.
 *
 * <h2>Không endpoint nào nhận tên tài khoản làm tham số</h2>
 *
 * <p>Đây là quy tắc quan trọng nhất của cả lớp. Một endpoint kiểu
 * {@code GET /api/history?username=...} cho phép bất kỳ ai đọc lịch sử duyệt
 * web của bất kỳ ai — <i>A01:2021 — Broken Access Control</i>, và với đúng
 * loại dữ liệu này thì hậu quả không chỉ là rò rỉ kỹ thuật: lịch sử duyệt web
 * nói ra nơi làm việc, tình trạng sức khoẻ, quan điểm chính trị của một người.
 *
 * <p>Danh tính LUÔN lấy từ claim {@code sub} của JWT qua
 * {@link CallerIdentity#required()}. Người gọi không sửa được nó, vì nó được
 * chữ ký RS256 bảo vệ.
 *
 * <h2>Chế độ ẩn danh không đi qua đây</h2>
 *
 * <p>Không có tham số nào bật/tắt ẩn danh. Ẩn danh nghĩa là máy khách
 * <b>không gửi gì</b> lên — nếu nó vẫn gửi rồi nhờ máy chủ "đừng lưu", thì lời
 * hứa riêng tư phụ thuộc vào việc máy chủ có làm đúng hay không, và người dùng
 * không có cách nào kiểm chứng. Trường {@code incognito} trong
 * {@link VisitDocument} chỉ tồn tại để một bản ghi lọt vào trở thành thứ đếm
 * được.
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    /** Thân request ghi một lượt ghé thăm. */
    public record VisitRequest(
            @NotBlank(message = "Thiếu địa chỉ trang")
            @Size(max = 2048, message = "Địa chỉ quá dài")
            String url,

            @Size(max = 512, message = "Tiêu đề quá dài")
            String title) {
    }

    /** Thân request ghi một truy vấn tìm kiếm. */
    public record SearchRequest(
            @NotBlank(message = "Thiếu truy vấn")
            @Size(max = 200, message = "Truy vấn quá dài")
            String query,

            int resultCount) {
    }

    // ------------------------------------------------------------ lượt ghé

    @PostMapping("/visits")
    public ResponseEntity<VisitDocument> ghiLuotGhe(@Valid @RequestBody VisitRequest request) {
        VisitDocument ghiDuoc = history.ghiLuotGhe(
                CallerIdentity.required(), request.url(), request.title(), false);
        return ghiDuoc == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CREATED).body(ghiDuoc);
    }

    /**
     * Danh sách lịch sử, có thể lọc theo từ khoá hoặc theo khoảng thời gian.
     *
     * @param q    tìm trong tiêu đề và địa chỉ; bỏ trống thì lấy tất cả
     * @param from mốc đầu (ISO-8601); phải đi cùng {@code to}
     * @param to   mốc cuối (ISO-8601)
     */
    @GetMapping("/visits")
    public Page<VisitDocument> lichSu(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return history.lichSu(CallerIdentity.required(), q, from, to, page, size);
    }

    /**
     * Xoá một mục.
     *
     * <p>Trả {@code 404} khi không xoá được gì — và {@code deleteByIdAndUsername}
     * bảo đảm rằng "không xoá được gì" gộp chung hai trường hợp: mục không tồn
     * tại, và mục thuộc về người khác. Phân biệt hai trường hợp đó cho người
     * gọi biết là để lộ rằng một id nào đó có thật.
     */
    @DeleteMapping("/visits/{id}")
    public ResponseEntity<Void> xoaMotMuc(@PathVariable String id) {
        return history.xoaMotLuotGhe(CallerIdentity.required(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Xoá lịch sử theo khoảng thời gian — nút "Xoá dữ liệu duyệt web".
     *
     * <p>Bỏ trống cả hai mốc nghĩa là <b>xoá sạch</b>. Đó là hành vi mà người
     * dùng mong đợi từ một nút mang tên như vậy, nhưng nó cũng là một thao tác
     * không hoàn tác được — nên giao diện phải hỏi lại trước khi gọi, và mọi
     * lần gọi đều được ghi log ở {@code HistoryService}.
     */
    @DeleteMapping("/visits")
    public Map<String, Object> xoaTheoKhoang(
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        return Map.of("deleted", history.xoaTheoKhoang(CallerIdentity.required(), from, to));
    }

    // ------------------------------------------------------- lịch sử tìm kiếm

    @PostMapping("/searches")
    public ResponseEntity<SearchQueryDocument> ghiTruyVan(
            @Valid @RequestBody SearchRequest request) {
        SearchQueryDocument ghiDuoc = history.ghiTruyVan(
                CallerIdentity.required(), request.query(), request.resultCount());
        return ghiDuoc == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CREATED).body(ghiDuoc);
    }

    @GetMapping("/searches")
    public Page<SearchQueryDocument> lichSuTimKiem(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return history.lichSuTimKiem(CallerIdentity.required(), page, size);
    }

    /**
     * Gợi ý cho ô địa chỉ, lấy từ lịch sử tìm kiếm của chính người này.
     *
     * <p>Khác {@code GET /api/suggest} của search-service: bên kia gợi ý từ
     * <i>chỉ mục</i> (những gì tồn tại trên web), bên này gợi ý từ <i>ký ức</i>
     * (những gì người này đã tìm). Giao diện hiển thị cả hai, và nhóm của
     * người dùng đứng trước — thứ họ đã tìm gần như luôn đúng ý hơn.
     */
    @GetMapping("/searches/suggest")
    public List<SearchQueryDocument> goiY(
            @RequestParam("prefix") String prefix,
            @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return history.goiY(CallerIdentity.required(), prefix, limit);
    }

    /** Số mục đang có — giao diện hiện "Xoá 1.234 mục?" trước khi xác nhận. */
    @GetMapping("/summary")
    public Map<String, Object> tomTat() {
        return Map.of("visits", history.demLuotGhe(CallerIdentity.required()));
    }
}
