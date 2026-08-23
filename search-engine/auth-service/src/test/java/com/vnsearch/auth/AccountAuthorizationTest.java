package com.vnsearch.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm thử tích hợp toàn bộ auth-service: đăng ký, phát token, xoay vòng,
 * thu hồi, và phân quyền.
 *
 * <h2>Phạm vi đã THU HẸP so với bản một khối</h2>
 *
 * <p>Bản trước của lớp này còn gọi {@code /api/search}, {@code /api/events} và
 * {@code /api/admin/analytics} — hồi đó cả bốn thứ nằm chung một tiến trình.
 * Nay chúng thuộc ba service khác, và một bài kiểm thử của auth-service mà gọi
 * sang search-service thì không còn là bài kiểm thử của một service: nó đỏ khi
 * service kia đổi, và nó không chạy được nếu không dựng cả hệ thống. Những
 * hành vi xuyên service ấy được kiểm ở tầng khác, bằng bài kiểm thử hợp đồng.
 *
 * <h2>Phân biệt 401 và 403 — thứ mà mọi bài ở đây canh</h2>
 * <pre>
 *   401 Unauthorized  "tôi không biết anh là ai"  -&gt; thiếu token, token hỏng
 *   403 Forbidden     "tôi biết anh là ai, và KHÔNG" -&gt; có token, sai vai trò
 * </pre>
 * <p>Trộn hai mã này là một lỗi hay gặp: trả 401 cho người đã đăng nhập sẽ
 * khiến giao diện đẩy họ về màn hình đăng nhập, họ đăng nhập lại thành công,
 * rồi lại bị đẩy về — một vòng lặp không lối thoát.
 */
