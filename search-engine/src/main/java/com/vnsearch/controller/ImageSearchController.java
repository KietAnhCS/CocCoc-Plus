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

    /**
     * Lấy nhiều trang hơn số ảnh cần, vì phần lớn trang có <b>vài</b> ảnh chứ
     * không phải một.
     *
     * <p>Đo trên trang thật: vnexpress.net 31 thẻ {@code <img>} mỗi trang,
     * tuoitre.vn 36. Nhưng phần lớn bị loại vì không có đuôi ảnh (icon SVG
     * nội tuyến, ảnh theo dõi) — nên số ảnh thật lọt vào kho thấp hơn nhiều.
     * Hệ số 2 là thoả hiệp: đủ để lấp đầy lưới, không đủ để biến một truy vấn
     * thành việc quét cả chỉ mục.
     */
    private static final int FETCH_PAGE_MULTIPLIER = 2;

    private final SearchEngineFacade facade;
    private final ImageStore imageStore;

    public ImageSearchController(SearchEngineFacade facade, ImageStore imageStore) {
        this.facade = facade;
        this.imageStore = imageStore;
    }

    @GetMapping("/images")
    public Map<String, Object> searchImages(
            @RequestParam("q") String q,
            @RequestParam(value = "size", defaultValue = "30") int size) {

        long start = System.currentTimeMillis();
        int safeSize = size < 1 || size > MAX_SIZE ? DEFAULT_SIZE : size;

        // Bước 1 — xếp hạng TRANG bằng máy tìm kiếm văn bản sẵn có.
        SearchResponse pages = facade.search(q, 1, safeSize * FETCH_PAGE_MULTIPLIER);

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
        List<ImageFound> images =
                imageStore.forPages(new ArrayList<>(titleByUrl.keySet()), safeSize);

        List<Map<String, Object>> items = new ArrayList<>(images.size());
        for (ImageFound image : images) {
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
        response.put("totalResults", items.size());
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
