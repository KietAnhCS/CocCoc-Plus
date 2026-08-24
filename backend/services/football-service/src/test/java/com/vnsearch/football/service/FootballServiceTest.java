package com.vnsearch.football.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnsearch.football.config.FootballProperties;
import com.vnsearch.football.model.League;
import com.vnsearch.football.store.CacheEntry;
import com.vnsearch.football.store.FootballStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FootballServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private FootballStore store;
    private FootballProperties properties;

    @BeforeEach
    void setUp() {
        store = mock(FootballStore.class);
        properties = new FootballProperties();
        properties.setApiKey("khoa-that");
    }

    private FootballService service() {
        return new FootballService(properties, store, mapper, clock);
    }

    private String encoded(String leagueName) throws Exception {
        return mapper.writeValueAsString(
                List.of(new League("47", leagueName, "ENG", "icon.png", "flag.png", "Active")));
    }

    @Test
    void cacheKeyNormalisesCaseAndWhitespace() {
        assertThat(FootballService.cacheKey("leagues", " Arsenal ", ""))
                .isEqualTo("v1:leagues:arsenal:-");
        assertThat(FootballService.cacheKey("leagues", "ARSENAL", null))
                .isEqualTo("v1:leagues:arsenal:-");
    }

    @Test
    void withoutAnApiKeyReturnsEmptyAndNeverTouchesTheCache() {
        properties.setApiKey("");

        Payload<List<League>> payload = service().leagues("", "");

        assertThat(payload.source()).isEqualTo(Source.UNAVAILABLE);
        assertThat(payload.data()).isEmpty();
        verify(store, never()).find(anyString());
    }

    @Test
    void freshCacheIsReturnedDirectlyWithoutAnyOutboundCall() throws Exception {
        when(store.find(anyString())).thenReturn(Optional.of(new CacheEntry(
                encoded("Premier League"), NOW.minusSeconds(60), NOW.plusSeconds(600))));

        Payload<List<League>> payload = service().leagues("", "");

        assertThat(payload.source()).isEqualTo(Source.CACHE);
        assertThat(payload.data()).singleElement()
                .extracting(League::name).isEqualTo("Premier League");
        assertThat(payload.cachedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(store, never()).put(anyString(), anyString(), any());
    }

    @Test
    void exhaustedQuotaReturnsStaleCacheInsteadOfEmpty() throws Exception {
        when(store.find(anyString())).thenReturn(Optional.of(new CacheEntry(
                encoded("Premier League"), NOW.minusSeconds(7200), NOW.minusSeconds(3600))));
        when(store.callsSince(any())).thenReturn(properties.getDailyBudget());

        Payload<List<League>> payload = service().leagues("", "");

        assertThat(payload.source()).isEqualTo(Source.STALE);
        assertThat(payload.stale()).isTrue();
        assertThat(payload.data()).singleElement()
                .extracting(League::name).isEqualTo("Premier League");
    }

    @Test
    void exhaustedQuotaWithoutCacheReturnsEmpty() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        when(store.callsSince(any())).thenReturn(properties.getDailyBudget());

        Payload<List<League>> payload = service().leagues("", "");

        assertThat(payload.source()).isEqualTo(Source.UNAVAILABLE);
        assertThat(payload.data()).isEmpty();
    }

    @Test
    void corruptCacheRecordIsTreatedAsMissing() {
        when(store.find(anyString())).thenReturn(Optional.of(
                new CacheEntry("{khong-phai-json}", NOW.minusSeconds(60), NOW.plusSeconds(600))));
        when(store.callsSince(any())).thenReturn(properties.getDailyBudget());

        Payload<List<League>> payload = service().leagues("", "");

        assertThat(payload.source()).isEqualTo(Source.UNAVAILABLE);
    }
}
