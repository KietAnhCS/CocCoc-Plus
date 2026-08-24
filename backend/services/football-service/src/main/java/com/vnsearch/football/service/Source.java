package com.vnsearch.football.service;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Source {
    LIVE("live"),
    CACHE("cache"),
    STALE("stale"),
    UNAVAILABLE("unavailable");

    private final String wire;

    Source(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
