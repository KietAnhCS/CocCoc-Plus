package com.vnsearch.config;

import com.vnsearch.auth.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /**
     * Vi pham rang buoc dat tren THAM SO cua phuong thuc controller —
     * {@code @RequestParam @Max(50) int top} chang han, tren mot lop co
     * {@code @Validated}.
     *
     * <p>Khong bat rieng thi loai nay roi xuong {@link #handleGeneric} va tra
     * ve <b>500</b>: mot dau vao sai cua nguoi goi bi bao thanh loi cua may
     * chu. Hau qua khong chi la ma trang thai xau — nguoi goi khong biet minh
     * gui sai nen se thu lai y nguyen, con nguoi van hanh thi thay bao dong
     * loi 5xx cho mot chuyen khong phai su co.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> lastNode(violation.getPropertyPath().toString())
                        + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return errorResponse(HttpStatus.BAD_REQUEST,
                detail.isBlank() ? "Tham so gui len khong hop le" : detail, null);
    }

    /**
     * {@code dashboard.top} -&gt; {@code top}.
     *
     * <p>Duong dan day du cua Bean Validation gom ca ten phuong thuc controller
     * — mot chi tiet noi bo khong giup nguoi goi sua request, va la thu khong
     * nen phoi ra ngoai.
     */
    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }

    /**
     * Sai thong tin dang nhap, hoac tai khoan dang bi khoa tam -> <b>401</b>.
     *
     * <p>Bat TRUOC {@link UserService.AuthException} (lop cha) vi Spring chon
     * handler khop CU THE nhat; de chung mot cho thi mot lan dang nhap sai se
     * tra 400 — ma 400 noi "request cua ban sai dinh dang", khong phai "thong
     * tin dang nhap khong dung".
     *
     * <p>Thong bao lay nguyen tu ngoai le va no CO Y mo ho ("ten tai khoan
     * hoac mat khau khong dung") — xem Javadoc {@link UserService}.
     */
    @ExceptionHandler(UserService.InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(
            UserService.InvalidCredentialsException e) {
        return errorResponse(HttpStatus.UNAUTHORIZED, e.getMessage(), null);
    }

    /** Vi pham luat nghiep vu cua tang tai khoan (ten da ton tai, mat khau ngan...). */
    @ExceptionHandler(UserService.AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(UserService.AuthException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    /**
     * Goi dung duong dan nhung SAI PHUONG THUC -> <b>405</b>, kem danh sach
     * phuong thuc duoc phep.
     *
     * <p>Khong bat rieng thi loai nay roi xuong {@link #handleGeneric} va tra
     * <b>500</b> — bao rang may chu hong, trong khi thuc te no dang chay dung.
     * Day la lan thu hai cung mot khuon loi xuat hien trong lop nay (lan truoc
     * la {@link ConstraintViolationException}), va ca hai deu co chung goc:
     * <i>mot nhanh bat-tat-ca cho 500 se nuot moi ngoai le cua Spring MVC von
     * da mang san mot ma trang thai dung</i>.
     *
     * <p>Phat hien duoc khi goi {@code DELETE} vao mot ban may chu chua co
     * endpoint do: dang le 405, nhan ve 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        // Goi getSupportedHttpMethods() MOT lan roi giu lai. Ban dau ham nay
        // goi hai lan — kiem null o lan mot, dung ket qua o lan hai — va
        // SpotBugs bat dung: khong co gi bao dam hai lan goi tra ve cung mot
        // thu, nen phep kiem null o lan dau khong bao ve duoc lan sau.
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        String allowed = supported == null ? ""
                : supported.stream().map(Object::toString).collect(Collectors.joining(", "));
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED,
                "Phuong thuc " + e.getMethod() + " khong duoc ho tro cho duong dan nay."
                        + (allowed.isBlank() ? "" : " Cho phep: " + allowed + "."),
                null);
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
