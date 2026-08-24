package com.vnsearch.football;

import com.vnsearch.controller.FootballController;
import com.vnsearch.football.model.League;
import com.vnsearch.football.service.FootballService;
import com.vnsearch.football.service.Payload;
import com.vnsearch.football.service.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FootballControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private FootballService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FootballService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new FootballController(service, clock)).build();
    }

    @Test
    void ngayViaPhamDinhDangBiChanTruocKhiTonHanMuc() throws Exception {
        mvc.perform(get("/api/v1/fixtures").param("date", "24-08-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_DATE"));

        verify(service, never()).fixturesByDate(anyString(), anyString(), anyString());
    }

    @Test
    void ngayKhongTonTaiBiChan() throws Exception {
        mvc.perform(get("/api/v1/fixtures").param("date", "2026-02-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_DATE"));
    }

    @Test
    void timDoiKhongCoBoLocNaoThiBiChan() throws Exception {
        mvc.perform(get("/api/v1/teams"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_FILTER"));
    }

    @Test
    void timCauThuDuoiBaKyTuThiBiChan() throws Exception {
        mvc.perform(get("/api/v1/players").param("search", "ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SEARCH_TOO_SHORT"));

        verify(service, never()).players(anyString());
    }

    @Test
    void phanHoiMangTheoMocThoiGianLayDuLieu() throws Exception {
        when(service.leagues("", "")).thenReturn(Payload.of(
                List.of(new League("47", "Premier League", "ENG", "icon.png", "flag.png",
                        "Active")),
                Source.STALE, NOW.minusSeconds(3600)));

        mvc.perform(get("/api/v1/leagues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Premier League"))
                .andExpect(jsonPath("$.meta.source").value("stale"))
                .andExpect(jsonPath("$.meta.stale").value(true));
    }

    @Test
    void khongTimThayCauThuThiTraBonKhongBon() throws Exception {
        when(service.player("999", "2026")).thenReturn(Payload.of(null, Source.UNAVAILABLE, NOW));

        mvc.perform(get("/api/v1/players/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void muaGiaiSuyRaTheoNamBatDau() throws Exception {
        when(service.player("10", "2026")).thenReturn(Payload.of(null, Source.UNAVAILABLE, NOW));

        mvc.perform(get("/api/v1/players/10")).andExpect(status().isNotFound());

        verify(service).player("10", "2026");
    }
}
