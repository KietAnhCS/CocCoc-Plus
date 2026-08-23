package com.vnsearch.downloads;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("docker-it")
@Import(DownloadApiIT.KhongCanJwksThat.class)
class DownloadApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vnsearch_downloads")
                    .withUsername("vnsearch_downloads")
                    .withPassword("kiem-thu");

    @DynamicPropertySource
    static void csdl(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @TestConfiguration
    static class KhongCanJwksThat {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("Dung jwt() postprocessor");
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private static final String AN = "an";
    private static final String BINH = "binh";

    private static RequestPostProcessor nguoiDung(String ten) {
        return jwt().jwt(builder -> builder.subject(ten).claim("roles", List.of("USER")));
    }

    private String batDau(String nguoi, String tenTep) throws Exception {
        String id = UUID.randomUUID().toString();
        String than = "{\"id\":\"" + id + "\",\"sourceUrl\":\"https://vi.wikipedia.org/tep.pdf\","
                + "\"fileName\":\"" + tenTep + "\",\"mimeType\":\"application/pdf\","
                + "\"totalBytes\":1000}";
        mockMvc.perform(post("/api/downloads")
                        .with(nguoiDung(nguoi))
                        .header("X-Device-Id", "may-" + nguoi)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(than))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.onThisDevice").value(true));
        return id;
    }

    @Test
    void chuaDangNhapThiBiTuChoi() throws Exception {
        mockMvc.perform(get("/api/downloads")).andExpect(status().isUnauthorized());
    }

    @Test
    void batDauRoiCapNhatTienDo() throws Exception {
        String id = batDau(AN, "bao-cao.pdf");

        mockMvc.perform(patch("/api/downloads/" + id)
                        .with(nguoiDung(AN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":400}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBytes").value(400))
                .andExpect(jsonPath("$.percent").value(40));
    }

    @Test
    void goiLaiCungIdKhongTaoBanGhiThuHai() throws Exception {
        String id = UUID.randomUUID().toString();
        String than = "{\"id\":\"" + id + "\",\"sourceUrl\":\"https://a.vn/x.zip\","
                + "\"fileName\":\"x.zip\",\"totalBytes\":10}";

        for (int lan = 0; lan < 3; lan++) {
            mockMvc.perform(post("/api/downloads")
                            .with(nguoiDung("idempotent"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(than))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/downloads/summary").with(nguoiDung("idempotent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void tienDoDenTreKhongDayNguocTrangThai() throws Exception {
        String id = batDau("tretin", "phim.mp4");

        mockMvc.perform(patch("/api/downloads/" + id)
                        .with(nguoiDung("tretin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":1000,\"state\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        mockMvc.perform(patch("/api/downloads/" + id)
                        .with(nguoiDung("tretin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":870,\"state\":\"IN_PROGRESS\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/downloads").with(nguoiDung("tretin")))
                .andExpect(jsonPath("$[0].state").value("COMPLETED"));
    }

    @Test
    void byteNhanChiTang() throws Exception {
        String id = batDau("chitang", "anh.png");

        mockMvc.perform(patch("/api/downloads/" + id).with(nguoiDung("chitang"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":600}"))
                .andExpect(jsonPath("$.receivedBytes").value(600));

        mockMvc.perform(patch("/api/downloads/" + id).with(nguoiDung("chitang"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":200}"))
                .andExpect(jsonPath("$.receivedBytes").value(600));
    }

    @Test
    void khongDocDuocSoTaiXuongCuaNguoiKhac() throws Exception {
        String id = batDau(BINH, "rieng-tu.pdf");

        mockMvc.perform(get("/api/downloads").with(nguoiDung("ke-la")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(delete("/api/downloads/" + id).with(nguoiDung("ke-la")))
                .andExpect(status().isNotFound());
    }

    @Test
    void tepTaiTrenMayKhacThiKhongHienNutMo() throws Exception {
        batDau("nhieumay", "tren-may-kia.pdf");

        mockMvc.perform(get("/api/downloads")
                        .with(nguoiDung("nhieumay"))
                        .header("X-Device-Id", "mot-may-khac"))
                .andExpect(jsonPath("$[0].onThisDevice").value(false));
    }

    @Test
    void idKhongPhaiUuidTraVe400() throws Exception {
        mockMvc.perform(delete("/api/downloads/khong-phai-uuid").with(nguoiDung(AN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void xoaHetChiXoaMucDaKetThuc() throws Exception {
        String dangChay = batDau("dondep", "dang-tai.iso");
        String daXong = batDau("dondep", "da-xong.iso");

        mockMvc.perform(patch("/api/downloads/" + daXong).with(nguoiDung("dondep"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":1000,\"state\":\"COMPLETED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/downloads").with(nguoiDung("dondep")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        mockMvc.perform(get("/api/downloads/active").with(nguoiDung("dondep")))
                .andExpect(jsonPath("$[0].id").value(dangChay));
    }

    @Test
    void khongBietTongSoByteThiPhanTramLaNull() throws Exception {
        String id = UUID.randomUUID().toString();
        String than = "{\"id\":\"" + id + "\",\"sourceUrl\":\"https://a.vn/stream\","
                + "\"fileName\":\"stream.bin\"}";
        mockMvc.perform(post("/api/downloads")
                        .with(nguoiDung("khongro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(than))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalBytes").doesNotExist())
                .andExpect(jsonPath("$.percent").doesNotExist());
    }
}
