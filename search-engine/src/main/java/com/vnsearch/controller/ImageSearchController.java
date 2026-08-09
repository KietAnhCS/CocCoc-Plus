package com.vnsearch.controller;

import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.modular.ImageStore;
import com.vnsearch.model.SearchResponse;
import com.vnsearch.model.SearchResult;
import com.vnsearch.service.SearchEngineFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tìm kiếm hình ảnh: {@code GET /api/images?q={query}&size={n}}.
 *
 * <h2>Không có mô hình xếp hạng riêng cho ảnh — và đó là chủ ý</h2>
 *
 * <p>Cách làm ở đây gồm đúng hai bước:
 *
 * <pre>
 *   1. Chạy CHÍNH truy vấn đó qua máy tìm kiếm văn bản  -> danh sách trang, đã xếp hạng
 *   2. Tra ảnh của các trang đó theo đúng thứ tự ấy      -> danh sách ảnh
 * </pre>
 *
 * <p>Nghĩa là ảnh được xếp hạng <b>gián tiếp</b>, thừa hưởng toàn bộ TF-IDF /
 * BM25 / PageRank / boost tiêu đề mà tab "Web" đã có. Một ảnh nằm trên trang
 * liên quan nhất sẽ hiện trước.
 *
 * <p>Vì sao không xếp hạng ảnh trực tiếp: tín hiệu để làm việc đó là nội dung
 * <i>của chính bức ảnh</i>, và hệ thống này không có nó — mặc định còn không
 * tải ảnh về. Xếp hạng theo {@code altText} thì chỉ là xếp hạng một chuỗi vài
 * từ, kém hơn hẳn so với xếp hạng cả trang chứa nó.
 *
 * <p><b>Hệ quả phải biết:</b> một trang rất liên quan nhưng không có ảnh sẽ
 * không đóng góp gì, còn một trang liên quan vừa phải mà nhiều ảnh có thể
 * chiếm phần lớn kết quả. {@link #FETCH_PAGE_MULTIPLIER} giảm bớt điều đó
 * bằng cách lấy nhiều trang hơn số ảnh cần, nhưng không xoá được nó.
 *
 * <h2>Vì sao trả kèm tiêu đề và URL trang</h2>
 *
 * <p>Đây là thứ phân biệt một kết quả ảnh dùng được với một lưới ảnh vô danh.
 * Người dùng bấm vào ảnh là để <b>tới trang chứa nó</b>, không phải để xem
 * riêng bức ảnh — đúng cách Cốc Cốc, Google Images và Bing đều làm. Nên mỗi
 * mục mang theo {@code pageTitle} và {@code pageUrl}.
 */
@RestController
@RequestMapping("/api")
public class ImageSearchController {

    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 30;

    /** Trần số lô, chặn {@code page} vô lý làm phép nhân tràn số nguyên. */
    private static final int MAX_PAGE = 100;

    /**
     * Số trang đã xếp hạng được quét để gom ảnh.
     *
     * <p><b>Cố định, KHÔNG phụ thuộc lô đang hỏi.</b> Đây là điều kiện để phân
     * trang đúng: mọi lô phải nhìn cùng một tập ảnh nền, nếu không thì cắt lát
     * ở lô sau sẽ lệch so với lô trước.
     *
     * <p><b>Con số này buộc phải bằng {@link #MAX_TOTAL_IMAGES}.</b> Từ khi
     * {@link ImageStore} chỉ giữ MỘT ảnh cho mỗi trang, quan hệ giữa hai đại
     * lượng trở thành một-một: quét N trang thì có nhiều nhất N ảnh.
     *
     * <p>Bản trước để 60 ở đây, và con số đó có căn cứ ĐÚNG VÀO LÚC ẤY — mỗi
     * trang cho 5–15 ảnh nên 60 trang là vài trăm ảnh. Giữ nguyên 60 sau khi
     * đổi sang một ảnh mỗi trang thì mọi truy vấn đều dừng ở 60 ảnh: người dùng
     * cuộn hết ba lô là hết, dù chỉ mục có hàng nghìn trang khớp.
     *
     * <p>Chi phí thật của việc nâng lên 300 vẫn nhỏ: {@code facade.search} có
     * cache, và bước tra ảnh chỉ là 300 lần tra bảng băm.
     */
    private static final int MAX_SCANNED_PAGES = 300;

    /**
     * Trần số ảnh gom cho một truy vấn.
     *
     * <p>Chặn ca bệnh lý: một truy vấn khớp 60 trang mà mỗi trang có 60 ảnh sẽ
     * là 3.600 bản ghi phải sắp xếp cho <i>mỗi lần cuộn</i>. Người dùng không
     * bao giờ cuộn tới đó — Google Images cũng chặn ở vài trăm.
     */
    private static final int MAX_TOTAL_IMAGES = 300;

    private final SearchEngineFacade facade;
    private final ImageStore imageStore;

    public ImageSearchController(SearchEngineFacade facade, ImageStore imageStore) {
        this.facade = facade;
        this.imageStore = imageStore;
    }

    @GetMapping("/images")
    public Map<String, Object> searchImages(
            @RequestParam("q") String q,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {

        long start = System.currentTimeMillis();
        int safeSize = size < 1 || size > MAX_SIZE ? DEFAULT_SIZE : size;
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);

        // Bước 1 — xếp hạng TRANG bằng máy tìm kiếm văn bản sẵn có.
        //
        // Số trang lấy KHÔNG phụ thuộc `page`: luôn quét cùng một tập trang đã
        // xếp hạng, rồi mới cắt lát ở bước 4. Đây là mấu chốt để phân trang
        // đúng — nếu số trang quét thay đổi theo `page` thì tập ảnh nền cũng
        // đổi, và ảnh sẽ vừa lặp vừa thiếu giữa các lô.
        SearchResponse pages = facade.search(q, 1, MAX_SCANNED_PAGES);

        // Giữ tiêu đề trang theo URL để gắn vào từng ảnh ở bước 2. LinkedHashMap
        // vì thứ tự chèn CHÍNH LÀ thứ tự xếp hạng, và ImageStore.forPages dựa
        // vào thứ tự đó.
        Map<String, String> titleByUrl = new LinkedHashMap<>();
        for (SearchResult result : pages.results()) {
            String url = result.url();
            if (url != null && !url.isBlank()) {
                titleByUrl.putIfAbsent(url, result.title());
            }
        }

        // Bước 2 — tra ảnh theo đúng thứ tự trang.
        //
        // Lấy TOÀN BỘ (tới trần MAX_TOTAL_IMAGES) chứ không chỉ một lô: phải
        // có cả danh sách thì sắp xếp ở bước 3 mới ổn định giữa các lô, và
        // phép cắt ở bước 4 mới không bỏ sót.
        List<ImageFound> images = new ArrayList<>(
                imageStore.forPages(new ArrayList<>(titleByUrl.keySet()), MAX_TOTAL_IMAGES));

        // Bước 3 — ĐẨY TRANG CÓ ẢNH TỐT LÊN TRƯỚC.
        //
        // Phần lọc ảnh trang trí NẶNG đã chuyển vào ImageQuality, chạy lúc
        // crawl: mỗi trang chỉ giữ tấm tốt nhất của nó, nên lưới không còn bị
        // logo của một trang duy nhất nuốt chỗ.
        //
        // Nhưng còn một ca mà bước đó không xử lý được: một trang mà TOÀN BỘ
        // ảnh đều là trang trí thì tấm "tốt nhất" của nó vẫn là một cái logo.
        // Phép sắp xếp này đẩy những trang như vậy xuống cuối.
        //
        // Sắp xếp ỔN ĐỊNH: trong cùng một nhóm, thứ tự xếp hạng trang được giữ
        // nguyên. Nên đây chỉ là một lần phân hoạch hai nhóm, không phải thay
        // thế phép xếp hạng của máy tìm kiếm.
        images.sort(Comparator.comparing(ImageFound::missingAlt));

        // Bước 4 — CẮT LÁT cho lô đang hỏi.
        //
        // Ba bước trên hoàn toàn không phụ thuộc `page`, nên danh sách nền là
        // như nhau ở mọi lô. Nhờ vậy lô 2 nối đúng vào sau lô 1: không ảnh nào
        // hiện hai lần, không ảnh nào bị nhảy qua.
        int total = images.size();
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<ImageFound> slice = images.subList(from, to);

        List<Map<String, Object>> items = new ArrayList<>(slice.size());
        for (ImageFound image : slice) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("imageUrl", image.imageUrl());
            item.put("pageUrl", image.pageUrl());
            item.put("pageTitle", titleByUrl.getOrDefault(image.pageUrl(), image.pageUrl()));
            item.put("host", image.host());
            item.put("altText", image.altText());
            item.put("width", image.declaredWidth());
            item.put("height", image.declaredHeight());
            item.put("missingAlt", image.missingAlt());
            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", q);
        response.put("results", items);
        response.put("page", safePage);
        response.put("pageSize", safeSize);
        // TỔNG số ảnh khớp truy vấn, không phải số ảnh trong lô này. Giao diện
        // cần nó để hiện "N ảnh" ngay từ lô đầu thay vì một con số lớn dần lên
        // theo mỗi lần cuộn.
        response.put("totalResults", total);
        // Còn lô nữa không. Tính ở máy chủ chứ không để giao diện tự suy từ
        // `results.length === pageSize`: cách suy đó sai đúng ở ca biên hay
        // gặp nhất — khi tổng số ảnh chia hết cho pageSize, lô cuối đầy đủ nên
        // giao diện tưởng còn nữa, gọi thêm một lần rồi nhận về rỗng.
        response.put("hasMore", to < total);
        // Số trang đã xét — cần cho giao diện phân biệt HAI ca hoàn toàn khác
        // nhau mà nếu chỉ nhìn `results` rỗng thì trông giống hệt:
        //   pagesScanned = 0  -> truy vấn không khớp trang nào
        //   pagesScanned > 0  -> có trang khớp, nhưng chưa trang nào được
        //                        Image Download Service xử lý
        // Ca thứ hai cần một thông báo khác hẳn: "hãy chạy crawl", không phải
        // "không tìm thấy".
        response.put("pagesScanned", titleByUrl.size());
        response.put("timeTakenMs", System.currentTimeMillis() - start);
        return response;
    }
}
