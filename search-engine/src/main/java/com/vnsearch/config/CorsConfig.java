package com.vnsearch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bat CORS cho renderer cua browser-app: dev server Vite
 * (http://localhost:5173) va production build load qua file://.
 *
 * <p><b>Vi sao goc {@code "null"} van phai nam trong danh sach.</b> Nhin qua
 * thi day la muc dang ngo nhat cua ca tep — goc {@code null} cung duoc gui boi
 * iframe sandbox va trang {@code data:}, nen no rong hon moi muc khac. Nhung bo
 * no di thi <b>ban dong goi cua browser-app ngung hoat dong</b>: renderer luc
 * do duoc nap qua {@code file://}, va Chromium gui {@code Origin: null} cho moi
 * request di ra tu mot trang {@code file://}. Day la danh doi co y thuc, khong
 * phai cho bi bo sot.
 *
 * <p>Ba dieu lam cho danh doi do chap nhan duoc, va ca ba deu duoc ghi tuong
 * minh o duoi thay vi de mac dinh:
 * <ol>
 *   <li><b>Khong cho gui thong tin xac thuc</b> ({@code allowCredentials(false)}).
 *       Day moi la thu bien mot cau hinh CORS rong thanh mot lo hong: khong co
 *       no, trinh duyet TU DONG dinh kem cookie cua nan nhan. He thong nay
 *       khong dung cookie, va dong duoi ghim dieu do lai de mot lan sua sau nay
 *       khong vo tinh bat len.</li>
 *   <li><b>Chi hai phuong thuc that su ton tai</b>. Truoc day danh sach co ca
 *       {@code PUT} va {@code DELETE} trong khi toan bo API khong co lay mot
 *       endpoint nao dung chung — quyen thua khong dung den van la quyen
 *       thua.</li>
 *   <li><b>Danh sach header cu the thay cho {@code "*"}.</b> {@code "*"} cho
 *       qua ca nhung header chua ai nghi toi. Ba header duoi la toan bo nhung
 *       gi tang goi that su gui.</li>
 * </ol>
 *
 * <p>Luu y quan trong: CORS <b>khong phai</b> lop bao ve cua {@code /api/admin/**}
 * — lop do la {@link ApiKeyAuthFilter}. CORS chi quyet dinh trinh duyet nao doc
 * duoc phan hoi; mot lenh {@code curl} khong bi CORS rang buoc chut nao.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins, "file://*", "null")
                // Chi GET va POST: toan bo API chi co hai loai endpoint —
                // doc (/search, /suggest, /images, /feed, /health) va hai lenh
                // quan tri POST (/admin/crawl, /admin/reindex).
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type", ApiKeyAuthFilter.HEADER)
                // TUONG MINH, du day la mac dinh cua Spring: xem muc (1) o
                // Javadoc lop. Ghi ro de mot thay doi sau nay phai la mot quyet
                // dinh co y thuc chu khong phai mot dong them vao cho tien.
                .allowCredentials(false)
                // Cho trinh duyet nho ket qua preflight 1 gio, thay vi hoi lai
                // truoc moi request POST.
                .maxAge(3600);
    }
}
