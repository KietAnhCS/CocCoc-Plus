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

    private final SearchEngineFacade facade;

    public SearchController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String q,
                                  @RequestParam(value = "page", defaultValue = "1") int page,
                                  @RequestParam(value = "size", defaultValue = "20") int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size < 1 || size > 100 ? 20 : size;
        return facade.search(q, safePage, safeSize);
    }
}
