package com.vnsearch.model;

import java.time.Instant;

/**
 * Mot ket qua tim kiem tra ve cho client, khop voi hop dong REST API
 * GET /api/search (xem docs/api-examples.http).
 */
public class SearchResult {

    private String title;
    private String url;
    private String snippet;
    private double score;
    private double tfidfScore;
    private double pageRankScore;
    private Instant crawledAt;

    public SearchResult() {
    }

    public SearchResult(String title, String url, String snippet, double score,
                         double tfidfScore, double pageRankScore, Instant crawledAt) {
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.score = score;
        this.tfidfScore = tfidfScore;
        this.pageRankScore = pageRankScore;
        this.crawledAt = crawledAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getTfidfScore() {
        return tfidfScore;
    }

    public void setTfidfScore(double tfidfScore) {
        this.tfidfScore = tfidfScore;
    }

    public double getPageRankScore() {
        return pageRankScore;
    }

    public void setPageRankScore(double pageRankScore) {
        this.pageRankScore = pageRankScore;
    }

    public Instant getCrawledAt() {
        return crawledAt;
    }

    public void setCrawledAt(Instant crawledAt) {
        this.crawledAt = crawledAt;
    }
}
