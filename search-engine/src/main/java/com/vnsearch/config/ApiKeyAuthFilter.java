package com.vnsearch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Xac thuc cac endpoint quan tri bang mot <b>API key</b> trong header
 * {@code X-API-Key}.
 *
 * <p><b>Vi sao API key chu khong phai tai khoan/mat khau hay OAuth.</b> He
 * thong nay khong co <i>nguoi dung</i> nao ca. Cac endpoint {@code /api/admin/**}
 * duoc goi boi mot cong cu van hanh — mot dong lenh {@code curl}, mot job dinh
 * ky — chu khong phai boi con nguoi ngoi truoc man hinh dang nhap. Dung bo may
 * quan ly nguoi dung day du o day se tao ra bang nguoi dung, luong dang ky, ma
 * hoa mat khau... toan bo mot he thong con khong ai su dung. API key la dung
 * hinh dang cua bai toan.
 *
 * <p><b>So sanh chuoi bang thuat toan thoi gian hang so.</b> Phep so
 * {@code String.equals} thoat ra ngay tai ky tu dau tien khac nhau, nen thoi
 * gian chay ro ri <i>do dai tien to dung</i> cua khoa doan. Do du chenh lech
 * nay qua nhieu lan request, ke tan cong doan duoc tung ky tu mot — bien khong
 * gian tim kiem tu {@code 62^32} xuong con {@code 62*32}.
 * {@link MessageDigest#isEqual} luon duyet het do dai.
 *
 * <p><b>Khong co khoa thi khong khoi dong duoc</b> — xem
 * {@link SecurityConfig}. Mot he thong "co bao ve" ma bao ve bang khoa mac dinh
 * ai cung biet thi con nguy hiem hon he thong khong bao ve, vi no tao cam giac
 * an toan sai.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String HEADER = "X-API-Key";

    private final byte[] expectedKey;

    public ApiKeyAuthFilter(String expectedKey) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);

        if (provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            // Danh dau da xac thuc. Vai tro ADMIN de SecurityConfig phan quyen
            // theo duong dan, khong phai theo tung filter.
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "admin-api-key", null,
                            AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        } else if (provided != null) {
            // Ghi log khoa SAI (khong ghi gia tri khoa) — mot loat dong nay la
            // dau hieu co nguoi dang do khoa.
            log.warn("Tu choi {} {}: API key khong dung", request.getMethod(),
                    request.getRequestURI());
        }

        chain.doFilter(request, response);
    }
}
