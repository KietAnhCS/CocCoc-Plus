package com.vnsearch.controller;

import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.modular.ImageStore;
import com.vnsearch.model.WebDocument;
import com.vnsearch.service.SearchEngineFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dòng tin cho trang tab mới: {@code GET /api/feed?page=&size=}.
 *
 * <p>Khác {@code /api/search} ở một điểm căn bản: <b>không có truy vấn</b>.
 * Người dùng vừa mở tab mới, chưa gõ gì cả — nên không có điểm liên quan nào
 * để xếp hạng. Đây là bài toán <i>duyệt</i> chỉ mục, không phải <i>truy vấn</i>
 * chỉ mục, và mọi đường đi qua {@code SearchEngineFacade.search} đều không
 * dùng được.
 *
 * <h2>Sắp theo thời gian crawl, mới nhất trước</h2>
 *
 * <p>Thứ tự dòng tin là {@code crawledAt} giảm dần — tức theo LẦN CRAWLER
 * chạy, bài thu thập gần đây nhất lên đầu. Tài liệu thiếu {@code crawledAt}
 * bị đẩy xuống cuối.
 *
 * <p>Thứ tự này <b>tất định</b>: cùng chỉ mục thì mọi lô nhìn cùng một dãy,
 * nên lô 2 nối khít vào sau lô 1 khi cuộn. Máy chủ <b>không giữ trạng thái
 * phiên</b> — nó chỉ đọc chỉ mục và cắt lát theo {@code page}/{@code size}.
 *
 * <h2>Chỉ lấy bài CÓ ảnh</h2>
 *
 * <p>Một dòng tin mà phần lớn ô là khối màu xám trông như đang hỏng. Nên bài
 * không có ảnh bị bỏ qua hoàn toàn chứ không hiện kèm ảnh giữ chỗ.
 *
 * <p>Phép lọc này <b>tất định</b> (cùng chỉ mục, cùng kho ảnh thì cùng kết
 * quả) nên nó không phá tính ổn định của phân trang.
 *
 * <p><b>Hệ quả phải biết:</b> ngay sau khi khởi động lại backend, {@link ImageStore}
 * rỗng nên dòng tin cũng rỗng — dù chỉ mục vẫn đầy đủ. Giao diện phân biệt
 * được ca này nhờ trường {@code indexedDocuments} trong phản hồi, và nói đúng
 * "hãy chạy crawl" thay vì "không có tin".
 */
@RestController
@RequestMapping("/api")
public class FeedController {

    private static final int MAX_SIZE = 50;
    private static final int DEFAULT_SIZE = 12;
    private static final int MAX_PAGE = 100;

    /**
     * Trần số bài gom cho một dòng tin.
     *
     * <p>Chặn chi phí ở corpus lớn: với 30.000 tài liệu thì xáo trộn và tra
     * ảnh cho toàn bộ là việc thừa — không ai cuộn tới bài thứ 200. Cắt sớm
     * giữ cho mỗi lô về trong vài mili-giây bất kể corpus lớn đến đâu.
     */
    private static final int MAX_FEED_ITEMS = 200;

    /** Độ dài đoạn trích, đủ hai dòng trên thẻ tin. */
    private static final int SNIPPET_LENGTH = 160;

    private final SearchEngineFacade facade;
    private final ImageStore imageStore;

    public FeedController(SearchEngineFacade facade, ImageStore imageStore) {
        this.facade = facade;
        this.imageStore = imageStore;
    }

    @GetMapping("/feed")
    public Map<String, Object> feed(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "12") int size) {

        long start = System.currentTimeMillis();
        int safeSize = size < 1 || size > MAX_SIZE ? DEFAULT_SIZE : size;
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);

        int totalDocs = facade.getIndexedDocumentCount();

        // Sắp DANH SÁCH ID, không phải danh sách tài liệu: id là số nguyên,
        // nên sắp 30.000 phần tử là gần như miễn phí.
        List<Integer> order = new ArrayList<>(totalDocs);
        for (int i = 0; i < totalDocs; i++) {
            order.add(i);
        }
        // crawledAt giảm dần: bài crawl gần đây nhất lên đầu, thiếu mốc thì
        // xuống cuối. Thứ tự tất định nên các lô nối khít vào nhau khi cuộn.
        order.sort(Comparator.comparing(
                (Integer docId) -> {
                    WebDocument doc = facade.getDocumentAt(docId);
                    return doc == null ? null : doc.getCrawledAt();
                },
                Comparator.nullsLast(Comparator.<Instant>reverseOrder())));

        // Gom tới trần, CHỈ những bài có ảnh.
        List<Map<String, Object>> items = new ArrayList<>();
        for (int docId : order) {
            if (items.size() >= MAX_FEED_ITEMS) {
                break;
            }
            WebDocument doc = facade.getDocumentAt(docId);
            if (doc == null || doc.getUrl() == null || doc.getUrl().isBlank()) {
                continue;
            }
            List<ImageFound> images = imageStore.forPage(doc.getUrl());
            if (images.isEmpty()) {
                continue;
            }
            // Ảnh ĐẦU TIÊN theo thứ tự DOM — với một bài báo thì gần như luôn
            // là ảnh chính. Xem ImageStore về việc vì sao thứ tự đó được giữ.
            items.add(toCard(doc, images.get(0)));
        }

        int total = items.size();
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", items.subList(from, to));
        response.put("page", safePage);
        response.put("pageSize", safeSize);
        response.put("totalResults", total);
        response.put("hasMore", to < total);
        // Để giao diện phân biệt "chỉ mục rỗng" với "chỉ mục có nhưng chưa thu
        // thập ảnh" — hai ca cần hai câu thông báo khác hẳn nhau.
        response.put("indexedDocuments", totalDocs);
        response.put("timeTakenMs", System.currentTimeMillis() - start);
        return response;
    }

    private Map<String, Object> toCard(WebDocument doc, ImageFound image) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("url", doc.getUrl());
        card.put("title", doc.getTitle() == null || doc.getTitle().isBlank()
                ? doc.getUrl() : doc.getTitle());
        card.put("snippet", snippetOf(doc));
        card.put("imageUrl", image.imageUrl());
        card.put("altText", image.altText());
        card.put("host", image.host());
        card.put("crawledAt", doc.getCrawledAt());
        return card;
    }

    /**
     * Đoạn trích cho thẻ tin.
     *
     * <p>Ưu tiên {@code metaDescription} vì nó do chính trang viết ra để tóm
     * tắt mình. Chỉ khi thiếu mới cắt từ thân bài — và thân bài <b>có thể
     * null</b>: tài liệu trong chỉ mục là bản {@code withoutBodyText()}, phần
     * văn bản được giữ riêng ở dạng đã nén. Quên điều đó là một NPE ở đúng
     * đường chạy nóng nhất của trang chủ.
     */
    private static String snippetOf(WebDocument doc) {
        String text = doc.getMetaDescription();
        if (text == null || text.isBlank()) {
            text = doc.getBodyText();
        }
        if (text == null || text.isBlank()) {
            return "";
        }
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() <= SNIPPET_LENGTH
                ? clean
                : clean.substring(0, SNIPPET_LENGTH).trim() + "…";
    }
}
