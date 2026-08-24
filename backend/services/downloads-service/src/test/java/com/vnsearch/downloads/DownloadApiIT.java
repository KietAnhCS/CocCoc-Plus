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
@Import(DownloadApiIT.NoRealJwksNeeded.class)
class DownloadApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vnsearch_downloads")
                    .withUsername("vnsearch_downloads")
                    .withPassword("kiem-thu");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @TestConfiguration
    static class NoRealJwksNeeded {
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

    private static RequestPostProcessor asUser(String name) {
        return jwt().jwt(builder -> builder.subject(name).claim("roles", List.of("USER")));
    }

    private String startDownload(String user, String fileName) throws Exception {
        String id = UUID.randomUUID().toString();
        String than = "{\"id\":\"" + id + "\",\"sourceUrl\":\"https://vi.wikipedia.org/tep.pdf\","
                + "\"fileName\":\"" + fileName + "\",\"mimeType\":\"application/pdf\","
                + "\"totalBytes\":1000}";
        mockMvc.perform(post("/api/downloads")
                        .with(asUser(user))
                        .header("X-Device-Id", "may-" + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(than))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.onThisDevice").value(true));
        return id;
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/downloads")).andExpect(status().isUnauthorized());
    }

    @Test
    void startsThenUpdatesProgress() throws Exception {
        String id = startDownload(AN, "bao-cao.pdf");

        mockMvc.perform(patch("/api/downloads/" + id)
                        .with(asUser(AN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":400}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBytes").value(400))
                .andExpect(jsonPath("$.percent").value(40));
    }

    @Test
    void repeatingTheSameIdDoesNotCreateASecondRecord() throws Exception {
        String id = UUID.randomUUID().toString();
        String than = "{\"id\":\"" + id + "\",\"sourceUrl\":\"https://a.vn/x.zip\","
                + "\"fileName\":\"x.zip\",\"totalBytes\":10}";

        for (int lan = 0; lan < 3; lan++) {
            mockMvc.perform(post("/api/downloads")
                            .with(asUser("idempotent"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(than))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/downloads/summary").with(asUser("idempotent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void lateProgressDoesNotPushTheStateBackwards() throws Exception {
        String id = startDownload("tretin", "phim.mp4");

        mockMvc.perform(patch("/api/downloads/" + id)
                        .with(asUser("tretin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":1000,\"state\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        mockMvc.perform(patch("/api/downloads/" + id)
                        .with(asUser("tretin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":870,\"state\":\"IN_PROGRESS\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/downloads").with(asUser("tretin")))
                .andExpect(jsonPath("$[0].state").value("COMPLETED"));
    }

    @Test
    void receivedBytesOnlyIncrease() throws Exception {
        String id = startDownload("chitang", "anh.png");

        mockMvc.perform(patch("/api/downloads/" + id).with(asUser("chitang"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":600}"))
                .andExpect(jsonPath("$.receivedBytes").value(600));

        mockMvc.perform(patch("/api/downloads/" + id).with(asUser("chitang"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":200}"))
                .andExpect(jsonPath("$.receivedBytes").value(600));
    }

    @Test
    void cannotReadAnotherUsersDownloads() throws Exception {
        String id = startDownload(BINH, "rieng-tu.pdf");

        mockMvc.perform(get("/api/downloads").with(asUser("ke-la")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(delete("/api/downloads/" + id).with(asUser("ke-la")))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileDownloadedOnAnotherDeviceHidesTheOpenButton() throws Exception {
        startDownload("nhieumay", "tren-may-kia.pdf");

        mockMvc.perform(get("/api/downloads")
                        .with(asUser("nhieumay"))
                        .header("X-Device-Id", "mot-may-khac"))
                .andExpect(jsonPath("$[0].onThisDevice").value(false));
    }

    @Test
    void nonUuidIdReturns400() throws Exception {
        mockMvc.perform(delete("/api/downloads/khong-phai-uuid").with(asUser(AN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteAllOnlyRemovesFinishedItems() throws Exception {
        String dangChay = startDownload("dondep", "dang-tai.iso");
        String daXong = startDownload("dondep", "da-xong.iso");

        mockMvc.perform(patch("/api/downloads/" + daXong).with(asUser("dondep"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedBytes\":1000,\"state\":\"COMPLETED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/downloads").with(asUser("dondep")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        mockMvc.perform(get("/api/downloads/active").with(asUser("dondep")))
                .andExpect(jsonPath("$[0].id").value(dangChay));
    }

    @Test
    void percentIsNullWhenTotalBytesAreUnknown() throws Exception {
        String id = UUID.randomUUID().toString();
        String than = "{\"id\":\"" + id + "\",\"sourceUrl\":\"https://a.vn/stream\","
                + "\"fileName\":\"stream.bin\"}";
        mockMvc.perform(post("/api/downloads")
                        .with(asUser("khongro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(than))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalBytes").doesNotExist())
                .andExpect(jsonPath("$.percent").doesNotExist());
    }
}
