package com.vnsearch.controller;

import com.vnsearch.football.model.League;
import com.vnsearch.football.model.Match;
import com.vnsearch.football.model.Player;
import com.vnsearch.football.model.Team;
import com.vnsearch.football.provider.ProviderException;
import com.vnsearch.football.service.FootballService;
import com.vnsearch.football.service.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class FootballController {

    private static final Logger log = LoggerFactory.getLogger(FootballController.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final int MIN_SEARCH_LENGTH = 3;
    private static final int FIRST_MONTH_OF_NEW_SEASON = 7;

    private final FootballService service;
    private final Clock clock;

    public FootballController(FootballService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    public record Envelope(Object data, Meta meta) {
    }

    public record Meta(Instant cachedAt, String source, boolean stale) {
    }

    public record ApiKeyRequest(String key) {
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "sampleOnly", !service.hasApiKey());
    }

    @GetMapping("/status")
    public ResponseEntity<Object> status() {
        int used;
        try {
            used = service.used();
        } catch (DataAccessException unreadable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "USAGE_UNAVAILABLE",
                    "Không đọc được sổ hạn mức.");
        }

        int budget = service.budget();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("used", used);
        body.put("budget", budget);
        body.put("remaining", Math.max(0, budget - used));
        body.put("sampleOnly", !service.hasApiKey());
        body.put("provider", service.providerName());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/leagues")
    public Envelope leagues(@RequestParam(defaultValue = "") String country,
                            @RequestParam(defaultValue = "") String search) {
        return envelope(service.leagues(country.trim(), search.trim()));
    }

    @GetMapping("/leagues/{id}/fixtures")
    public Envelope leagueFixtures(@PathVariable String id,
                                   @RequestParam(defaultValue = "") String season) {
        return envelope(service.leagueFixtures(id, seasonOrCurrent(season)));
    }

    @GetMapping("/fixtures")
    public ResponseEntity<Object> fixtures(@RequestParam(defaultValue = "") String date,
                                           @RequestParam(defaultValue = "") String league,
                                           @RequestParam(defaultValue = "") String season) {
        String day = date.trim();
        if (day.isEmpty()) {
            day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        }
        if (!DATE_PATTERN.matcher(day).matches()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_DATE",
                    "Tham số `date` phải có dạng YYYY-MM-DD.");
        }
        try {
            LocalDate.parse(day);
        } catch (DateTimeParseException malformed) {
            return error(HttpStatus.BAD_REQUEST, "BAD_DATE", "Ngày không tồn tại: " + day);
        }

        Payload<List<Match>> payload =
                service.fixturesByDate(day, league.trim(), season.trim());
        return ResponseEntity.ok(envelope(payload));
    }

    @GetMapping("/teams")
    public ResponseEntity<Object> teams(@RequestParam(defaultValue = "") String search,
                                        @RequestParam(defaultValue = "") String league,
                                        @RequestParam(defaultValue = "") String season) {
        String name = search.trim();
        String leagueId = league.trim();
        if (name.isEmpty() && leagueId.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "MISSING_FILTER",
                    "Cần ít nhất một trong hai tham số: `search` hoặc `league`.");
        }

        Payload<List<Team>> payload = service.teams(name, leagueId, season.trim());
        return ResponseEntity.ok(envelope(payload));
    }

    @GetMapping("/teams/{id}/fixtures")
    public Envelope teamFixtures(@PathVariable String id,
                                 @RequestParam(defaultValue = "") String season,
                                 @RequestParam(defaultValue = "") String league) {
        return envelope(service.teamFixtures(id, seasonOrCurrent(season), league.trim()));
    }

    @GetMapping("/players")
    public ResponseEntity<Object> players(@RequestParam(defaultValue = "") String search) {
        String needle = search.trim();
        if (needle.codePointCount(0, needle.length()) < MIN_SEARCH_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "SEARCH_TOO_SHORT",
                    "Tham số `search` phải có ít nhất 3 ký tự.");
        }

        Payload<List<Player>> payload = service.players(needle);
        return ResponseEntity.ok(envelope(payload));
    }

    @GetMapping("/players/{id}")
    public ResponseEntity<Object> player(@PathVariable String id,
                                         @RequestParam(defaultValue = "") String season) {
        String year = seasonOrCurrent(season);
        Payload<Player> payload = service.player(id, year);
        if (payload.data() == null) {
            return error(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND",
                    "Không tìm thấy cầu thủ này ở mùa " + year + ".");
        }
        return ResponseEntity.ok(envelope(payload));
    }

    @PutMapping("/config/api-key")
    public ResponseEntity<Object> setApiKey(@RequestBody ApiKeyRequest request) {
        try {
            service.setApiKey(request == null ? "" : request.key());
        } catch (ProviderException rejected) {
            log.warn("khoá API bị từ chối: {}", rejected.getMessage());
            return error(HttpStatus.BAD_REQUEST, "KEY_REJECTED", rejected.getMessage());
        }
        return ResponseEntity.ok(Map.of("ok", true, "sampleOnly", false));
    }

    private String seasonOrCurrent(String season) {
        String value = season == null ? "" : season.trim();
        if (!value.isEmpty()) {
            return value;
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        int year = today.getYear();
        if (today.getMonthValue() < FIRST_MONTH_OF_NEW_SEASON) {
            year--;
        }
        return String.valueOf(year);
    }

    private Envelope envelope(Payload<?> payload) {
        return new Envelope(payload.data(),
                new Meta(payload.cachedAt(), payload.source().wire(), payload.stale()));
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
