package com.vnsearch.controller;

import com.vnsearch.config.CallerIdentity;
import com.vnsearch.config.GlobalExceptionHandler;
import com.vnsearch.downloads.DownloadRecord;
import com.vnsearch.downloads.DownloadService;
import com.vnsearch.downloads.DownloadState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sổ tải xuống của <b>chính người đang gọi</b>.
 *
 * <p>Như {@code HistoryController}: không endpoint nào nhận tên tài khoản làm
 * tham số. Danh tính LUÔN lấy từ claim {@code sub} của JWT.
 *
 * <h2>Về header {@code X-Device-Id}</h2>
 *
 * <p>Đây KHÔNG phải một cơ chế bảo mật và không được dùng như thế: máy khách
 * tự sinh nó và tự khai. Nó chỉ để trả lời câu hỏi hiển thị "tệp này nằm trên
 * máy nào" — sai thì giao diện hiện nhầm một dòng chữ, không ai đọc được dữ
 * liệu của ai.
 */
@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    private final DownloadService downloads;

    public DownloadController(DownloadService downloads) {
        this.downloads = downloads;
    }

    /**
     * Thân request bắt đầu theo dõi một lượt tải.
     *
     * @param id UUID do MÁY KHÁCH sinh. Đây là khoá idempotent: gửi lại cùng
     *           id sau khi mất mạng sẽ không tạo bản ghi thứ hai.
     */
    public record BatDauRequest(
            @NotBlank(message = "Thiếu id") String id,
            @NotBlank(message = "Thiếu địa chỉ nguồn") String sourceUrl,
            @NotBlank(message = "Thiếu tên tệp")
            @Size(max = 255, message = "Tên tệp quá dài") String fileName,
            @Size(max = 255) String mimeType,
            Long totalBytes,
            String localPath) {
    }

    /** Thân request cập nhật. Mọi trường đều tuỳ chọn: chỉ gửi thứ thay đổi. */
    public record CapNhatRequest(Long receivedBytes, DownloadState state, String localPath) {
    }

    @PostMapping
    public ResponseEntity<DownloadRecord.PublicView> batDau(
            @Valid @RequestBody BatDauRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {

        DownloadRecord record = downloads.batDau(CallerIdentity.required(),
                UUID.fromString(request.id()), request.sourceUrl(), request.fileName(),
                request.mimeType(), request.totalBytes(), request.localPath(), deviceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(record.toPublic(deviceId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DownloadRecord.PublicView> capNhat(
            @PathVariable String id,
            @RequestBody CapNhatRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {

        return downloads.capNhat(CallerIdentity.required(), UUID.fromString(id),
                        request.receivedBytes(), request.state(), request.localPath())
                .map(record -> ResponseEntity.ok(record.toPublic(deviceId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<DownloadRecord.PublicView> danhSach(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return downloads.danhSach(CallerIdentity.required(), page, size).stream()
                .map(record -> record.toPublic(deviceId))
                .toList();
    }

    /** Những lượt còn đang tải — giao diện hỏi riêng để vẽ thanh tiến độ. */
    @GetMapping("/active")
    public List<DownloadRecord.PublicView> dangChay(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return downloads.dangChay(CallerIdentity.required()).stream()
                .map(record -> record.toPublic(deviceId))
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> xoa(@PathVariable String id) {
        return downloads.xoa(CallerIdentity.required(), UUID.fromString(id))
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Xoá sổ tải xuống. Chỉ xoá mục ĐÃ KẾT THÚC — xem {@code DownloadRepository.xoaHet}. */
    @DeleteMapping
    public Map<String, Object> xoaHet() {
        return Map.of("deleted", downloads.xoaHet(CallerIdentity.required()));
    }

    @GetMapping("/summary")
    public Map<String, Object> tomTat() {
        return Map.of("total", downloads.dem(CallerIdentity.required()));
    }

    /**
     * Chuyển trạng thái vô lý → <b>409 Conflict</b>, không phải 400.
     *
     * <p>400 nói "request của bạn sai định dạng" — không đúng: request hoàn
     * toàn hợp lệ, chỉ là nó đến vào lúc bản ghi đang ở một trạng thái không
     * cho phép. 409 nói đúng điều đó, và máy khách đọc được nó để biết nên bỏ
     * qua gói tin đến trễ thay vì thử lại mãi.
     */
    @ExceptionHandler(DownloadService.ChuyenTrangThaiKhongHopLe.class)
    public ResponseEntity<Map<String, Object>> chuyenTrangThaiSai(
            DownloadService.ChuyenTrangThaiKhongHopLe e) {
        return GlobalExceptionHandler.errorResponse(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    /**
     * {@code id} không phải UUID → 400, không phải 500.
     *
     * <p>{@code UUID.fromString} ném {@link IllegalArgumentException}, và không
     * bắt thì nó rơi xuống nhánh bắt-tất-cả và thành 500 — báo rằng máy chủ
     * hỏng, trong khi thực tế người gọi vừa gửi một chuỗi không hợp lệ.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> idKhongHopLe(IllegalArgumentException e) {
        return GlobalExceptionHandler.errorResponse(HttpStatus.BAD_REQUEST,
                "Mã tải xuống không hợp lệ (phải là UUID).", null);
    }
}
