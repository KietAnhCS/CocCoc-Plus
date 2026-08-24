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
@Import(HistoryApiIT.NoRealJwksNeeded.class)
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

    @Autowired
    private VisitRepository visits;

    @Autowired
    private SearchQueryRepository queries;

    @BeforeEach
    void cleanUp() {
        visits.deleteAll();
        queries.deleteAll();
    }

    private static RequestPostProcessor asUser(String name) {
        return jwt().jwt(builder -> builder.subject(name).claim("roles", List.of("USER")));
    }

    private void visit(String user, String url, String title) throws Exception {
        mockMvc.perform(post("/api/history/visits")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/history/visits")).andExpect(status().isUnauthorized());
    }

    @Test
    void writesThenReadsBack() throws Exception {
        visit("an", "https://vnexpress.net/bai-viet", "Một bài báo");

        mockMvc.perform(get("/api/history/visits").with(asUser("an")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Một bài báo"))
                .andExpect(jsonPath("$.content[0].host").value("vnexpress.net"))
                .andExpect(jsonPath("$.content[0].visitCount").value(1));
    }

    @Test
    void rewritingTheSameUrlMergesInsteadOfAppending() throws Exception {
        visit("gop", "https://a.vn/trang", "Lần một");
        visit("gop", "https://a.vn/trang", "Lần hai");
        visit("gop", "https://a.vn/trang", "Lần ba");

        mockMvc.perform(get("/api/history/visits").with(asUser("gop")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].visitCount").value(3))
                .andExpect(jsonPath("$.content[0].title").value("Lần ba"));
    }

    @Test
    void cannotReadAnotherUsersHistory() throws Exception {
        visit("binh", "https://rieng-tu.vn/trang", "Bí mật");

        mockMvc.perform(get("/api/history/visits").with(asUser("ke-la")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchesKeywordInTitleAndUrl() throws Exception {
        visit("tim", "https://a.vn/java-spring", "Hướng dẫn Spring Boot");
        visit("tim", "https://b.vn/nau-an", "Cách làm bánh mì");

        mockMvc.perform(get("/api/history/visits").param("q", "spring").with(asUser("tim")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].url").value("https://a.vn/java-spring"));

        mockMvc.perform(get("/api/history/visits").param("q", "bánh").with(asUser("tim")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void deletingAnotherUsersEntryReturns404() throws Exception {
        visit("chu-so-huu", "https://a.vn/cua-toi", "Của tôi");
        String id = visits.findAll().get(0).id();

        mockMvc.perform(delete("/api/history/visits/" + id).with(asUser("ke-la")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/history/visits/" + id).with(asUser("chu-so-huu")))
                .andExpect(status().isNoContent());
    }

    @Test
    void rangeDeleteAlsoClearsSearchHistory() throws Exception {
        visit("don", "https://a.vn/1", "Một");
        visit("don", "https://a.vn/2", "Hai");
        mockMvc.perform(post("/api/history/searches")
                        .with(asUser("don"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"máy tính\",\"resultCount\":5}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/history/visits").with(asUser("don")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));

        mockMvc.perform(get("/api/history/visits").with(asUser("don")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void suggestsByPrefix() throws Exception {
        for (String truyVan : List.of("máy tính xách tay", "máy giặt", "điện thoại")) {
            mockMvc.perform(post("/api/history/searches")
                            .with(asUser("goiy"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"" + truyVan + "\",\"resultCount\":3}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/history/searches/suggest")
                        .param("prefix", "máy")
                        .with(asUser("goiy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void prefixWithSpecialCharactersDoesNotBreakTheQuery() throws Exception {
        mockMvc.perform(post("/api/history/searches")
                        .with(asUser("redos"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"(a+)+ regex\",\"resultCount\":0}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/history/searches/suggest")
                        .param("prefix", "(a+)+")
                        .with(asUser("redos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/history/searches/suggest")
                        .param("prefix", ".*")
                        .with(asUser("redos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void retypingTheSameQueryOnlyUpdatesTheTimestamp() throws Exception {
        for (int lan = 0; lan < 3; lan++) {
            mockMvc.perform(post("/api/history/searches")
                            .with(asUser("lap"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"  Máy Tính  \",\"resultCount\":1}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/history/searches").with(asUser("lap")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void incognitoNeverReachesTheServer() throws Exception {
        mockMvc.perform(get("/api/history/visits").with(asUser("an-danh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        assertTrue(visits.findAll().isEmpty());
    }
}
