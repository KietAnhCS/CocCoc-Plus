package com.vnsearch.oauth;

import java.time.Duration;
import java.util.Optional;

/**
 * Kho refresh token, kèm phát hiện dùng lại.
 *
 * <h2>Vì sao cần refresh token</h2>
 *
 * <p>Access token sống 15 phút. Không có gì khác thì người dùng phải đăng nhập
 * lại bốn lần mỗi giờ — và cách người ta phản ứng với điều đó luôn là kéo dài
 * hạn access token, tức là phá đúng thứ vừa dựng lên. Refresh token tách hai
 * mối quan tâm: <b>hạn ngắn</b> cho thứ đi kèm mọi request, <b>hạn dài</b> cho
 * thứ chỉ đi tới đúng một endpoint.
 *
 * <h2>Xoay vòng, và vì sao nó quan trọng hơn vẻ ngoài</h2>
 *
 * <p>Mỗi lần dùng refresh token, nó bị <b>huỷ</b> và một token mới được cấp.
 * Nhờ vậy, nếu một refresh token cũ xuất hiện lần thứ hai thì chỉ có hai khả
 * năng: hoặc máy khách gặp lỗi mạng và thử lại, hoặc <b>có kẻ đang dùng bản
 * sao đánh cắp</b>. Không phân biệt được hai trường hợp, nên xử lý theo giả
 * định xấu nhất: huỷ <i>toàn bộ</i> chuỗi token của tài khoản đó, buộc đăng
 * nhập lại. Đây là cách duy nhất phát hiện được vụ đánh cắp refresh token mà
 * không cần người dùng báo cáo.
 *
 * <p>Hai bản cài đặt: {@code RedisRefreshTokenStore} cho môi trường thật (chia
 * sẻ giữa nhiều bản sao, tự hết hạn theo TTL) và
 * {@code InMemoryRefreshTokenStore} cho test và cho lần chạy thử đầu tiên.
 * Ranh giới đặt ở giao diện này chứ không ở một câu {@code if} trong
 * controller: một service không được phép biết dữ liệu của nó nằm ở đâu.
 */
public interface RefreshTokenStore {

    /** Một refresh token còn hiệu lực. */
    record Grant(String username, String family) {
    }

    /**
     * Cấp refresh token mới cho một chuỗi (family).
     *
     * @param family chuỗi mà token này thuộc về; {@code null} để mở chuỗi mới
     * @return giá trị token thô — CHỈ trả về đúng lần này, kho chỉ giữ bản băm
     */
    String issue(String username, String family, Duration ttl);

    /**
     * Đổi một refresh token lấy quyền phát token mới.
     *
     * <p>Trả {@link Optional#empty()} khi token không tồn tại, đã hết hạn,
     * hoặc <b>đã bị dùng rồi</b> — và ở trường hợp cuối, mọi token cùng chuỗi
     * cũng bị huỷ trước khi hàm trả về.
     */
    Optional<Grant> consume(String token);

    /** Huỷ đúng một token (đăng xuất tại một thiết bị). */
    void revoke(String token);

    /** Huỷ mọi token của một tài khoản (đăng xuất mọi nơi, đổi mật khẩu, khoá tài khoản). */
    int revokeAllFor(String username);

    /** Ghi một access token vào danh sách thu hồi cho tới lúc nó hết hạn. */
    void denyAccessToken(String tokenId, Duration remainingLifetime);

    /** Access token này đã bị thu hồi chưa. */
    boolean isAccessTokenDenied(String tokenId);
}
