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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test tich hop toan bo tang REST API (PHASE 6), chay tren du lieu co
 * dinh (fixture) thay vi crawl mang that, de dam bao ket qua deterministic.
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
    void statsReflectFixtureCorpus() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/admin/stats"), String.class);
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
        assertTrue(body.contains("\"tfidfScore\""));
        assertTrue(body.contains("\"pageRankScore\""));
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
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/admin/crawl/does-not-exist/status"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void reindexReturnsOk() {
        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/admin/reindex"), null, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"OK\""));
    }

    @Test
    void crawlWithEmptySeedUrlsReturns400() {
        String requestBody = "{\"seedUrls\":[],\"maxDepth\":1,\"maxPages\":1}";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/admin/crawl"), new org.springframework.http.HttpEntity<>(requestBody, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
