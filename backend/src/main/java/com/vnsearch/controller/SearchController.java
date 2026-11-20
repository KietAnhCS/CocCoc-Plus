package com.vnsearch.controller;

import com.vnsearch.model.SearchResponse;
import com.vnsearch.service.SearchEngineFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint tim kiem: GET /api/search?q={query}&amp;page={n}&amp;size={20}. */
@RestController
@RequestMapping("/api")
public class SearchController {

    /**
     * Trang toi da cho phep hoi.
     *
     * <p>Truoc day {@code page} chi bi ep {@code >= 1}, khong co chan tren. Ma
     * {@code SearchEngineFacade} tinh {@code topN = page * size} — voi
     * {@code page=30000000} va {@code size=100}, phep nhan nay <b>TRAN int</b>
     * va {@code topN} nhan mot gia tri vo nghia (co the am).
     *
     * <p>Hien tai hau qua bang khong, vi {@code MinHeap.topK} khong bao gio giu
     * nhieu hon so ung vien that. Nhung do la mot bat bien duoc mot lop KHAC
     * giu ho — dung loai phu thuoc ngam ma phan con lai cua du an can than
     * tranh. Chan o day, tai cho nguoi dung nhap vao.
     *
     * <p>1.000 trang x 100 ket qua = 100.000 ket qua. Khong ai cuon toi do; may
     * tim kiem that con chan thap hon nhieu.
     */
    private static final int MAX_PAGE = 1_000;

    /** Chan tren cho so ket qua moi trang. Vuot qua thi dung mac dinh. */
    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    private final SearchEngineFacade facade;

    public SearchController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String q,
                                  @RequestParam(value = "page", defaultValue = "1") int page,
                                  @RequestParam(value = "size", defaultValue = "20") int size) {
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safeSize = size < 1 || size > MAX_SIZE ? DEFAULT_SIZE : size;
        return facade.search(q, safePage, safeSize);
    }
}
