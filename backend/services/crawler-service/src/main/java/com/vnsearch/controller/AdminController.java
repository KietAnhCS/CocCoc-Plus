package com.vnsearch.controller;

import com.vnsearch.analytics.CorpusStats;
import com.vnsearch.crawler.SeedUrlValidator;
import com.vnsearch.service.SearchEngineFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint quan tri: kich hoat crawl, xem trang thai job, reindex,
 * xem thong ke he thong.
 *
 * <p><b>Toan bo duong dan nay yeu cau header {@code X-API-Key}</b> — xem
 * {@code SecurityConfig}. Truoc day chung mo hoan toan, va vi
 * {@code POST /crawl} khien may chu di tai mot URL tuy y roi dua noi dung vao
 * chi muc cong khai, do la mot lo hong SSRF day du chu khong chi la "API khong
 * co xac thuc".
 *
 * <p><b>Ba lop bao ve, moi lop chan mot thu khac nhau:</b>
 * <ol>
 *   <li>API key — chan nguoi la;</li>
 *   <li>{@link SeedUrlValidator} — chan URL tro vao mang noi bo, ke ca khi
 *       nguoi goi da co khoa (khoa co the ro ri, va mot phep sai sot cua chinh
 *       nguoi van hanh cung khong nen tai duoc metadata dam may);</li>
 *   <li>chan tren {@code maxPages}/{@code maxDepth} — chan viec mot request
 *       hop le lam can tai nguyen may.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminController {

    /**
     * Tran cho mot lan crawl qua API.
     *
     * <p>Truoc day khong co tran nao: {@code maxPages} chi bi
     * {@code CrawlConfig} kiem tra la {@code > 0}, nen mot request voi
     * {@code maxPages=100000000} duoc chap nhan va chay cho toi khi het bo nho.
     * Muon crawl lon hon tran nay thi dung {@code MultiDomainCrawlRunner} tu
     * dong lenh — noi nguoi chay chiu trach nhiem truc tiep va nhin thay tien
     * do, chu khong phai qua mot loi goi HTTP roi bo di.
     */
    private static final int MAX_PAGES_LIMIT = 50_000;
    private static final int MAX_DEPTH_LIMIT = 10;
    private static final int MAX_SEEDS = 50;

    private final SearchEngineFacade facade;

    public AdminController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    /**
     * @param maxDepth {@code null} nghia la dung mac dinh 3
     * @param maxPages {@code null} nghia la dung mac dinh 100
     */
    public record CrawlRequest(
            @NotEmpty(message = "seedUrls khong duoc de rong")
            @Size(max = MAX_SEEDS, message = "Toi da " + MAX_SEEDS + " seed URL moi lan")
            List<String> seedUrls,

            @Min(value = 0, message = "maxDepth phai >= 0")
            @Max(value = MAX_DEPTH_LIMIT, message = "maxDepth toi da " + MAX_DEPTH_LIMIT)
            Integer maxDepth,

            @Min(value = 1, message = "maxPages phai >= 1")
            @Max(value = MAX_PAGES_LIMIT, message = "maxPages toi da " + MAX_PAGES_LIMIT)
            Integer maxPages) {
    }

    @PostMapping("/crawl")
    public Map<String, String> crawl(@Valid @RequestBody CrawlRequest request) {
        // Kiem tra SSRF cho TUNG seed truoc khi khoi dong job: mot seed xau
        // khong duoc phep di vao hang doi roi moi bi phat hien.
        for (String seedUrl : request.seedUrls()) {
            SeedUrlValidator.validate(seedUrl);
        }

        int maxDepth = request.maxDepth() != null ? request.maxDepth() : 3;
        int maxPages = request.maxPages() != null ? request.maxPages() : 100;
        String jobId = facade.startCrawl(request.seedUrls(), maxDepth, maxPages);
        return Map.of("jobId", jobId, "status", "STARTED");
    }

    @GetMapping("/crawl/{jobId}/status")
    public ResponseEntity<Map<String, Object>> crawlStatus(@PathVariable String jobId) {
        Map<String, Object> status = facade.getCrawlStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Dựng lại chỉ mục từ corpus đã crawl.
     *
     * <p><b>Vì sao endpoint này nằm ở crawler-service chứ không ở
     * search-service</b> — dù thứ nó dựng lại là chỉ mục của search-service.
     * Lý do là quan hệ nhân quả: lập chỉ mục lại chỉ có nghĩa NGAY SAU một lần
     * crawl, và người vận hành gọi hai lệnh này liền nhau. Tách chúng ra hai
     * service là bắt họ nhớ hai địa chỉ cho một thao tác.
     *
     * <p><b>Giới hạn đã biết, ghi ra để không ai ngạc nhiên:</b> lệnh này dựng
     * lại chỉ mục TRONG TIẾN TRÌNH NÀY. Chỉ mục mà search-service đang phục vụ
     * chỉ đổi theo khi nó cũng nạp lại — hiện tại là lúc khởi động lại. Đây là
     * món nợ thật của việc tách service, và cách trả đúng là phát một sự kiện
     * `index.rebuilt` lên Kafka để search-service tự nạp lại. Chưa làm.
     */
    @PostMapping("/reindex")
    public Map<String, String> reindex() throws IOException {
        facade.reindex();
        return Map.of("status", "OK");
    }

    /**
     * Số liệu chỉ mục và corpus, cho bảng điều khiển quản trị.
     *
     * <p>analytics-service gọi endpoint này khi dựng bảng điều khiển, thay vì
     * đọc thẳng tệp chỉ mục. Xem {@code AdminDashboardAssembler}.
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return facade.getStats();
    }

    /**
     * Thống kê CORPUS: bao nhiêu host, bao nhiêu liên kết, độ dài tài liệu,
     * phân bố ngôn ngữ.
     *
     * <p>Tách khỏi {@link #stats()} vì hai thứ trả lời hai câu hỏi khác nhau và
     * có chi phí rất khác nhau: {@code /stats} đọc vài con số có sẵn trong bộ
     * nhớ, còn hàm này duyệt toàn bộ corpus để tính trung vị độ dài và đếm
     * liên kết. Gộp chung thì một bảng điều khiển làm mới mỗi 5 giây sẽ kéo
     * theo một lần duyệt corpus mỗi 5 giây.
     */
    @GetMapping("/corpus-stats")
    public CorpusStats corpusStats() {
        return facade.getCorpusStats();
    }
}
