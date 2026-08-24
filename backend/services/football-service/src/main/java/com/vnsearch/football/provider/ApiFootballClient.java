package com.vnsearch.football.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnsearch.football.model.FixtureQuery;
import com.vnsearch.football.model.League;
import com.vnsearch.football.model.Match;
import com.vnsearch.football.model.MatchStatus;
import com.vnsearch.football.model.Player;
import com.vnsearch.football.model.PlayerStat;
import com.vnsearch.football.model.Team;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ApiFootballClient implements FootballProvider {

    private static final Set<String> FINISHED = Set.of("FT", "AET", "PEN", "WO", "AWD");
    private static final Set<String> LIVE =
            Set.of("1H", "HT", "2H", "ET", "BT", "P", "SUSP", "INT", "LIVE");

    private final String apiKey;
    private final String rapidHost;
    private final RestClient http;
    private final CallRecorder recorder;
    private final ObjectMapper mapper;

    public ApiFootballClient(String baseUrl, String apiKey, Duration timeout,
                             CallRecorder recorder, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.rapidHost = rapidApiHost(baseUrl);
        this.http = HttpSupport.client(baseUrl, timeout);
        this.recorder = recorder == null ? CallRecorder.NOOP : recorder;
        this.mapper = mapper;
    }

    static String rapidApiHost(String baseUrl) {
        try {
            String host = URI.create(HttpSupport.trimTrailingSlash(baseUrl)).getHost();
            return host != null && host.endsWith(".rapidapi.com") ? host : "";
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    @Override
    public String name() {
        return "API-Football";
    }

    @Override
    public List<League> leagues(String country, String search) {
        Map<String, String> params = HttpSupport.params();
        HttpSupport.setIfPresent(params, "country", country);
        HttpSupport.setIfPresent(params, "search", search);

        List<League> leagues = new ArrayList<>();
        for (JsonNode node : get("/leagues", params)) {
            leagues.add(toLeague(node));
        }
        return leagues;
    }

    @Override
    public List<Match> fixtures(FixtureQuery query) {
        Map<String, String> params = HttpSupport.params();
        HttpSupport.setIfPresent(params, "date", query.date());
        HttpSupport.setIfPresent(params, "league", query.league());
        HttpSupport.setIfPresent(params, "season", query.season());
        HttpSupport.setIfPresent(params, "team", query.team());
        if (query.live()) {
            params.put("live", "all");
        }

        List<Match> matches = new ArrayList<>();
        for (JsonNode node : get("/fixtures", params)) {
            matches.add(toMatch(node));
        }
        return matches;
    }

    @Override
    public List<Team> teams(String search, String league, String season) {
        Map<String, String> params = HttpSupport.params();
        HttpSupport.setIfPresent(params, "search", search);
        HttpSupport.setIfPresent(params, "league", league);
        HttpSupport.setIfPresent(params, "season", season);

        List<Team> teams = new ArrayList<>();
        for (JsonNode node : get("/teams", params)) {
            teams.add(toTeam(node.path("team")));
        }
        return teams;
    }

    @Override
    public List<Player> players(String search) {
        Map<String, String> params = HttpSupport.params();
        HttpSupport.setIfPresent(params, "search", search);

        List<Player> players = new ArrayList<>();
        for (JsonNode node : get("/players/profiles", params)) {
            players.add(toPlayer(node));
        }
        return players;
    }

    @Override
    public Optional<Player> player(String playerId, String season) {
        Map<String, String> params = HttpSupport.params();
        HttpSupport.setIfPresent(params, "id", playerId);
        HttpSupport.setIfPresent(params, "season", season);

        Iterator<JsonNode> nodes = get("/players", params).iterator();
        return nodes.hasNext() ? Optional.of(toPlayer(nodes.next())) : Optional.empty();
    }

    private JsonNode get(String path, Map<String, String> params) {
        if (apiKey.isEmpty()) {
            throw new ProviderException("chưa cấu hình FOOTBALL_API_KEY");
        }

        String body;
        try {
            body = http.get()
                    .uri(HttpSupport.path(path, params))
                    .header("accept", "application/json")
                    .headers(headers -> {
                        if (rapidHost.isEmpty()) {
                            headers.set("x-apisports-key", apiKey);
                        } else {
                            headers.set("x-rapidapi-key", apiKey);
                            headers.set("x-rapidapi-host", rapidHost);
                        }
                    })
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException failure) {
            recorder.recordCall(path, HttpSupport.encode(params));
            throw new ProviderException("gọi " + path + " hỏng: " + failure.getMessage(), failure);
        }

        recorder.recordCall(path, HttpSupport.encode(params));

        JsonNode envelope = parse(path, body);
        String message = errorMessage(envelope.path("errors"));
        if (!message.isEmpty()) {
            throw new ProviderException("API-Football báo lỗi ở " + path + ": " + message);
        }
        return envelope.path("response");
    }

    private JsonNode parse(String path, String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException broken) {
            throw new ProviderException("giải mã phản hồi " + path + " hỏng", broken);
        }
    }

    static String errorMessage(JsonNode errors) {
        if (errors == null || errors.isMissingNode() || errors.isNull() || errors.isEmpty()) {
            return "";
        }
        if (errors.isObject()) {
            StringBuilder out = new StringBuilder();
            errors.fields().forEachRemaining(entry -> {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(entry.getKey()).append(": ").append(entry.getValue().asText());
            });
            return out.toString();
        }
        return errors.toString();
    }

    static League toLeague(JsonNode node) {
        JsonNode league = node.path("league");
        JsonNode country = node.path("country");

        String status = "Offseason";
        for (JsonNode season : node.path("seasons")) {
            if (season.path("current").asBoolean(false)) {
                status = "Active";
                break;
            }
        }

        return new League(
                league.path("id").asText(""),
                league.path("name").asText(""),
                country.path("name").asText(""),
                league.path("logo").asText(""),
                country.path("flag").asText(""),
                status);
    }

    static Match toMatch(JsonNode node) {
        JsonNode fixture = node.path("fixture");
        JsonNode league = node.path("league");
        JsonNode goals = node.path("goals");

        MatchStatus status = toStatus(fixture.path("status").path("short").asText(""));
        Integer elapsed = status == MatchStatus.LIVE
                ? intOrNull(fixture.path("status").path("elapsed"))
                : null;

        return new Match(
                fixture.path("id").asText(""),
                league.path("name").asText(""),
                league.path("id").asText(""),
                league.path("logo").asText(""),
                league.path("round").asText(""),
                status,
                elapsed,
                instantOrNull(fixture.path("date").asText("")),
                toFixtureTeam(node.path("teams").path("home")),
                toFixtureTeam(node.path("teams").path("away")),
                intOrNull(goals.path("home")),
                intOrNull(goals.path("away")));
    }

    static MatchStatus toStatus(String shortCode) {
        String code = shortCode.toUpperCase(Locale.ROOT);
        if (FINISHED.contains(code)) {
            return MatchStatus.FINISHED;
        }
        if (LIVE.contains(code)) {
            return MatchStatus.LIVE;
        }
        return MatchStatus.SCHEDULED;
    }

    private static Team toFixtureTeam(JsonNode node) {
        String name = node.path("name").asText("");
        return Team.of(node.path("id").asText(""), name, name, node.path("logo").asText(""));
    }

    static Team toTeam(JsonNode team) {
        String name = team.path("name").asText("");
        String code = team.path("code").asText("");
        return new Team(
                team.path("id").asText(""),
                name,
                code.isBlank() ? name : code,
                team.path("logo").asText(""),
                team.path("country").asText(""),
                intOrNull(team.path("founded")),
                null);
    }

    static Player toPlayer(JsonNode node) {
        JsonNode player = node.path("player");

        List<PlayerStat> stats = new ArrayList<>();
        for (JsonNode entry : node.path("statistics")) {
            JsonNode team = entry.path("team");
            JsonNode league = entry.path("league");
            JsonNode games = entry.path("games");
            JsonNode goals = entry.path("goals");
            JsonNode cards = entry.path("cards");

            stats.add(new PlayerStat(
                    team.path("id").asText(""),
                    team.path("name").asText(""),
                    team.path("logo").asText(""),
                    league.path("id").asText(""),
                    league.path("name").asText(""),
                    league.path("country").asText(""),
                    intOrNull(league.path("season")),
                    games.path("position").asText(""),
                    games.path("appearences").asInt(0),
                    games.path("minutes").asInt(0),
                    parseRating(games.path("rating").asText("")),
                    goals.path("total").asInt(0),
                    goals.path("assists").asInt(0),
                    cards.path("yellow").asInt(0),
                    cards.path("red").asInt(0)));
        }

        return new Player(
                player.path("id").asText(""),
                player.path("name").asText(""),
                player.path("firstname").asText(""),
                player.path("lastname").asText(""),
                intOrNull(player.path("age")),
                player.path("nationality").asText(""),
                player.path("height").asText(""),
                player.path("weight").asText(""),
                player.path("photo").asText(""),
                player.path("injured").asBoolean(false),
                List.copyOf(stats));
    }

    static Double parseRating(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    static Integer intOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    static Instant instantOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }
}
