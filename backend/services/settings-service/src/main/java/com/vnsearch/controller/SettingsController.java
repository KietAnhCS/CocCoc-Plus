package com.vnsearch.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnsearch.config.AuditLogger;
import com.vnsearch.config.CallerIdentity;
import com.vnsearch.config.GlobalExceptionHandler;
import com.vnsearch.settings.SettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Tuỳ chọn của <b>chính người đang gọi</b>, đồng bộ giữa các thiết bị.
 *
 * <pre>
 *   GET    /api/settings          đọc toàn bộ khối
 *   PATCH  /api/settings          GỘP: chỉ gửi những khoá thay đổi
 *   PUT    /api/settings          THAY THẾ: khoá không gửi sẽ biến mất
 *   DELETE /api/settings/{khoa}   xoá một khoá
 *   DELETE /api/settings          khôi phục mặc định (xoá cả dòng)
 * </pre>
 *
 * <h2>Chống ghi đè giữa nhiều thiết bị</h2>
 *
 * <p>Máy khách gửi kèm {@code If-Match: <version>} — số phiên bản nó đang giữ.
 * Máy chủ chỉ ghi khi số đó còn khớp; lệch thì trả <b>409 Conflict</b> kèm
 * khối hiện tại để máy khách gộp lại và thử tiếp.
 *
 * <p><b>Vì sao cần.</b> Không có nó, người dùng đổi chủ đề trên máy A, rồi máy
 * B (đang giữ bản cũ) đồng bộ lên và <i>xoá mất</i> thay đổi đó. Lỗi này không
 * bao giờ báo gì cả — nó chỉ hiện ra dưới dạng "cài đặt của tôi cứ tự quay về
 * như cũ", và gần như không thể tái hiện được khi đi tìm.
 *
 * <p>Header {@code If-Match} là TUỲ CHỌN. Bỏ trống nghĩa là "ghi đè, tôi biết
 * mình đang làm gì" — cần cho lần đồng bộ đầu tiên của một thiết bị mới, khi
 * nó chưa có phiên bản nào để so.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    /**
     * Trần độ dài thân request.
     *
     * <p>Chặn ở đây, TRƯỚC khi phân tích JSON — phân tích một chuỗi vài
     * megabyte đã tốn bộ nhớ rồi, kể cả khi sau đó ta từ chối nó. Ràng buộc
     * {@code ck_user_settings_size} ở CSDL là lớp chặn thứ hai; hai lớp vì lớp
     * này bảo vệ tiến trình còn lớp kia bảo vệ dữ liệu.
     */
    private static final int JSON_TOI_DA = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Khối rỗng trả về cho người chưa từng lưu tuỳ chọn nào. */
    private static final String RONG = "{}";

    private final SettingsRepository repository;
    private final AuditLogger audit;

    public SettingsController(SettingsRepository repository, AuditLogger audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** Ném khi thân request không phải một đối tượng JSON hợp lệ. */
    static class JsonKhongHopLe extends RuntimeException {
        JsonKhongHopLe(String message) {
            super(message);
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> doc() {
        String username = CallerIdentity.required();
        return repository.doc(username)
                .map(SettingsController::than)
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "settings", Map.of(),
                        "version", 0L)));
    }

    @PatchMapping
    public ResponseEntity<Map<String, Object>> gop(
            @RequestBody String than,
            @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        return ghi(than, expectedVersion, true);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> thayThe(
            @RequestBody String than,
            @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        return ghi(than, expectedVersion, false);
    }

    @DeleteMapping("/{khoa}")
    public ResponseEntity<Map<String, Object>> xoaKhoa(@PathVariable String khoa) {
        String username = CallerIdentity.required();
        return repository.xoaKhoa(username, khoa)
                .map(snapshot -> {
                    audit.record(username, "SETTINGS_DELETE_KEY", "user_settings:" + username,
                            "SUCCESS", "khoa=" + khoa);
                    return than(snapshot);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Khôi phục mặc định: xoá hẳn dòng, lần đọc sau trả về khối rỗng. */
    @DeleteMapping
    public ResponseEntity<Void> khoiPhucMacDinh() {
        String username = CallerIdentity.required();
        repository.xoaHet(username);
        audit.record(username, "SETTINGS_RESET", "user_settings:" + username, "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, Object>> ghi(String than, Long expectedVersion,
                                                     boolean gop) {
        String username = CallerIdentity.required();
        String json = kiemTra(than);

        Optional<SettingsRepository.Snapshot> ketQua = gop
                ? repository.gop(username, json, expectedVersion)
                : repository.thayThe(username, json, expectedVersion);

        if (ketQua.isEmpty()) {
            // Xung đột phiên bản. Trả kèm khối HIỆN TẠI để máy khách gộp lại
            // ngay mà không phải gọi thêm một lượt GET — với một thao tác chạy
            // nền như đồng bộ cài đặt, mỗi lượt gọi thừa là một lượt có thể
            // hỏng tiếp.
            SettingsRepository.Snapshot hienTai = repository.doc(username)
                    .orElse(new SettingsRepository.Snapshot(RONG, 0L, Instant.now()));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "conflict",
                    "message", "Thiết bị khác đã sửa tuỳ chọn. Hãy gộp rồi thử lại.",
                    "settings", doc(hienTai.json()),
                    "version", hienTai.version()));
        }
        audit.record(username, gop ? "SETTINGS_MERGE" : "SETTINGS_REPLACE",
                "user_settings:" + username, "SUCCESS", null);
        return than(ketQua.get());
    }

    /**
     * Xác nhận thân request là một ĐỐI TƯỢNG JSON, không phải mảng hay số.
     *
     * <p>Kiểm ở đây thay vì để CSDL từ chối, vì thông báo lỗi khác hẳn nhau:
     * ở đây nói được "phải là một đối tượng JSON", còn ràng buộc CHECK chỉ ném
     * một lỗi SQL mà người gọi API không hiểu nổi.
     */
    private static String kiemTra(String than) {
        if (than == null || than.isBlank()) {
            throw new JsonKhongHopLe("Thân request rỗng.");
        }
        if (than.length() > JSON_TOI_DA) {
            throw new JsonKhongHopLe("Khối tuỳ chọn quá lớn (tối đa "
                    + (JSON_TOI_DA / 1024) + " KB).");
        }
        try {
            JsonNode node = JSON.readTree(than);
            if (!node.isObject()) {
                throw new JsonKhongHopLe("Tuỳ chọn phải là một đối tượng JSON.");
            }
            return node.toString();
        } catch (JsonProcessingException e) {
            throw new JsonKhongHopLe("JSON không hợp lệ: " + e.getOriginalMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> than(
            SettingsRepository.Snapshot snapshot) {
        return ResponseEntity.ok()
                // ETag để máy khách gửi lại trong If-Match ở lần ghi sau. Đây
                // là vòng khép kín của phép khoá lạc quan: máy khách không phải
                // tự nhớ số phiên bản từ đâu ra.
                .eTag("\"" + snapshot.version() + "\"")
                .body(Map.of(
                        "settings", doc(snapshot.json()),
                        "version", snapshot.version(),
                        "updatedAt", snapshot.updatedAt()));
    }

    private static Object doc(String json) {
        try {
            return JSON.readTree(json == null ? RONG : json);
        } catch (JsonProcessingException e) {
            // Không thể xảy ra: chuỗi này vừa từ cột jsonb ra, và PostgreSQL
            // không lưu jsonb hỏng. Nếu xảy ra thì lược đồ đã bị sửa tay.
            throw new IllegalStateException("Cột settings chứa JSON hỏng", e);
        }
    }

    @ExceptionHandler(JsonKhongHopLe.class)
    public ResponseEntity<ProblemDetail> jsonHong(JsonKhongHopLe e) {
        return GlobalExceptionHandler.errorResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }
}
