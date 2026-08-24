package com.vnsearch.football.store;

import java.time.Instant;

public record CacheEntry(String payload, Instant fetchedAt, Instant expiresAt) {

    public boolean expired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
