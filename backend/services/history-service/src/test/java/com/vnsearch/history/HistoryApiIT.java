package com.vnsearch.history;

import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("docker-it")
@Import(HistoryApiIT.KhongCanJwksThat.class)
class HistoryApiIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> MONGO.getReplicaSetUrl("vnsearch_history_test"));
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

    @Autowired
    private VisitRepository visits;

    @Autowired
    private SearchQueryRepository queries;

    @BeforeEach
    void donSach() {
        visits.deleteAll();
        queries.deleteAll();
    }

    private static RequestPostProcessor nguoiDung(String ten) {
        return jwt().jwt(builder -> builder.subject(ten).claim("roles", List.of("USER")));
    }

    private void ghe(String nguoi, String url, String tieuDe) throws Exception {
        mockMvc.perform(post("/api/history/visits")
                        .with(nguoiDung(nguoi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"title\":\"" + tieuDe + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void chuaDangNhapThiBiTuChoi() throws Exception {
        mockMvc.perform(get("/api/history/visits")).andExpect(status().isUnauthorized());
    }

    @Test
    void ghiRoiDocLai() throws Exception {
        ghe("an", "https://vnexpress.net/bai-viet", "Một bài báo");

        mockMvc.perform(get("/api/history/visits").with(nguoiDung("an")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Một bài báo"))
                .andExpect(jsonPath("$.content[0].host").value("vnexpress.net"))
                .andExpect(jsonPath("$.content[0].visitCount").value(1));
    }

    @Test
    void ghiLaiCungUrlThiGopChuKhongThemDong() throws Exception {
        ghe("gop", "https://a.vn/trang", "Lần một");
        ghe("gop", "https://a.vn/trang", "Lần hai");
        ghe("gop", "https://a.vn/trang", "Lần ba");

        mockMvc.perform(get("/api/history/visits").with(nguoiDung("gop")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].visitCount").value(3))
                .andExpect(jsonPath("$.content[0].title").value("Lần ba"));
    }

    @Test
    void khongDocDuocLichSuCuaNguoiKhac() throws Exception {
        ghe("binh", "https://rieng-tu.vn/trang", "Bí mật");

        mockMvc.perform(get("/api/history/visits").with(nguoiDung("ke-la")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void timTheoTuKhoaTrongTieuDeVaDiaChi() throws Exception {
        ghe("tim", "https://a.vn/java-spring", "Hướng dẫn Spring Boot");
        ghe("tim", "https://b.vn/nau-an", "Cách làm bánh mì");

        mockMvc.perform(get("/api/history/visits").param("q", "spring").with(nguoiDung("tim")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].url").value("https://a.vn/java-spring"));

        mockMvc.perform(get("/api/history/visits").param("q", "bánh").with(nguoiDung("tim")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void xoaMotMucCuaNguoiKhacTraVe404() throws Exception {
        ghe("chu-so-huu", "https://a.vn/cua-toi", "Của tôi");
        String id = visits.findAll().get(0).id();

        mockMvc.perform(delete("/api/history/visits/" + id).with(nguoiDung("ke-la")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/history/visits/" + id).with(nguoiDung("chu-so-huu")))
                .andExpect(status().isNoContent());
    }

    @Test
    void xoaTheoKhoangXoaCaLichSuTimKiem() throws Exception {
        ghe("don", "https://a.vn/1", "Một");
        ghe("don", "https://a.vn/2", "Hai");
        mockMvc.perform(post("/api/history/searches")
                        .with(nguoiDung("don"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"máy tính\",\"resultCount\":5}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/history/visits").with(nguoiDung("don")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));

        mockMvc.perform(get("/api/history/visits").with(nguoiDung("don")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void goiYTheoTienTo() throws Exception {
        for (String truyVan : List.of("máy tính xách tay", "máy giặt", "điện thoại")) {
            mockMvc.perform(post("/api/history/searches")
                            .with(nguoiDung("goiy"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"" + truyVan + "\",\"resultCount\":3}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/history/searches/suggest")
                        .param("prefix", "máy")
                        .with(nguoiDung("goiy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void tienToChuaKyTuDacBietKhongLamHongTruyVan() throws Exception {
        mockMvc.perform(post("/api/history/searches")
                        .with(nguoiDung("redos"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"(a+)+ regex\",\"resultCount\":0}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/history/searches/suggest")
                        .param("prefix", "(a+)+")
                        .with(nguoiDung("redos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/history/searches/suggest")
                        .param("prefix", ".*")
                        .with(nguoiDung("redos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void goLaiCungTruyVanThiChiCapNhatThoiDiem() throws Exception {
        for (int lan = 0; lan < 3; lan++) {
            mockMvc.perform(post("/api/history/searches")
                            .with(nguoiDung("lap"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"  Máy Tính  \",\"resultCount\":1}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/history/searches").with(nguoiDung("lap")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void anDanhKhongBaoGioToiDuocMayChu() throws Exception {
        mockMvc.perform(get("/api/history/visits").with(nguoiDung("an-danh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        assertTrue(visits.findAll().isEmpty());
    }
}
