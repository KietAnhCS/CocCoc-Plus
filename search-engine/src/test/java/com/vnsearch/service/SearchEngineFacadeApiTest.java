package com.vnsearch.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test tich hop toan bo tang REST API (PHASE 6), chay tren du lieu co
 * dinh (fixture) thay vi crawl mang that, de dam bao ket qua deterministic.
 *
 * <p><b>Duong dan quan tri can header {@code X-API-Key}.</b> Cac bai test goi
 * {@code /api/admin/**} phai gui khoa qua {@link #adminGet}/{@link #adminPost};
 * goi tran se nhan 401. Khoa dung o day trung voi khoa gia dat trong cau hinh
 * surefire cua {@code pom.xml}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.index.data-path=target/test-data/index-should-not-exist.json",
        "app.crawler.data-path=src/test/resources/fixtures/test-crawled-documents.json"
})
class SearchEngineFacadeApiTest {

    /** Trung voi gia tri ADMIN_API_KEY khai bao trong maven-surefire-plugin. */
    private static final String TEST_API_KEY = "test-only-key-0123456789abcdef";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void ensureNoStaleIndexFile() throws IOException {
        Path path = Path.of("target/test-data/index-should-not-exist.json");
        Files.deleteIfExists(path);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Header xac thuc quan tri; {@code null} nghia la khong gui header nao. */
    private HttpEntity<String> adminRequest(String apiKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> adminGet(String path, String apiKey) {
        return restTemplate.exchange(url(path), HttpMethod.GET,
                adminRequest(apiKey, null), String.class);
    }

    private ResponseEntity<String> adminPost(String path, String apiKey, String body) {
        return restTemplate.exchange(url(path), HttpMethod.POST,
                adminRequest(apiKey, body), String.class);
    }

    @Test
    void statsReflectFixtureCorpus() {
        ResponseEntity<String> response = adminGet("/api/admin/stats", TEST_API_KEY);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"totalDocuments\":3"));
    }

    @Test
    void searchReturnsMatchingDocumentWithSnippetAndScores() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/search") + "?q={q}&page=1&size=10", String.class, "máy tính");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains("test.local/a"));
        assertTrue(body.contains("<mark>"));
        assertTrue(body.contains("\"score\""));
        assertTrue(body.contains("\"pageRankScore\""));
        // "tfidfScore" da bi bo khoi hop dong: no tra ve dung cung mot so voi
        // "score" ke tu khi cac tin hieu duoc gom bang Decorator.
        assertFalse(body.contains("\"tfidfScore\""));
    }

    @Test
    void searchWithNoMatchesReturnsEmptyResults() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/search?q=khongtontai12345"), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"totalResults\":0"));
    }

    @Test
    void missingQueryParamReturns400() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/search"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void suggestReturnsSuggestionsForKnownPrefix() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/suggest") + "?prefix={p}&limit=5", String.class, "công");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"suggestions\""));
    }

    @Test
    void unknownCrawlJobReturns404() {
        ResponseEntity<String> response =
                adminGet("/api/admin/crawl/does-not-exist/status", TEST_API_KEY);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void reindexReturnsOk() {
        ResponseEntity<String> response = adminPost("/api/admin/reindex", TEST_API_KEY, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"OK\""));
    }

    @Test
    void crawlWithEmptySeedUrlsReturns400() {
        String body = "{\"seedUrls\":[],\"maxDepth\":1,\"maxPages\":1}";
        ResponseEntity<String> response = adminPost("/api/admin/crawl", TEST_API_KEY, body);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // --- Xac thuc: chung minh khoa THAT SU chan, khong chi la trang tri ---

    @Test
    void adminEndpointsRejectRequestWithoutApiKey() {
        // Khong co header nao. Day la truong hop quan trong nhat: POST /crawl
        // khien may chu di tai mot URL tuy y, nen no KHONG duoc phep mo.
        assertEquals(HttpStatus.UNAUTHORIZED,
                adminGet("/api/admin/stats", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                adminPost("/api/admin/reindex", null, null).getStatusCode());
    }

    @Test
    void adminEndpointsRejectWrongApiKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                adminGet("/api/admin/stats", "khoa-sai-nhung-du-dai-16").getStatusCode());
    }

    @Test
    void publicEndpointsStayOpenWithoutApiKey() {
        // Ranh gioi phai dung ca HAI chieu: khoa duong dan admin ma vo tinh khoa
        // luon /api/search thi ung dung khong con phuc vu duoc ai.
        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity(url("/api/search?q=test"), String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity(url("/api/health"), String.class).getStatusCode());
    }
}
