package com.vnsearch.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

/**
 * Giới hạn tần suất theo <b>người dùng</b>, không theo địa chỉ IP.
 *
 * <h2>Vì sao không theo IP</h2>
 *
 * <p>Đếm theo IP sai ở cả hai chiều, và cả hai đều gây hại thật:
 *
 * <ul>
 *   <li><b>Quá chặt.</b> Một trường đại học, một toà nhà văn phòng, hay một
 *       nhà mạng dùng NAT đều đẩy hàng nghìn người ra sau MỘT địa chỉ. Một
 *       người bấm nhanh sẽ chặn tất cả những người còn lại — và người bị chặn
 *       không hiểu vì sao, vì họ chưa làm gì cả.</li>
 *   <li><b>Quá lỏng.</b> Một kẻ tấn công có sẵn dải địa chỉ, hoặc chỉ cần một
 *       máy chủ đám mây rẻ tiền, thì đổi IP là được cấp lại hạn ngạch.</li>
 * </ul>
 *
 * <p>Đếm theo {@code sub} trong JWT thì hạn ngạch bám theo <i>tài khoản</i>:
 * đổi IP không giúp gì, và người ngồi cạnh không bị vạ lây.
 *
 * <h2>Khách chưa đăng nhập thì đếm theo gì</h2>
 *
 * <p>Không có tài khoản thì không còn lựa chọn nào ngoài địa chỉ, và ta quay
 * lại đúng hai khuyết điểm trên. Chấp nhận được vì hai lý do: phần lớn lưu
 * lượng nặng đến từ người đã đăng nhập, và các endpoint công khai
 * ({@code /api/search}) chỉ đọc — chúng không thay đổi dữ liệu của ai.
 *
 * <p>Khoá có tiền tố {@code ip:} để một tài khoản tên trùng một địa chỉ IP
 * không dùng chung gáo token với nó. Nghe khó xảy ra, nhưng đây là loại va
 * chạm không bao giờ được phát hiện bằng cách nhìn — nó chỉ hiện ra dưới dạng
 * "thỉnh thoảng bị 429 vô cớ".
 */
@Configuration
public class RateLimitConfig {

    /**
     * Tên bean này được trích dẫn trong {@code application.yaml}
     * ({@code key-resolver: "#{@nguoiDungHoacDiaChi}"}), nên <b>đổi tên hàm là
     * làm hỏng cấu hình</b> — và hỏng lúc chạy, không phải lúc biên dịch:
     * Spring báo không tìm thấy bean khi tuyến đầu tiên được gọi tới.
     */
    @Bean
    public KeyResolver nguoiDungHoacDiaChi() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(authentication -> authentication != null
                        && authentication.isAuthenticated())
                .map(authentication -> "user:" + authentication.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    var remote = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (remote == null ? "khong-ro" : remote.getHostString());
                }));
    }
}
