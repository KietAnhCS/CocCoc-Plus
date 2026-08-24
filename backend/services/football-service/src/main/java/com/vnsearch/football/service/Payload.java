package com.vnsearch.football.service;

import java.time.Instant;

public record Payload<T>(T data, Source source, Instant cachedAt) {

    public static <T> Payload<T> of(T data, Source source, Instant cachedAt) {
        return new Payload<>(data, source, cachedAt);
    }

    public boolean stale() {
        return source == Source.STALE;
    }
}
