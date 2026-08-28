package com.vnsearch.config;

import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Danh sách endpoint <b>công khai</b> của một service — thứ duy nhất mà mỗi
 * service phải tự khai khi dùng {@link ServiceSecurityConfig}.
 *
 * <p><b>Vì sao là danh sách CHO PHÉP chứ không phải danh sách CHẶN.</b> Mặc
 * định của {@link ServiceSecurityConfig} là chặn hết. Một endpoint mới quên
 * khai ở đây sẽ trả 401 ngay lần gọi đầu — hỏng to, thấy ngay, sửa một dòng.
 * Với danh sách chặn thì một endpoint mới quên khai sẽ <i>mở toang</i>, và
 * không có bài test nào phát hiện được điều đó, vì nó vẫn trả về đúng dữ liệu.
 *
 * <p>Bài học này đã trả giá một lần: {@code /api/images} trả 401 ở lần chạy
 * đầu vì thiếu tên nó trong danh sách cho phép. Đó chính là cổng chặn đang làm
 * đúng việc của mình.
 */
@FunctionalInterface
public interface PublicEndpoints {

    /** Các mẫu đường dẫn không cần đăng nhập. */
    List<RequestMatcher> matchers();

    /** Cú pháp gọn cho trường hợp thường gặp: một nhóm đường dẫn, mọi phương thức. */
    static PublicEndpoints of(String... antPatterns) {
        List<RequestMatcher> list = new java.util.ArrayList<>(antPatterns.length);
        for (String pattern : antPatterns) {
            list.add(new AntPathRequestMatcher(pattern));
        }
        return () -> List.copyOf(list);
    }

    /** Chỉ mở một phương thức trên một đường dẫn — xem {@code POST /api/events}. */
    static RequestMatcher method(String httpMethod, String antPattern) {
        return new AntPathRequestMatcher(antPattern, httpMethod);
    }
}
