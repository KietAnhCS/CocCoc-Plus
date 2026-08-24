package com.vnsearch.football;

import com.vnsearch.football.store.FootballStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("docker-it")
@Import(FootballApiIT.KhongCanJwksThat.class)
class FootballApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("vnsearch")
                    .withUsername("vnsearch")
                    .withPassword("kiem-thu");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
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
                throw new UnsupportedOperationException("Endpoint bong da khong can JWT");
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FootballStore store;

    @Test
    void healthEndpointIsPublicAndReportsRunningWithoutAKey() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.sampleOnly").value(true));
    }

    @Test
    void quotaNumbersAreReadFromTheTableFlywayJustCreated() throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.budget").value(95))
                .andExpect(jsonPath("$.remaining").value(95));
    }

    @Test
    void withoutAnApiKeyReturnsEmptyWithSourceUnavailable() throws Exception {
        mockMvc.perform(get("/api/v1/leagues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("unavailable"));
    }

    @Test
    void cacheIsWrittenAndReadBackThroughTheJsonbType() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        store.put("v1:kiem-thu", "[{\"id\":\"47\"}]", expiresAt);

        Optional<com.vnsearch.football.store.CacheEntry> entry = store.find("v1:kiem-thu");

        assertThat(entry).isPresent();
        assertThat(entry.get().payload()).contains("47");
        assertThat(entry.get().expired(Instant.now())).isFalse();
    }

    @Test
    void callCountIsCountedCorrectlyByTimestamp() {
        int before = store.callsSince(Instant.now().minusSeconds(60));
        store.recordCall("/leagues", "search=arsenal");

        assertThat(store.callsSince(Instant.now().minusSeconds(60))).isEqualTo(before + 1);
    }

    @Test
    void settingsCanBeSavedAndOverwritten() {
        store.putSetting("api_key", "khoa-cu");
        store.putSetting("api_key", "khoa-moi");

        assertThat(store.setting("api_key")).isEqualTo("khoa-moi");
        assertThat(store.setting("khong-ton-tai")).isEmpty();
    }
}
