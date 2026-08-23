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

    // --- Xac thuc: chung minh khoa THAT SU chan, khong chi la trang tri ---

    /**
     * Duong dan quan tri bi chan MAC DINH, ke ca duong dan khong ton tai.
     *
     * <p>Vi sao goi mot duong dan khong co controller nao: chinh vi the no moi
     * chung minh dieu can chung minh. Luat bao mat trong ServiceSecurityConfig
     * la `/api/admin/** -> hasRole(ADMIN)` roi `anyRequest -> denyAll`, tuc no
     * chan theo TIEN TO chu khong theo tung endpoint. Mot endpoint quan tri
     * them vao ngay mai se duoc bao ve san; bai test nay la thu canh dieu do.
     *
     * <p>Ket qua mong doi la 401 chu khong phai 404: may chu KHONG duoc noi
     * cho nguoi chua xac thuc biet duong dan nao ton tai.
     */
    @Test
    void adminEndpointsRejectRequestWithoutApiKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                adminGet("/api/admin/bat-ky", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                adminPost("/api/admin/bat-ky", null, null).getStatusCode());
    }

    @Test
    void adminEndpointsRejectWrongApiKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                adminGet("/api/admin/bat-ky", "khoa-sai-nhung-du-dai-16").getStatusCode());
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
