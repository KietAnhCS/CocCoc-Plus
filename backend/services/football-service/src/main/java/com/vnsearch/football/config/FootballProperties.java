package com.vnsearch.football.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "football")
public class FootballProperties {

    private String apiBaseUrl = "https://v3.football.api-sports.io";
    private String apiKey = "";
    private int dailyBudget = 95;
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Duration liveTtl = Duration.ofMinutes(15);
    private Duration seasonTtl = Duration.ofHours(24);
    private Duration metadataTtl = Duration.ofDays(7);

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getDailyBudget() {
        return dailyBudget;
    }

    public void setDailyBudget(int dailyBudget) {
        this.dailyBudget = dailyBudget;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getLiveTtl() {
        return liveTtl;
    }

    public void setLiveTtl(Duration liveTtl) {
        this.liveTtl = liveTtl;
    }

    public Duration getSeasonTtl() {
        return seasonTtl;
    }

    public void setSeasonTtl(Duration seasonTtl) {
        this.seasonTtl = seasonTtl;
    }

    public Duration getMetadataTtl() {
        return metadataTtl;
    }

    public void setMetadataTtl(Duration metadataTtl) {
        this.metadataTtl = metadataTtl;
    }
}
