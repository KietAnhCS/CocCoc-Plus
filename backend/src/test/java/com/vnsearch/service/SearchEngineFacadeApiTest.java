package com.vnsearch.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
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
 * <p><b>PHAM VI DA THU HEP.</b> Ban truoc con kiem ca {@code /api/admin/stats},
 * {@code /api/admin/reindex} va {@code /api/admin/crawl} — hoi do chung nam
 * chung mot tien trinh. Nay chung thuoc crawler-service, va mot bai test cua
 * search-service ma goi sang service khac thi do khi service kia doi, va khong
 * chay duoc neu khong dung ca he thong. Chung duoc kiem o crawler-service.
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
    void publicEndpointsAreOpen() {
        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity(url("/api/search?q=test"), String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity(url("/api/health"), String.class).getStatusCode());
    }
}
