package com.vnsearch.config;

import com.vnsearch.auth.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Hai ngoại lệ của tầng tài khoản, dịch sang mã HTTP.
 *
 * <p><b>Vì sao tách khỏi {@link GlobalExceptionHandler}.</b> Hai handler này
 * từng nằm chung với phần xử lý lỗi dùng chung. Nhưng {@code UserService} nay
 * chỉ tồn tại trong auth-service, còn {@code GlobalExceptionHandler} nằm ở
 * module {@code platform} mà cả tám service đều nạp — giữ nguyên thì
 * {@code platform} phải phụ thuộc vào một service cụ thể, tức là mọi service
 * kéo theo mã nguồn tài khoản mà chúng không bao giờ chạm tới.
 *
 * <p><b>Vì sao KHÔNG kế thừa {@code GlobalExceptionHandler}.</b> Nghe thì gọn,
 * nhưng cả hai lớp đều mang {@code @RestControllerAdvice}: Spring sẽ đăng ký
 * CẢ HAI, và mọi handler của lớp cha xuất hiện hai lần cho cùng một kiểu ngoại
 * lệ. Kết quả là {@code IllegalStateException: Ambiguous @ExceptionHandler}
 * ngay lúc khởi động — hoặc tệ hơn, một handler thắng một cách ngẫu nhiên.
 * Thay vào đó, phần dùng chung duy nhất là hàm dựng thân lỗi
 * {@link GlobalExceptionHandler#errorResponse}, gọi tĩnh. Nhờ nó, hình dạng
 * JSON của lỗi 401 từ auth-service giống hệt lỗi 400 từ search-service — hai
 * dạng thân lỗi khác nhau trong cùng một API là thứ khiến phía giao diện phải
 * viết hai nhánh phân tích cho cùng một việc.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    /**
     * Sai thông tin đăng nhập, hoặc tài khoản đang bị khoá tạm → <b>401</b>.
     *
     * <p>Bắt TRƯỚC {@link UserService.AuthException} (lớp cha) vì Spring chọn
     * handler khớp CỤ THỂ nhất; để chung một chỗ thì một lần đăng nhập sai sẽ
     * trả 400 — mà 400 nói "request của bạn sai định dạng", không phải "thông
     * tin đăng nhập không đúng".
     *
     * <p>Thông báo lấy nguyên từ ngoại lệ và nó CÓ Ý mơ hồ ("tên tài khoản
     * hoặc mật khẩu không đúng") — xem Javadoc {@link UserService}.
     */
    @ExceptionHandler(UserService.InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(
            UserService.InvalidCredentialsException e) {
        return GlobalExceptionHandler.errorResponse(HttpStatus.UNAUTHORIZED, e.getMessage(), null);
    }

    /** Vi phạm luật nghiệp vụ của tầng tài khoản (tên đã tồn tại, mật khẩu ngắn...). */
    @ExceptionHandler(UserService.AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(UserService.AuthException e) {
        return GlobalExceptionHandler.errorResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }
}
