package com.vnsearch.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Xu ly loi toan cuc cho tat ca REST controller, tra ve JSON thay vi Whitelabel
 * HTML mac dinh cua Spring.
 *
 * <p><b>Nguyen tac: loi cua NGUOI GOI thi noi ro, loi cua HE THONG thi giau.</b>
 *
 * <p>Truoc day moi ngoai le deu duoc tra nguyen van ra ngoai:
 * <pre>
 *   return errorResponse(INTERNAL_SERVER_ERROR, "Loi he thong: " + e.getMessage());
 * </pre>
 * {@code e.getMessage()} cua mot {@code SQLException} chua chuoi ket noi va ten
 * bang; cua mot {@code IOException} chua duong dan tuyet doi tren may chu. Do
 * la ban do mien phi cho nguoi dang do he thong.
 *
 * <p>Nay loi he thong tra ve mot <b>ma tham chieu</b> ngau nhien, con noi dung
 * day du thi vao log kem dung ma do. Nguoi van hanh tra log bang ma; nguoi
 * ngoai khong biet them gi. Nguoi dung bao loi van co thu de doc cho bo phan ho
 * tro — dieu ma mot cau "Da co loi xay ra" tran khong lam duoc.
 *
 * <p>Nguoc lai, loi do dau vao sai ({@link IllegalArgumentException},
 * {@link MethodArgumentNotValidException}) van duoc noi ro nguyen nhan: nguoi
 * goi can biet ho gui sai cho nao thi moi sua duoc, va thong tin do khong tiet
 * lo gi ve noi bo he thong.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException e) {
        return errorResponse(HttpStatus.BAD_REQUEST,
                "Thieu tham so bat buoc: " + e.getParameterName(), null);
    }

    /** Vi pham rang buoc {@code @Valid} tren request body — gom moi truong sai. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return errorResponse(HttpStatus.BAD_REQUEST,
                detail.isBlank() ? "Du lieu gui len khong hop le" : detail, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e, HttpServletRequest request) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        // Toan bo chi tiet — ke ca stack trace — o day, KHONG o phan hoi.
        log.error("Loi he thong [ma {}] khi xu ly {} {}",
                reference, request.getMethod(), request.getRequestURI(), e);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Da xay ra loi he thong. Vui long cung cap ma tham chieu khi bao loi.",
                reference);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status, String message, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? "" : message);
        if (reference != null) {
            body.put("reference", reference);
        }
        return ResponseEntity.status(status).body(body);
    }
}
