package com.vnsearch.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bề mặt quản trị của crawler-service.
 *
 * <p>Các bài này trước đây nằm trong {@code SearchEngineFacadeApiTest} của
 * search-service — hồi crawl và tìm kiếm còn chung một tiến trình. Chúng theo
 * controller sang đây, đúng nguyên tắc: bài kiểm thử sống cạnh thứ nó kiểm.
 *
 * <h2>Vì sao phần lớn các bài ở đây là bài kiểm BẢO MẬT</h2>
 *
 * <p>{@code POST /api/admin/crawl} khiến máy chủ đi tải một URL do người gọi
 * chọn. Một endpoint như thế mà hở là một máy quét cổng miễn phí cho bất kỳ ai
 * — <i>A10:2021 — Server-Side Request Forgery</i>. Số bài kiểm "ai KHÔNG được
 * vào" ở đây nhiều hơn số bài kiểm "chức năng chạy đúng", và đó là tỉ lệ đúng
 * cho một service như vậy.
 */
@SpringBootTest(properties = {
        "app.security.admin-api-key=khoa-kiem-thu-du-dai-32-ky-tu-000",
        "app.security.rate-limit.enabled=false",
        // Chỉ mục dựng sẵn: trỏ tới một tệp KHÔNG tồn tại để mỗi lần chạy đều
        // bắt đầu từ cùng một trạng thái.
        "app.index.data-path=target/test-data/crawler-index.json",
        "app.crawler.data-path=src/test/resources/fixtures/test-crawled-documents.json"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class CrawlAdminApiTest {

    /** Trùng với giá trị khai trong {@code @SpringBootTest} ở trên. */
    private static final String KHOA = "khoa-kiem-thu-du-dai-32-ky-tu-000";
    private static final String HEADER_KHOA = "X-API-Key";

    @Autowired
    private MockMvc mockMvc;

    // --------------------------------------------------------- chức năng

    @Test
    void indexStatsReflectTheSampleCorpus() throws Exception {
        mockMvc.perform(get("/api/admin/stats").header(HEADER_KHOA, KHOA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDocuments").value(3));
    }

    @Test
    void corpusStatsReturnEveryField() throws Exception {
        mockMvc.perform(get("/api/admin/corpus-stats").header(HEADER_KHOA, KHOA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(3))
                .andExpect(jsonPath("$.distinctHosts").exists())
                .andExpect(jsonPath("$.avgDocLength").exists());
    }

    @Test
    void reindexReturnsOk() throws Exception {
        mockMvc.perform(post("/api/admin/reindex").header(HEADER_KHOA, KHOA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void statusOfAMissingJobReturns404() throws Exception {
        mockMvc.perform(get("/api/admin/crawl/khong-he-ton-tai/status")
                        .header(HEADER_KHOA, KHOA))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ bảo mật

    /**
     * Danh sách hạt giống RỖNG bị từ chối ở biên, không đi tiếp.
     *
     * <p>400 chứ không phải 200 kèm một job không làm gì: một job "thành công"
     * mà không crawl trang nào là thứ khiến người vận hành chờ vô ích rồi đi
     * tìm lỗi ở chỗ khác.
     */
    @Test
    void crawlWithAnEmptySeedListReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/crawl")
                        .header(HEADER_KHOA, KHOA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seedUrls\":[],\"maxDepth\":1,\"maxPages\":1}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Không có khoá thì KHÔNG vào được, kể cả endpoint chỉ đọc.
     *
     * <p>Trả 401 chứ không phải 404: máy chủ không được nói cho người chưa xác
     * thực biết đường dẫn nào tồn tại.
     */
    @Test
    void requestWithoutApiKeyIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/reindex")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seedUrls\":[\"https://example.com\"],"
                                + "\"maxDepth\":1,\"maxPages\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongApiKeyIsAlsoRejected() throws Exception {
        mockMvc.perform(get("/api/admin/stats")
                        .header(HEADER_KHOA, "khoa-sai-nhung-du-dai-16"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Một đường dẫn quản trị CHƯA TỒN TẠI cũng đã bị chặn sẵn.
     *
     * <p>Bài này canh luật chặn theo TIỀN TỐ trong {@code ServiceSecurityConfig}
     * ({@code /api/admin/**} rồi {@code anyRequest().denyAll()}). Nhờ nó, một
     * endpoint quản trị thêm vào ngày mai được bảo vệ ngay cả khi người thêm
     * quên nghĩ tới phân quyền — mặc định đóng, không phải mặc định mở.
     */
    @Test
    void aNewAdminPathIsBlockedByDefault() throws Exception {
        mockMvc.perform(get("/api/admin/mot-endpoint-chua-ton-tai"))
                .andExpect(status().isUnauthorized());
    }
}
