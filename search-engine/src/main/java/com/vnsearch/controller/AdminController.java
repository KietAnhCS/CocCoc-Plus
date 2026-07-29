package com.vnsearch.controller;

import com.vnsearch.service.SearchEngineFacade;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SearchEngineFacade facade;

    public AdminController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    public record CrawlRequest(List<String> seedUrls, Integer maxDepth, Integer maxPages) {
    }

    @PostMapping("/crawl")
    public Map<String, String> crawl(@RequestBody CrawlRequest request) {
        if (request.seedUrls() == null || request.seedUrls().isEmpty()) {
            throw new IllegalArgumentException("seedUrls khong duoc de rong");
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

    @PostMapping("/reindex")
    public Map<String, String> reindex() throws IOException {
        facade.reindex();
        return Map.of("status", "OK");
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return facade.getStats();
    }
}
