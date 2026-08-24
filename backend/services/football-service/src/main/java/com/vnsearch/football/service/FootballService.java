package com.vnsearch.football.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnsearch.football.config.FootballProperties;
import com.vnsearch.football.model.FixtureQuery;
import com.vnsearch.football.model.League;
import com.vnsearch.football.model.Match;
import com.vnsearch.football.model.Player;
import com.vnsearch.football.model.Team;
import com.vnsearch.football.provider.ApiFootballClient;
import com.vnsearch.football.provider.CallRecorder;
import com.vnsearch.football.provider.FootballProvider;
import com.vnsearch.football.provider.LiveFootballClient;
import com.vnsearch.football.provider.ProviderException;
import com.vnsearch.football.store.CacheEntry;
import com.vnsearch.football.store.FootballStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class FootballService implements CallRecorder {

    private static final Logger log = LoggerFactory.getLogger(FootballService.class);
    private static final String SETTING_API_KEY = "api_key";

    private final FootballProperties properties;
    private final FootballStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    private volatile String apiKey;
    private volatile FootballProvider provider;

    public FootballService(FootballProperties properties, FootballStore store,
                           ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        applyKey(properties.getApiKey());
    }

    private FootballProvider newProvider(String key) {
        if (properties.getApiBaseUrl().contains(LiveFootballClient.HOST)) {
            return new LiveFootballClient(properties.getApiBaseUrl(), key,
                    properties.getRequestTimeout(), this, mapper);
        }
        return new ApiFootballClient(properties.getApiBaseUrl(), key,
                properties.getRequestTimeout(), this, mapper);
    }

    private void applyKey(String key) {
        this.provider = newProvider(key);
        this.apiKey = key;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String providerName() {
        return provider.name();
    }

    public void restoreApiKey() {
        if (!properties.getApiKey().isBlank()) {
            return;
        }
        try {
            String saved = store.setting(SETTING_API_KEY);
            if (saved != null && !saved.isBlank()) {
                applyKey(saved);
                log.info("đã nạp lại khoá API dán từ giao diện");
            }
        } catch (DataAccessException unreadable) {
            log.warn("không đọc được khoá đã lưu: {}", unreadable.getMessage());
        }
    }

    public void setApiKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isBlank()) {
            throw new ProviderException("khoá rỗng");
        }

        newProvider(key).leagues("", "Premier League");

        try {
            store.putSetting(SETTING_API_KEY, key);
        } catch (DataAccessException unwritable) {
            log.warn("không lưu được khoá, chỉ dùng cho phiên này: {}", unwritable.getMessage());
        }
        applyKey(key);
    }

    @Override
    public void recordCall(String endpoint, String params) {
        try {
            store.recordCall(endpoint, params);
        } catch (DataAccessException unwritable) {
            log.warn("không ghi được sổ lượt gọi {}: {}", endpoint, unwritable.getMessage());
        }
    }

    public int used() {
        return store.callsSince(startOfDay());
    }

    public int budget() {
        return properties.getDailyBudget();
    }

    private Instant startOfDay() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private boolean budgetLeft() {
        try {
            return used() < budget();
        } catch (DataAccessException unreadable) {
            log.warn("không đếm được hạn mức, tạm coi là đã hết: {}", unreadable.getMessage());
            return false;
        }
    }

    public Payload<List<League>> leagues(String country, String search) {
        return resolve(cacheKey("leagues", country, search), properties.getMetadataTtl(),
                () -> provider.leagues(country, search),
                new TypeReference<List<League>>() {
                }, List.<League>of());
    }

    public Payload<List<Match>> fixturesByDate(String date, String leagueId, String season) {
        Duration ttl = properties.getLiveTtl();
        try {
            if (LocalDate.parse(date).isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
                ttl = properties.getMetadataTtl();
            }
        } catch (DateTimeParseException malformed) {
            ttl = properties.getLiveTtl();
        }

        return resolve(cacheKey("fixtures:date", date, leagueId, season), ttl,
                () -> provider.fixtures(FixtureQuery.byDate(date, leagueId, season)),
                new TypeReference<List<Match>>() {
                }, List.<Match>of());
    }

    public Payload<List<Match>> teamFixtures(String teamId, String season, String leagueId) {
        return resolve(cacheKey("fixtures:team", teamId, season, leagueId), seasonTtl(season),
                () -> provider.fixtures(FixtureQuery.byTeam(teamId, season, leagueId)),
                new TypeReference<List<Match>>() {
                }, List.<Match>of());
    }

    public Payload<List<Match>> leagueFixtures(String leagueId, String season) {
        return resolve(cacheKey("fixtures:league", leagueId, season), seasonTtl(season),
                () -> provider.fixtures(FixtureQuery.byLeague(leagueId, season)),
                new TypeReference<List<Match>>() {
                }, List.<Match>of());
    }

    public Payload<List<Team>> teams(String search, String leagueId, String season) {
        return resolve(cacheKey("teams", search, leagueId, season), properties.getMetadataTtl(),
                () -> provider.teams(search, leagueId, season),
                new TypeReference<List<Team>>() {
                }, List.<Team>of());
    }

    public Payload<List<Player>> players(String search) {
        return resolve(cacheKey("players", search), properties.getMetadataTtl(),
                () -> provider.players(search),
                new TypeReference<List<Player>>() {
                }, List.<Player>of());
    }

    public Payload<Player> player(String playerId, String season) {
        return resolve(cacheKey("player", playerId, season), seasonTtl(season),
                () -> provider.player(playerId, season).orElse(null),
                new TypeReference<Player>() {
                }, null);
    }

    private Duration seasonTtl(String season) {
        try {
            int year = Integer.parseInt(season.trim());
            if (year < LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear()) {
                return properties.getMetadataTtl();
            }
        } catch (NumberFormatException notAYear) {
            return properties.getSeasonTtl();
        }
        return properties.getSeasonTtl();
    }

    private <T> Payload<T> resolve(String key, Duration ttl, Supplier<T> live,
                                   TypeReference<T> type, T fallback) {
        Instant now = clock.instant();

        if (!hasApiKey()) {
            return Payload.of(fallback, Source.UNAVAILABLE, now);
        }

        Optional<CacheEntry> entry = readCache(key);

        if (entry.isPresent() && !entry.get().expired(now)) {
            Optional<T> decoded = decode(key, entry.get(), type);
            if (decoded.isPresent()) {
                return Payload.of(decoded.get(), Source.CACHE, entry.get().fetchedAt());
            }
        }

        if (!budgetLeft()) {
            return stale(key, entry, type)
                    .orElseGet(() -> Payload.of(fallback, Source.UNAVAILABLE, now));
        }

        T data;
        try {
            data = live.get();
        } catch (ProviderException failure) {
            log.error("gọi nhà cung cấp hỏng cho {}: {}", key, failure.getMessage());
            return stale(key, entry, type)
                    .orElseGet(() -> Payload.of(fallback, Source.UNAVAILABLE, now));
        }

        writeCache(key, data, now.plus(ttl));
        return Payload.of(data, Source.LIVE, now);
    }

    private <T> Optional<Payload<T>> stale(String key, Optional<CacheEntry> entry,
                                           TypeReference<T> type) {
        return entry.flatMap(found -> decode(key, found, type)
                .map(data -> Payload.of(data, Source.STALE, found.fetchedAt())));
    }

    private Optional<CacheEntry> readCache(String key) {
        try {
            return store.find(key);
        } catch (DataAccessException unreadable) {
            log.warn("đọc đệm {} hỏng, bỏ qua đệm: {}", key, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> decode(String key, CacheEntry entry, TypeReference<T> type) {
        try {
            return Optional.ofNullable(mapper.readValue(entry.payload(), type));
        } catch (Exception broken) {
            log.warn("bản ghi đệm {} không giải mã được: {}", key, broken.getMessage());
            return Optional.empty();
        }
    }

    private <T> void writeCache(String key, T data, Instant expiresAt) {
        try {
            store.put(key, mapper.writeValueAsString(data), expiresAt);
        } catch (Exception unwritable) {
            log.warn("không ghi được đệm {}: {}", key, unwritable.getMessage());
        }
    }

    static String cacheKey(String... parts) {
        StringBuilder key = new StringBuilder("v1");
        for (String part : parts) {
            String cleaned = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            key.append(':').append(cleaned.isEmpty() ? "-" : cleaned);
        }
        return key.toString();
    }
}
