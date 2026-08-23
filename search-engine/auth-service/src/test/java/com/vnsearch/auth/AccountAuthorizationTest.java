package com.vnsearch.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phan quyen theo <b>TAI KHOAN</b>, kiem o tang HTTP that.
 *
 * <p>Bai nay tra loi dung cau hoi "lam sao biet tai khoan nao la admin, tai
 * khoan nao la nguoi dung thuong": no dang ky mot nguoi dung thuong, dang nhap,
 * roi chung minh rang token cua nguoi do <b>khong</b> mo duoc bang so lieu, con
 * token cua quan tri vien thi mo duoc.
 *
 * <p><b>Phan biet 401 va 403 — hai thu khac nhau, va bai nay chot ca hai:</b>
 * <pre>
 *   401 Unauthorized  "toi khong biet anh la ai"     -> khong co token
 *   403 Forbidden     "toi biet anh la ai, va KHONG" -> co token, sai vai tro
 * </pre>
 * Tron hai ma nay lai la mot loi hay gap: tra 401 cho nguoi da dang nhap se
 * khien giao dien day ho ve man hinh dang nhap, ho dang nhap lai thanh cong,
 * roi lai bi day ve — mot vong lap khong loi thoat.
 */
@SpringBootTest(properties = {
        "app.security.admin-api-key=khoa-kiem-thu-du-dai-32-ky-tu-000",
        "app.security.rate-limit.enabled=false",
        // Khong tao tai khoan moi: bai nay tu tao tai khoan can dung.
        "app.auth.bootstrap-admin.password="
})
/*
 * @DirtiesContext: DONG context ngay sau khi lop nay chay xong.
 *
 * Moi @SpringBootTest co cau hinh khac nhau tao MOT ApplicationContext rieng,
 * va Spring GIU LAI tat ca de tai su dung. Moi context o day nap ca chi muc
 * 31.030 tai lieu (~400 MB tren dia) vao heap. Ba context cung song trong mot
 * JVM lam bo test chet vi OutOfMemoryError — mot loi that, gap ngay khi them
 * lop kiem thu tich hop thu hai.
 *
 * Danh doi: lop nay khong dung chung context voi ai, nen cham hon vai giay.
 * Doi lai bo test chay duoc. Va no cung dung ve mat ngu nghia: cac bai o day
 * GHI vao kho tai khoan va bo dem so lieu, tuc context that su "ban" sau khi
 * chay.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
class AccountAuthorizationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Kho tai khoan RIENG cho moi lan chay.
     *
     * <p>Ban dau bai nay dung mot duong dan co dinh, va no PASS lan dau roi
     * FAIL o lan thu hai: tep JSON con lai tu lan truoc khien
     * {@code createAccount} bao "ten da ton tai", va mot tai khoan da duoc nang
     * len ADMIN o lan truoc lam bai kiem "nguoi dung thuong bi tu choi" tra ve
     * 200. Mot bai kiem thu phu thuoc lan chay truoc thi khong con la bai kiem
     * thu — no chi dung mot lan.
     *
     * <p>Nam trong {@code target/} nen {@code mvn clean} don di, va khong bao
     * gio dung toi {@code data/users.json} that.
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

    /** Dang ky (neu chua co) roi dang nhap, tra ve token. */
    private String tokenFor(String username, String password, Role role) throws Exception {
        if (users.find(username).isEmpty()) {
            users.createAccount(username, password, role);
        }
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload(username, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("token").asText();
    }

    private record AuthPayload(String username, String password) {
    }

    // ------------------------------------------------------------------

    @Test
    void aiCungDangKyDuoc() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload("nguoi.dang.ky", "matkhaudaidu"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                // Ban cong khai KHONG duoc mang hash mat khau ra ngoai.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        assertEquals(Role.USER, users.find("nguoi.dang.ky").orElseThrow().role());
    }

    /**
     * Khong the tu cap vai tro ADMIN qua than request dang ky — lo hong leo
     * thang quyen kinh dien.
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

    @Test
    void nguoiDungThuongKhongDocDuocSoLieu() throws Exception {
        String token = tokenFor("nguoi.thuong", "matkhaudaidu", Role.USER);

        // 403, KHONG phai 401: may chu biet ho la ai, va tu choi.
        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void nguoiDungThuongKhongQuanLyDuocTaiKhoan() throws Exception {
        String token = tokenFor("nguoi.thuong.2", "matkhaudaidu", Role.USER);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void nguoiDungThuongVanTimKiemDuoc() throws Exception {
        String token = tokenFor("nguoi.thuong.3", "matkhaudaidu", Role.USER);

        mockMvc.perform(get("/api/search").param("q", "test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void quanTriVienDocDuocSoLieu() throws Exception {
        String token = tokenFor("quan.tri", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.total").exists());
    }

    @Test
    void tokenSaiBiDoiXuNhuKhongCoToken() throws Exception {
        mockMvc.perform(get("/api/admin/analytics")
                        .header("Authorization", "Bearer token-bia-ra"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dangXuatLamTokenMatHieuLucNgay() throws Exception {
        String token = tokenFor("quan.tri.dang.xuat", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Ngay lap tuc, khong cho het han.
        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meNoiRoDangNhapBangDuongNao() throws Exception {
        String token = tokenFor("nguoi.hoi.toi.la.ai", "matkhaudaidu", Role.USER);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.via").value("session"))
                .andExpect(jsonPath("$.user.username").value("nguoi.hoi.toi.la.ai"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                // Ban cong khai KHONG duoc mang hash mat khau ra ngoai.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

        // Duong con lai: khoa API. Khong co tai khoan dung sau, va /me noi that.
        mockMvc.perform(get("/api/auth/me")
                        .header("X-API-Key", "khoa-kiem-thu-du-dai-32-ky-tu-000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.via").value("api-key"));
    }

    /**
     * Dang xuat phai goi duoc ngay ca khi token DA HET HAN hoac khong hop le.
     *
     * <p>Truoc khi sua, /api/auth/logout nam trong nhom .authenticated() nen no
     * tra 401 — dung luc nguoi dung muon don dep phien thi he thong tu choi.
     * Javadoc cua handler noi ro no tra 204 ke ca khi token khong con hieu luc;
     * luat phan quyen da mau thuan voi loi hua do. Loi nay do review chi ra.
     */
    @Test
    void dangXuatGoiDuocKeCaKhiTokenKhongHopLe() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer het-han"))
                .andExpect(status().isNoContent());
    }

    /** Nguoc lai, dang xuat MOI THIET BI van phai xac thuc: no tac dong len TAI KHOAN. */
    @Test
    void dangXuatMoiThietBiVanCanXacThuc() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void khongCoDanhTinhThiMeTraVe401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Doi mat khau va dang xuat moi thiet bi
    // ------------------------------------------------------------------

    /**
     * Doi mat khau: phien dang goi duoc GIU, moi phien khac cua cung tai khoan
     * bi dong. Bai nay mo hai phien cho cung mot nguoi de kiem ca hai ve.
     */
    @Test
    void doiMatKhauGiuPhienDangDungVaDongPhienKhac() throws Exception {
        users.createAccount("nguoi.doi.mk", "matkhaucu123", Role.USER);
        String phienA = tokenFor("nguoi.doi.mk", "matkhaucu123", Role.USER);
        String phienB = tokenFor("nguoi.doi.mk", "matkhaucu123", Role.USER);

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + phienA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"matkhaucu123","newPassword":"matkhaumoi456"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedOtherSessions").value(1));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + phienA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + phienB))
                .andExpect(status().isUnauthorized());

        // Mat khau moi dung duoc, mat khau cu thi khong.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload("nguoi.doi.mk", "matkhaumoi456"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AuthPayload("nguoi.doi.mk", "matkhaucu123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doiMatKhauSaiMatKhauHienTaiThiBiTuChoi() throws Exception {
        users.createAccount("nguoi.doi.mk.sai", "matkhaucu123", Role.USER);
        String token = tokenFor("nguoi.doi.mk.sai", "matkhaucu123", Role.USER);

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"doan-mo","newPassword":"matkhaumoi456"}"""))
                .andExpect(status().isUnauthorized());

        // Phien cu VAN song: mot lan doi that bai khong duoc phep dang xuat nguoi ta.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void chuaDangNhapThiKhongDoiDuocMatKhau() throws Exception {
        mockMvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"gi-do","newPassword":"matkhaumoi456"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dangXuatMoiThietBiDongCaPhienDangGoi() throws Exception {
        users.createAccount("nguoi.dang.xuat.het", "matkhaudaidu", Role.USER);
        String phienA = tokenFor("nguoi.dang.xuat.het", "matkhaudaidu", Role.USER);
        String phienB = tokenFor("nguoi.dang.xuat.het", "matkhaudaidu", Role.USER);

        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + phienA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedSessions").value(2));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + phienA))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + phienB))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Nang vai tro co hieu luc — va phien CU bi dong, nen nguoi do phai dang
     * nhap lai thay vi mang mot phien con ghi vai tro cu.
     */
    @Test
    void quanTriNangVaiTroVaPhienCuBiDong() throws Exception {
        String adminToken = tokenFor("quan.tri.nang.quyen", "matkhaudaidu", Role.ADMIN);
        String userToken = tokenFor("nguoi.se.duoc.nang", "matkhaudaidu", Role.USER);

        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/users/nguoi.se.duoc.nang/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Token cu khong con dung duoc — phai dang nhap lai.
        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        // Dang nhap lai thi vao duoc.
        String moi = tokenFor("nguoi.se.duoc.nang", "matkhaudaidu", Role.ADMIN);
        mockMvc.perform(get("/api/admin/analytics").header("Authorization", "Bearer " + moi))
                .andExpect(status().isOk());
    }

    @Test
    void quanTriXoaHanTaiKhoanVaDongPhienCuaNguoiDo() throws Exception {
        String adminToken = tokenFor("quan.tri.xoa", "matkhaudaidu", Role.ADMIN);
        String victimToken = tokenFor("nguoi.se.bi.xoa", "matkhaudaidu", Role.USER);

        mockMvc.perform(delete("/api/admin/users/nguoi.se.bi.xoa")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertTrue(users.find("nguoi.se.bi.xoa").isEmpty(), "tai khoan phai bien mat");
        // Token cu khong duoc phep con tra ra mot phien hop le cho mot tai khoan
        // da khong con ton tai.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Sai PHUONG THUC tra <b>405</b>, khong phai 500.
     *
     * <p>Truoc khi co handler rieng, {@code HttpRequestMethodNotSupportedException}
     * roi vao nhanh bat-tat-ca va thanh 500 — bao rang may chu hong trong khi no
     * dang chay dung. Phat hien khi goi DELETE vao mot ban may chu chua co
     * endpoint do.
     */
    @Test
    void saiPhuongThucTraVe405ChuKhongPhai500() throws Exception {
        String token = tokenFor("quan.tri.405", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(put("/api/admin/users/ai-do")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Xoa lai lan hai: 404, khong gay them hau qua nao (idempotent). */
    @Test
    void xoaTaiKhoanKhongTonTaiTraVe404() throws Exception {
        String adminToken = tokenFor("quan.tri.xoa.404", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(delete("/api/admin/users/khong-he-ton-tai")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void quanTriKhongTuXoaChinhMinh() throws Exception {
        String token = tokenFor("quan.tri.tu.xoa", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(delete("/api/admin/users/quan.tri.tu.xoa")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertTrue(users.find("quan.tri.tu.xoa").isPresent());
    }

    @Test
    void nguoiDungThuongKhongXoaDuocTaiKhoan() throws Exception {
        String token = tokenFor("nguoi.thuong.xoa", "matkhaudaidu", Role.USER);
        users.createAccount("nan.nhan", "matkhaudaidu", Role.USER);

        mockMvc.perform(delete("/api/admin/users/nan.nhan")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        assertTrue(users.find("nan.nhan").isPresent(), "tai khoan phai con nguyen");
    }

    @Test
    void quanTriKhongTuHaQuyenChinhMinh() throws Exception {
        String token = tokenFor("quan.tri.tu.ha", "matkhaudaidu", Role.ADMIN);

        mockMvc.perform(post("/api/admin/users/quan.tri.tu.ha/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(Role.ADMIN, users.find("quan.tri.tu.ha").orElseThrow().role());
    }

    /** Danh sach tai khoan tra ve KHONG duoc chua hash mat khau. */
    @Test
    void danhSachTaiKhoanKhongLoHash() throws Exception {
        String token = tokenFor("quan.tri.xem.ds", "matkhaudaidu", Role.ADMIN);

        String body = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("passwordHash"), "phan hoi khong duoc chua truong hash");
        assertFalse(body.contains("$2a$"), "phan hoi khong duoc chua chuoi BCrypt");
        JsonNode array = JSON.readTree(body);
        assertTrue(array.isArray() && !array.isEmpty());
    }

    /**
     * Su kien su dung duoc gan danh tinh tu NGU CANH BAO MAT, khong phai tu
     * than request — neu khong, ai cung gan duoc hanh vi cho nguoi khac.
     */
    @Test
    void suKienGanDanhTinhTheoTokenChuKhongTheoLoiTuKhai() throws Exception {
        String userToken = tokenFor("nguoi.gui.su.kien", "matkhaudaidu", Role.USER);
        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"search","sessionId":"phien-x","query":"ha noi",
                                 "resultCount":5,"tookMs":10}"""))
                .andExpect(status().isNoContent());

        String adminToken = tokenFor("quan.tri.xem.su.kien", "matkhaudaidu", Role.ADMIN);
        String body = mockMvc.perform(get("/api/admin/analytics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("nguoi.gui.su.kien"),
                "bang topUsers phai co ten lay tu token");
    }
}
