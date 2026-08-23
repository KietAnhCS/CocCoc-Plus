package com.vnsearch.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Chặn access token đã bị <b>thu hồi</b>, dù chữ ký vẫn hợp lệ và hạn vẫn còn.
 *
 * <h2>Vấn đề mà lớp này giải</h2>
 *
 * <p>JWT tự chứng thực: ai cầm nó cũng chứng minh được danh tính mà không cần
 * hỏi ai. Đó là ưu điểm — và cũng là lý do <b>không xoá được nó</b>. Người dùng
 * bấm "đăng xuất", tài khoản bị khoá, mật khẩu vừa đổi vì nghi bị chiếm: cả ba
 * trường hợp đều để lại một access token còn sống tới 15 phút nữa.
 *
 * <p>auth-service ghi {@code jti} của những token đó vào Redis với TTL bằng
 * đúng phần đời còn lại. Lớp này tra danh sách ấy.
 *
 * <h2>Vì sao đặt ở Gateway chứ không ở từng service</h2>
 *
 * <p>Đặt ở bảy service nghĩa là bảy lượt hỏi Redis cho một trang chạm nhiều
 * service, và bảy chỗ có thể quên hỏi. Đặt ở đây là một lượt, một chỗ.
 *
 * <p><b>Cái giá:</b> một service bị gọi thẳng (không qua Gateway) sẽ vẫn chấp
 * nhận token đã thu hồi cho tới khi nó hết hạn. Chấp nhận được vì đường gọi
 * thẳng chỉ tồn tại bên trong mạng nội bộ, và cửa sổ tối đa là 15 phút. Ghi ra
 * đây để lần sau không ai phải phát hiện lại bằng cách gỡ lỗi.
 *
 * <h2>Vì sao Redis hỏng thì request vẫn ĐI TIẾP</h2>
 *
 * <p>Hai lựa chọn khi không tra được danh sách: chặn hết, hoặc cho qua hết.
 *
 * <p>Chặn hết nghe an toàn hơn, nhưng nó biến Redis thành điểm chết của
 * <i>toàn bộ</i> hệ thống: Redis rớt là không ai đăng nhập được, không ai tìm
 * kiếm được. Cho qua thì thiệt hại giới hạn ở đúng những token vừa bị thu hồi
 * trong 15 phút gần nhất — một tập rất nhỏ, và mỗi lần xảy ra đều để lại một
 * dòng log ERROR để đếm được.
 *
 * <p>Đây là một đánh đổi CÓ CHỦ Ý, không phải một chỗ bắt lỗi cho xong. Ở một
 * hệ thống mà token mang quyền chuyển tiền, lựa chọn đúng sẽ là ngược lại.
 */
@Component
@ConditionalOnProperty(name = "app.gateway.denylist.enabled", havingValue = "true",
        matchIfMissing = true)
public class TokenDenylistFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenDenylistFilter.class);

    /** Cùng tiền tố mà auth-service dùng khi ghi — xem RedisRefreshTokenStore. */
    private static final String DENIED_PREFIX = "at:denied:";

    private final ReactiveStringRedisTemplate redis;

    public TokenDenylistFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Chạy SAU chuỗi xác thực.
     *
     * <p>Bắt buộc: lớp này đọc {@code jti} từ token ĐÃ được xác minh chữ ký.
     * Chạy trước thì {@code SecurityContext} còn trống và filter này lặng lẽ
     * không làm gì — một biện pháp an ninh bị vô hiệu hoá mà không có dấu hiệu
     * nào, dạng lỗi khó thấy nhất.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> token.getToken().getId())
                .filter(jti -> jti != null && !jti.isBlank())
                .flatMap(jti -> redis.hasKey(DENIED_PREFIX + jti)
                        .onErrorResume(error -> {
                            log.error("Không tra được danh sách thu hồi token ({}). "
                                    + "Cho request đi tiếp — xem Javadoc lớp về đánh đổi này.",
                                    error.toString());
                            return Mono.just(Boolean.FALSE);
                        })
                        .flatMap(daThuHoi -> {
                            if (Boolean.TRUE.equals(daThuHoi)) {
                                log.info("Chặn token đã thu hồi (jti={})", jti);
                                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                return exchange.getResponse().setComplete();
                            }
                            return chain.filter(exchange);
                        }))
                // Không có token (endpoint công khai) thì không có gì để tra.
                .switchIfEmpty(chain.filter(exchange));
    }
}
