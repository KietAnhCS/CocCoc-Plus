package com.vnsearch.football.store;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class FootballStore {

    private final JdbcTemplate jdbc;

    public FootballStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CacheEntry> find(String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT payload, fetched_at, expires_at
                    FROM football.api_cache
                    WHERE cache_key = ?
                    """,
                    (rs, rowNum) -> {
                        Timestamp expires = rs.getTimestamp("expires_at");
                        return new CacheEntry(
                                rs.getString("payload"),
                                rs.getTimestamp("fetched_at").toInstant(),
                                expires == null ? null : expires.toInstant());
                    },
                    key));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    public void put(String key, String payload, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO football.api_cache (cache_key, payload, fetched_at, expires_at)
                VALUES (?, ?::jsonb, now(), ?)
                ON CONFLICT (cache_key) DO UPDATE
                SET payload = EXCLUDED.payload,
                    fetched_at = EXCLUDED.fetched_at,
                    expires_at = EXCLUDED.expires_at
                """,
                key, payload, expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    public void recordCall(String endpoint, String params) {
        jdbc.update("INSERT INTO football.api_call_log (endpoint, params) VALUES (?, ?)",
                endpoint, params);
    }

    public int callsSince(Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM football.api_call_log WHERE called_at >= ?",
                Integer.class, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    public String setting(String name) {
        try {
            return jdbc.queryForObject(
                    "SELECT value FROM football.settings WHERE name = ?", String.class, name);
        } catch (EmptyResultDataAccessException absent) {
            return "";
        }
    }

    public void putSetting(String name, String value) {
        jdbc.update("""
                INSERT INTO football.settings (name, value, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (name) DO UPDATE
                SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
                """,
                name, value);
    }
}
