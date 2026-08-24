package com.vnsearch.controller;

import com.vnsearch.service.SearchEngineFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint goi y tu khoa (autocomplete), dua tren Trie:
 * GET /api/suggest?q={p}&amp;limit={10}.
 *
 * <p>Nhan CA HAI ten tham so, {@code q} va {@code prefix}. Ban dau chi co
 * {@code prefix}, va do la endpoint DUY NHAT trong ca API dat ten khac: moi
 * cho khac deu la {@code q} ({@code /api/search?q=}, {@code /api/images?q=}).
 * Giao dien gui {@code q} theo dung le do, nen no nhan 400 o MOI phim go — va
 * vi {@code searchApi.suggest} nuot loi bang {@code catch { return [] }}, o
 * goi y khong bao gio hien mot dong nao ma cung khong bao mot loi nao.
 *
 * <p>{@code prefix} van duoc giu de khong pha thu gi dang goi bang ten cu.
 */
@RestController
@RequestMapping("/api")
public class SuggestController {

    private final SearchEngineFacade facade;

    public SuggestController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/suggest")
    public Map<String, List<String>> suggest(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        String tuKhoa = q != null && !q.isBlank() ? q : prefix;
        if (tuKhoa == null || tuKhoa.isBlank()) {
            return Map.of("suggestions", List.of());
        }
        int safeLimit = limit < 1 || limit > 50 ? 10 : limit;
        return Map.of("suggestions", facade.suggest(tuKhoa, safeLimit));
    }
}
