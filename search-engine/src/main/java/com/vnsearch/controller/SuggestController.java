package com.vnsearch.controller;

import com.vnsearch.service.SearchEngineFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST endpoint goi y tu khoa (autocomplete), dua tren Trie: GET /api/suggest?prefix={p}&amp;limit={10}. */
@RestController
@RequestMapping("/api")
public class SuggestController {

    private final SearchEngineFacade facade;

    public SuggestController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/suggest")
    public Map<String, List<String>> suggest(@RequestParam("prefix") String prefix,
                                              @RequestParam(value = "limit", defaultValue = "10") int limit) {
        int safeLimit = limit < 1 || limit > 50 ? 10 : limit;
        return Map.of("suggestions", facade.suggest(prefix, safeLimit));
    }
}
