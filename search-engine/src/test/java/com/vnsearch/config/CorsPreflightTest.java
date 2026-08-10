package com.vnsearch.config;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS phai cho phep header <b>Authorization</b>.
 *
 * <h2>Bai nay ton tai vi mot loi that, va vi cach no an minh</h2>
 *
 * <p>Danh sach {@code allowedHeaders} ban dau khong co {@code Authorization} —
 * chi co {@code Accept}, {@code Content-Type} va {@code X-API-Key}. Hau qua:
 * <b>toan bo tang dang nhap bang token khong dung duoc tu trinh duyet</b>.
 *
 * <p>Va no hong theo kieu kho lan nhat:
 * <ul>
 *   <li>trinh duyet chan request o buoc PREFLIGHT, nen may chu khong nhan duoc
 *       gi va <b>log hoan toan sach</b>;</li>
 *   <li>moi phep thu bang {@code curl} deu tra 200 — {@code curl} khong bi CORS
 *       rang buoc, nen ca viec thu tay bang dong lenh lan cac bai kiem thu
 *       MockMvc goi thang controller deu KHONG thay gi;</li>
 *   <li>dang nhap van chay (POST /login chi gui {@code Content-Type}), chi
 *       nhung request MANG token moi hong — nen trieu chung la "dang nhap thanh
 *       cong roi bang dieu khien bao khong ket noi duoc may chu".</li>
 * </ul>
 *
 * <p>No chi lo ra khi MO UNG DUNG THAT va nhin vao man hinh. Bai kiem thu nay
 * ghim lai bai hoc do: request preflight o day di qua dung bo loc CORS ma trinh
 * duyet se gap.
 */
@SpringBootTest(properties = {
        "app.security.admin-api-key=khoa-kiem-thu-du-dai-32-ky-tu-000",
        "app.security.rate-limit.enabled=false",
        "app.auth.bootstrap-admin.password="
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class CorsPreflightTest {

    /** Goc cua dev server Vite — dung goc ma renderer gui khi chay `npm run dev`. */
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chapNhanHeaderAuthorization() throws Exception {
        mockMvc.perform(options("/api/admin/analytics")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        Matchers.containsStringIgnoringCase("Authorization")));
    }

    @Test
    void chapNhanHeaderXApiKey() throws Exception {
        mockMvc.perform(options("/api/admin/analytics")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-api-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        Matchers.containsStringIgnoringCase("X-API-Key")));
    }

    /**
     * DELETE phai duoc phep — endpoint xoa tai khoan dung no.
     *
     * <p>Cung loai bay voi header {@code Authorization}: mot phuong thuc thieu
     * trong {@code allowedMethods} bi chan o preflight, va {@code curl} khong
     * bao gio thay.
     */
    @Test
    void chapNhanPhuongThucDelete() throws Exception {
        mockMvc.perform(options("/api/admin/users/ai-do")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods",
                        Matchers.containsStringIgnoringCase("DELETE")));
    }

    /** Dang nhap: POST kem Content-Type, tu goc cua dev server Vite. */
    @Test
    void chapNhanPostDangNhap() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
    }

    /**
     * KHONG cho gui thong tin xac thuc tu dong (cookie).
     *
     * <p>Day moi la thu bien mot cau hinh CORS rong thanh mot lo hong. He thong
     * xac thuc bang header dat thu cong nen khong can, va dong nay ghim lai de
     * mot lan sua sau khong vo tinh bat len.
     */
    @Test
    void khongChoGuiCookieKemTheo() throws Exception {
        mockMvc.perform(options("/api/search")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