@SpringBootTest(properties = {
        "app.security.admin-api-key=khoa-kiem-thu-du-dai-32-ky-tu-000",
        "app.security.rate-limit.enabled=false",
        // Không tạo tài khoản mồi: bài này tự tạo tài khoản cần dùng.
        "app.auth.bootstrap-admin.password=",
        // Đồng hồ thật, hạn thật — nhưng ngắn lại để bài "token hết hạn" không
        // phải chờ 15 phút. Vẫn đủ dài để mọi bài khác chạy xong.
        "app.auth.access-token-ttl=PT5M"
})
/*
 * @DirtiesContext: ĐÓNG context ngay sau khi lớp này chạy xong.
 *
 * Mỗi @SpringBootTest có cấu hình khác nhau tạo MỘT ApplicationContext riêng,
 * và Spring GIỮ LẠI tất cả để tái sử dụng. Ở đây điều đó còn có nghĩa xấu hơn
 * bộ nhớ: RsaKeyProvider sinh một cặp khoá RSA 2048 bit cho mỗi context, và
 * các bài ở lớp này GHI vào kho tài khoản lẫn kho refresh token — tức context
 * thật sự "bẩn" sau khi chạy.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
class AccountAuthorizationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Kho tài khoản RIÊNG cho mỗi lần chạy.
     *
     * <p>Ban đầu bài này dùng một đường dẫn cố định, và nó PASS lần đầu rồi
     * FAIL ở lần thứ hai: tệp JSON còn lại từ lần trước khiến
     * {@code createAccount} báo "tên đã tồn tại", và một tài khoản đã được nâng
     * lên ADMIN ở lần trước làm bài kiểm "người dùng thường bị từ chối" trả về
     * 200. Một bài kiểm thử phụ thuộc lần chạy trước thì không còn là bài kiểm
     * thử — nó chỉ đúng một lần.
     *
     * <p>Nằm trong {@code target/} nên {@code mvn clean} dọn đi, và không bao
     * giờ đụng tới {@code data/users.json} thật.
     */
    @DynamicPropertySource
    static void khoTaiKhoanRieng(DynamicPropertyRegistry registry) {
        registry.add("app.auth.users-path",
                () -> "target/test-users-" + UUID.randomUUID() + ".json");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService users;

    private record AuthPayload(String username, String password) {
    }

    /** Cặp token vừa phát: access để gọi API, refresh để gia hạn. */
    private record Tokens(String access, String refresh) {
    }

    /** Đăng ký (nếu chưa có) rồi đăng nhập, trả về cả hai token. */
    private Tokens login(String username, String password, Role role) throws Exception {
        if (users.find(username).isEmpty()) {
            users.createAccount(username, password, role);
        }
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new AuthPayload(username, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = JSON.readTree(body);
        return new Tokens(node.get("token").asText(), node.get("refreshToken").asText());
    }

    private String accessToken(String username, String password, Role role) throws Exception {
        return login(username, password, role).access();
    }

    // ---------------------------------------------------------------- đăng ký

    @Test
    void aiCungDangKyDuoc() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload("nguoi.dang.ky", "matkhaudaidu"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                // Bản công khai KHÔNG được mang hash mật khẩu ra ngoài.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        assertEquals(Role.USER, users.find("nguoi.dang.ky").orElseThrow().role());
    }

    /**
     * Không thể tự cấp vai trò ADMIN qua thân request đăng ký — lỗ hổng leo
     * thang quyền kinh điển (mass assignment).
     */
    @Test
    void thanRequestKhongDatDuocVaiTro() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ke.leo.thang","password":"matkhaudaidu",
                                 "role":"ADMIN"}"""))
                .andReturn();

        assertEquals(Role.USER, users.find("ke.leo.thang").orElseThrow().role(),
                "truong 'role' trong than request PHAI bi bo qua");
    }

    // -------------------------------------------------------------- đăng nhập

    @Test
    void dangNhapSaiTraVe401VaKhongCoToken() throws Exception {
        users.createAccount("nguoi.sai.mk", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload("nguoi.sai.mk", "matkhausai1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * Đăng nhập trả về access token dạng JWT ba phần, kèm refresh token.
     *
     * <p>Kiểm cả hình dạng token: một lỗi cấu hình khiến máy chủ trả về chuỗi
     * rỗng hay một token mờ vẫn làm bài "đăng nhập thành công" xanh, rồi đổ ở
     * tận request tiếp theo.
     */
    @Test
    void dangNhapTraVeCapTokenJwt() throws Exception {
        Tokens tokens = login("nguoi.co.token", "matkhaudaidu", Role.USER);

        assertEquals(3, tokens.access().split("\\.").length,
                "access token phai la JWT ba phan header.payload.signature");
        assertFalse(tokens.refresh().isBlank(), "phai co refresh token");
        assertNotEquals(tokens.access(), tokens.refresh(),
                "hai token phai khac nhau — dung chung mot gia tri nghia la"
                        + " refresh token cung mo duoc moi endpoint");
    }

    @Test
    void khongCoDanhTinhThiMeTraVe401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meNoiRoDangNhapBangDuongNao() throws Exception {
        String token = accessToken("nguoi.hoi.me", "matkhaudaidu", Role.USER);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.via").value("jwt"))
                .andExpect(jsonPath("$.user.username").value("nguoi.hoi.me"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void tokenBiaRaBiTuChoi() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer token-bia-ra"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- gia hạn

    @Test
    void giaHanTraVeCapTokenMoiVaHuyTokenCu() throws Exception {
        Tokens dau = login("nguoi.gia.han", "matkhaudaidu", Role.USER);

        String body = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + dau.refresh() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode moi = JSON.readTree(body);

        assertNotEquals(dau.refresh(), moi.get("refreshToken").asText(),
                "refresh token PHAI xoay vong: dung lai gia tri cu nghia la mot"
                        + " ban sao bi danh cap dung duoc mai mai");

        // Token cũ đã chết. Đây là phép kiểm quan trọng nhất của cả cơ chế
        // xoay vòng — thiếu nó thì "xoay vòng" chỉ là cấp thêm token mới.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + dau.refresh() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void giaHanBangTokenBiaRaBiTuChoi() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"khong-ton-tai\"}"))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------- đăng xuất

    /**
     * Đăng xuất huỷ refresh token: sau đó không gia hạn được nữa.
     *
     * <p>Access token đang cầm vẫn còn hạn — đó là bản chất của token tự chứng
     * thực, và {@code TokenService} đưa nó vào danh sách thu hồi để bịt nốt.
     * Bài này canh vế <i>chắc chắn kiểm được</i>: cửa gia hạn đã đóng.
     */
    @Test
    void dangXuatHuyRefreshToken() throws Exception {
        Tokens tokens = login("nguoi.dang.xuat", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokens.refresh() + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokens.refresh() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Đăng xuất gọi được kể cả khi không có token hợp lệ.
     *
     * <p>Người dùng bấm "đăng xuất" thì kết quả họ mong đợi là <i>đã đăng
     * xuất</i>. Trả 401 cho một trạng thái vốn đã đúng chỉ khiến giao diện phải
     * viết một nhánh xử lý lỗi cho việc không có gì sai.
     */
    @Test
    void dangXuatGoiDuocKeCaKhiTokenKhongHopLe() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer token-da-het-han")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"khong-ton-tai\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void dangXuatMoiThietBiVanCanXacThuc() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dangXuatMoiThietBiDongMoiPhien() throws Exception {
        Tokens mot = login("nguoi.hai.may", "matkhaudaidu", Role.USER);
        Tokens hai = login("nguoi.hai.may", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/api/auth/logout-all")
                        .header("Authorization", "Bearer " + mot.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedSessions").value(2));

        // Cả hai phiên đều chết, kể cả phiên KHÔNG gọi lệnh này.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + hai.refresh() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- đổi mật khẩu

    @Test
    void chuaDangNhapThiKhongDoiDuocMatKhau() throws Exception {
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"matkhaudaidu","newPassword":"matkhaumoi123"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doiMatKhauSaiMatKhauHienTaiThiBiTuChoi() throws Exception {
        String token = accessToken("nguoi.doi.mk.sai", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"khongdung123","newPassword":"matkhaumoi123"}"""))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Đổi mật khẩu đóng MỌI phiên.
     *
     * <p>Lý do phổ biến nhất để đổi mật khẩu là nghi ngờ có người khác đang
     * dùng tài khoản. Giữ lại bất kỳ refresh token nào — kể cả của thiết bị
     * đang gọi — là giữ lại đúng thứ có thể đã bị đánh cắp.
     */
    @Test
    void doiMatKhauDongMoiPhien() throws Exception {
        Tokens tokens = login("nguoi.doi.mk", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + tokens.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"matkhaudaidu","newPassword":"matkhaumoi123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokens.refresh() + "\"}"))
                .andExpect(status().isBadRequest());

        // Mật khẩu mới thật sự có hiệu lực.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload("nguoi.doi.mk", "matkhaumoi123"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- quản trị

    @Test
    void nguoiDungThuongKhongQuanLyDuocTaiKhoan() throws Exception {
        String token = accessToken("nguoi.thuong.2", "matkhaudaidu", Role.USER);

        // 403, KHÔNG phải 401: máy chủ biết họ là ai, và từ chối.
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void quanTriVienDocDuocDanhSachTaiKhoan() throws Exception {
        String token = accessToken("quan.tri", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").exists())
                // Danh sách tài khoản KHÔNG được lộ hash mật khẩu. Đây là lỗi
                // rò rỉ dữ liệu hay gặp nhất ở trang quản trị: trả nguyên
                // entity thay vì bản chiếu công khai.
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void quanTriDocDuocSoLieuTaiKhoan() throws Exception {
        String token = accessToken("quan.tri.stats", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(get("/api/admin/users/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.admins").isNumber())
                .andExpect(jsonPath("$.activeSessions").isNumber());
    }

    @Test
    void quanTriNangVaiTroVaPhienCuBiDong() throws Exception {
        Tokens naNhan = login("nguoi.duoc.nang", "matkhaudaidu", Role.USER);
        String quanTri = accessToken("quan.tri.nang", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(post("/api/admin/users/nguoi.duoc.nang/role")
                        .header("Authorization", "Bearer " + quanTri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Phiên cũ mang vai trò cũ nên phải chết — nếu không, người vừa được
        // nâng quyền sẽ không hiểu vì sao vẫn bị từ chối.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + naNhan.refresh() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quanTriKhongTuHaQuyenChinhMinh() throws Exception {
        String token = accessToken("quan.tri.tu.ha", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(post("/api/admin/users/quan.tri.tu.ha/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(Role.ADMIN, users.find("quan.tri.tu.ha").orElseThrow().role());
    }

    @Test
    void quanTriKhongTuXoaChinhMinh() throws Exception {
        String token = accessToken("quan.tri.tu.xoa", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(delete("/api/admin/users/quan.tri.tu.xoa")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertTrue(users.find("quan.tri.tu.xoa").isPresent());
    }

    @Test
    void quanTriXoaHanTaiKhoanVaDongPhienCuaNguoiDo() throws Exception {
        Tokens naNhan = login("nguoi.bi.xoa", "matkhaudaidu", Role.USER);
        String quanTri = accessToken("quan.tri.xoa", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(delete("/api/admin/users/nguoi.bi.xoa")
                        .header("Authorization", "Bearer " + quanTri))
                .andExpect(status().isNoContent());

        assertTrue(users.find("nguoi.bi.xoa").isEmpty());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + naNhan.refresh() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void xoaTaiKhoanKhongTonTaiTraVe404() throws Exception {
        String token = accessToken("quan.tri.404", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(delete("/api/admin/users/khong-he-ton-tai")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /**
     * Gọi đúng đường dẫn nhưng sai phương thức trả <b>405</b>, không phải 500.
     *
     * <p>Một nhánh bắt-tất-cả cho 500 sẽ nuốt ngoại lệ của Spring MVC và báo
     * rằng máy chủ hỏng, trong khi thực tế nó đang chạy đúng.
     */
    @Test
    void saiPhuongThucTraVe405ChuKhongPhai500() throws Exception {
        String token = accessToken("quan.tri.405", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(put("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    // ------------------------------------------------------------ OAuth2 chuẩn

    @Test
    void oauth2TokenCapTokenBangGrantPassword() throws Exception {
        users.createAccount("nguoi.oauth", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("username", "nguoi.oauth")
                        .param("password", "matkhaudaidu"))
                .andExpect(status().isOk())
                // Tên trường theo đúng RFC 6749 §5.1 — đây là điều khiến một
                // thư viện OAuth2 bất kỳ đọc được phản hồi này mà không cần
                // lớp chuyển đổi nào.
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(jsonPath("$.refresh_token").exists())
                // Không được lưu đệm: một access token nằm trong bộ đệm dùng
                // chung là một access token đã bị rò.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", "no-store"));
    }

    @Test
    void oauth2TokenTuChoiGrantKhongHoTro() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
    }

    /**
     * JWKS chỉ chứa phần CÔNG KHAI của khoá.
     *
     * <p>Đây là bài kiểm quan trọng nhất trong cả lớp. Quên một lời gọi
     * {@code toPublicJWK()} thì khoá RIÊNG được tuần tự hoá ra và phát công
     * khai qua HTTP — bất kỳ ai cũng ký được token ADMIN. Phản hồi vẫn đúng
     * dạng JWKS và trông bình thường với người đọc lướt; chỉ có trường
     * {@code "d"} (số mũ riêng của RSA) xuất hiện thêm.
     */
    @Test
    void jwksChiChuaKhoaCongKhai() throws Exception {
        String body = mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].n").exists())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"d\""),
                "JWKS KHONG duoc chua so mu rieng cua RSA — kiem tra loi goi toPublicJWK()");
        assertFalse(body.contains("\"p\"") || body.contains("\"q\""),
                "JWKS KHONG duoc chua thua so nguyen to cua khoa rieng");
    }

    @Test
    void sieuDuLieuMayChuUyQuyenDayDu() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").exists())
                .andExpect(jsonPath("$.token_endpoint").exists())
                .andExpect(jsonPath("$.jwks_uri").exists())
                .andExpect(jsonPath("$.id_token_signing_alg_values_supported[0]").value("RS256"));
    }
}
