package com.vnsearch.model;

import java.util.List;

/** Response tra ve cho GET /api/search. */
public class SearchResponse {

    private String query;
    private int totalResults;
    private int page;
    private long timeTakenMs;
    private List<SearchResult> results;

    public SearchResponse() {
    }

    public SearchResponse(String query, int totalResults, int page, long timeTakenMs, List<SearchResult> results) {
        this.query = query;
        this.totalResults = totalResults;
        this.page = page;
        this.timeTakenMs = timeTakenMs;
        this.results = results;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int getTotalResults() {
        return totalResults;
    }

    public void setTotalResults(int totalResults) {
        this.totalResults = totalResults;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public long getTimeTakenMs() {
        return timeTakenMs;
    }

    public void setTimeTakenMs(long timeTakenMs) {
        this.timeTakenMs = timeTakenMs;
    }

    public List<SearchResult> getResults() {
        return results;
    }

    public void setResults(List<SearchResult> results) {
        this.results = results;
    }
}
