package com.vnsearch.settings;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("docker-it")
@Import(SettingsApiIT.KhongCanJwksThat.class)
class SettingsApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vnsearch_settings")
                    .withUsername("vnsearch_settings")
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

    private static RequestPostProcessor nguoiDung(String ten) {
        return jwt().jwt(builder -> builder.subject(ten).claim("roles", List.of("USER")));
    }

    @Test
    void chuaDangNhapThiBiTuChoi() throws Exception {
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void chuaLuoGiThiTraVeKhoiRong() throws Exception {
        mockMvc.perform(get("/api/settings").with(nguoiDung("moi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.settings").isEmpty());
    }

    @Test
    void patchGopChuKhongThayThe() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("gop"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\",\"homePage\":\"https://a.vn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/settings").with(nguoiDung("gop"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"sang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.theme").value("sang"))
                .andExpect(jsonPath("$.settings.homePage").value("https://a.vn"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void putThayTheHanToanBoKhoi() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("thay"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\",\"homePage\":\"https://a.vn\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/settings").with(nguoiDung("thay"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"sang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.theme").value("sang"))
                .andExpect(jsonPath("$.settings.homePage").doesNotExist());
    }

    @Test
    void phienBanCuBiTuChoiVoi409() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("xungdot"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\"}"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/settings").with(nguoiDung("xungdot"))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homePage\":\"https://may-a.vn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(patch("/api/settings").with(nguoiDung("xungdot"))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homePage\":\"https://may-b.vn\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.settings.homePage").value("https://may-a.vn"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void khongDocDuocTuyChonCuaNguoiKhac() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("chu"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/settings").with(nguoiDung("ke-la")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings").isEmpty());
    }

    @Test
    void thanKhongPhaiDoiTuongJsonBiTuChoi() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("sai"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/settings").with(nguoiDung("sai"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{khong-phai-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void khoiQuaLonBiTuChoi() throws Exception {
        String qua = "{\"x\":\"" + "a".repeat(70_000) + "\"}";
        mockMvc.perform(patch("/api/settings").with(nguoiDung("to"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qua))
                .andExpect(status().isBadRequest());
    }

    @Test
    void xoaMotKhoa() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("xoakhoa"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\",\"homePage\":\"https://a.vn\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/settings/theme").with(nguoiDung("xoakhoa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.theme").doesNotExist())
                .andExpect(jsonPath("$.settings.homePage").value("https://a.vn"));
    }

    @Test
    void khoiPhucMacDinhXoaHanDong() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("macdinh"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/settings").with(nguoiDung("macdinh")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/settings").with(nguoiDung("macdinh")))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.settings").isEmpty());
    }

    @Test
    void tenKhoaChuaCuPhapSqlChiLaMotTenKhoa() throws Exception {
        mockMvc.perform(patch("/api/settings").with(nguoiDung("injection"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"toi\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/settings/x'; DROP TABLE user_settings; --")
                        .with(nguoiDung("injection")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/settings").with(nguoiDung("injection")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.theme").value("toi"));
    }
}
