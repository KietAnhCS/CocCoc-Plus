package com.vnsearch.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiem thu <b>PHAN QUYEN</b> cua tang so lieu, o dung noi phan quyen that su
 * duoc quyet dinh: chuoi filter HTTP.
 *
 * <p><b>Vi sao bai nay ton tai.</b> {@code UsageAnalyticsServiceTest} chung
 * minh cac con so dung; no khong noi duoc gi ve chuyen <i>ai</i> doc duoc chung.
 * Ma phan quyen o day khong nam trong controller — no nam trong mot dong duy
 * nhat cua {@code SecurityConfig}. Mot dong nhu vay rat de bi mot lan sua sau
 * nay lam hong ma khong bai kiem thu don vi nao nhan ra: them mot duong dan
 * vao danh sach cong khai, doi thu tu hai {@code requestMatchers}, hay doi ten
 * duong dan cua controller — ca ba deu bien bang dieu khien thanh cong khai
 * trong khi moi thu van bien dich va moi test khac van xanh.
 *
 * <p>Bai nay chot lai <b>ba ranh gioi</b>:
 * <pre>
 *   khong co khoa        -> 401  (chieu DOC bi dong)
 *   khoa sai             -> 401  (khong phai cu co header la duoc)
 *   khoa dung            -> 200  (va tra ve dung cau truc)
 *   POST /api/events     -> 204  KHONG can khoa (chieu GHI mo co chu y)
 * </pre>
 */
@SpringBootTest(properties = {
        // Khoa CHi dung cho test. SecurityConfig khong cho khoi dong neu thieu,
        // nen bai nay cung dong thoi chung minh dieu do van dung.
        "app.security.admin-api-key=khoa-kiem-thu-du-dai-32-ky-tu-000",
        // Tat gioi han tan suat: bai nay do PHAN QUYEN, va mot bo gioi han bat
        // len co the tra 429 truoc khi request cham toi lop xac thuc — khi do
        // bai kiem thu se "xanh" vi mot ly do khac han dieu no dinh kiem.
        "app.security.rate-limit.enabled=false"
})
/*
 * @DirtiesContext: DONG context ngay sau khi lop nay chay xong.
 *
 * Moi @SpringBootTest co cau hinh khac nhau tao MOT ApplicationContext rieng,
 * va Spring GIU LAI tat ca de tai su dung. Moi context o day nap ca chi muc
 * 31.030 tai lieu (~400 MB tren dia) vao heap. Ba context cung song trong mot
 * JVM lam bo test chet vi OutOfMemoryError — mot loi that, gap ngay khi them
 * lop kiem thu tich hop thu hai.
 *
 * Danh doi: lop nay khong dung chung context voi ai, nen cham hon vai giay.
 * Doi lai bo test chay duoc. Va no cung dung ve mat ngu nghia: cac bai o day
 * GHI vao kho tai khoan va bo dem so lieu, tuc context that su "ban" sau khi
 * chay.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class AnalyticsAuthorizationTest {

    private static final String KEY_HEADER = "X-API-Key";
    private static final String VALID_KEY = "khoa-kiem-thu-du-dai-32-ky-tu-000";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withoutApiKeyMetricsAreNotReadable() throws Exception {
        mockMvc.perform(get("/api/admin/analytics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongApiKeyIsAlsoRejected() throws Exception {
        mockMvc.perform(get("/api/admin/analytics").header(KEY_HEADER, "khoa-sai-hoan-toan"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Khoá đúng thì đọc được khối số liệu mà CHÍNH service này sở hữu.
     *
     * <p>Chỉ kiểm khối {@code traffic}: hai khối còn lại ({@code crawl},
     * {@code index}, {@code accounts}) do crawler-service và auth-service giữ,
     * và trong một bài kiểm thử của riêng service này thì hai service đó không
     * chạy. Đòi chúng có mặt là biến bài test thành thứ chỉ xanh khi cả hệ
     * thống đang chạy — tức là một bài test tích hợp đội lốt test đơn vị.
     */
    @Test
    void validKeyReturnsItsOwnMetricsBlock() throws Exception {
        mockMvc.perform(get("/api/admin/analytics").header(KEY_HEADER, VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.traffic.searches").exists());
    }

    /**
     * Service phụ thuộc CHẾT thì bảng điều khiển vẫn trả 200, chỉ thiếu khối.
     *
     * <p>Đây là bài kiểm quan trọng nhất của mẫu API Composition, và nó chạy
     * được chính vì crawler-service lẫn auth-service đều KHÔNG tồn tại trong
     * bài test này — mọi lượt gọi ra ngoài đều hỏng. Không có phép suy giảm
     * từng phần trong {@code AdminDashboardAssembler} thì endpoint này trả 500
     * mỗi khi một service bất kỳ chậm, và một sự cố nhỏ ở crawler-service làm
     * mù luôn công cụ dùng để chẩn đoán nó.
     *
     * <p>{@code scorer = "khong-ro"} là dấu hiệu của khối dự phòng — xem
     * {@code AdminDashboardAssembler.INDEX_KHONG_RO}.
     */
    @Test
    void deadDependencyStillReturns200WithEmptyBlock() throws Exception {
        mockMvc.perform(get("/api/admin/analytics").header(KEY_HEADER, VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index.scorer").value("khong-ro"))
                .andExpect(jsonPath("$.accounts.total").value(0))
                .andExpect(jsonPath("$.crawl").doesNotExist());
    }

    @Test
    void resettingMetricsAlsoRequiresApiKey() throws Exception {
        mockMvc.perform(post("/api/admin/analytics/reset"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/analytics/reset").header(KEY_HEADER, VALID_KEY))
                .andExpect(status().isNoContent());
    }

    /**
     * Chieu GHI mo cho moi nguoi — neu dong lai thi chi quan tri vien moi gop
     * duoc so lieu, tuc khong con so lieu nao dang doc.
     */
    @Test
    void anyoneCanPostUsageEvents() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"search","sessionId":"phien-1","query":"ha noi",
                                 "resultCount":12,"tookMs":18}"""))
                .andExpect(status().isNoContent());
    }

    @Test
    void malformedEventIsRejectedNotSilentlyIgnored() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"khong-ton-tai\",\"sessionId\":\"phien-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlongQueryIsRejectedAtTheApplicationBoundary() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"search\",\"sessionId\":\"phien-1\",\"query\":\""
                                + "x".repeat(500) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Tham so {@code top} co chan tren de mot request khong ep may chu cap phat lon. */
    @Test
    void topParameterIsCappedAtTheUpperBound() throws Exception {
        mockMvc.perform(get("/api/admin/analytics").param("top", "1000000")
                        .header(KEY_HEADER, VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
