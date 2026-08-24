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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class LiveFootballClient implements FootballProvider {

    public static final String HOST = "free-api-live-football-data.p.rapidapi.com";

    private static final String TEAM_LOGO_BASE =
            "https://images.fotmob.com/image_resources/logo/teamlogo/";
    private static final String LEAGUE_LOGO_BASE =
            "https://images.fotmob.com/image_resources/logo/leaguelogo/";
    private static final String PLAYER_PHOTO_BASE =
            "https://images.fotmob.com/image_resources/playerimages/";

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int FULL_MATCH_MINUTES = 90;

    private final String apiKey;
    private final RestClient http;
    private final CallRecorder recorder;
    private final ObjectMapper mapper;

    private final AtomicReference<LeagueTable> leagueTable = new AtomicReference<>();

    public LiveFootballClient(String baseUrl, String apiKey, Duration timeout,
                              CallRecorder recorder, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = HttpSupport.client(baseUrl, timeout);
        this.recorder = recorder == null ? CallRecorder.NOOP : recorder;
        this.mapper = mapper;
    }

    private record LeagueTable(Map<String, League> byId, Set<String> popular) {
    }

    @Override
    public String name() {
        return "free-api-live-football-data (RapidAPI)";
    }

    @Override
    public List<League> leagues(String country, String search) {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String code = country == null ? "" : country.trim();

        List<League> matches = new ArrayList<>();
        for (League league : allLeagues()) {
            if (!code.isBlank() && !code.equalsIgnoreCase(league.country())) {
                continue;
            }
            if (!needle.isBlank()
                    && !league.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches.add(league);
        }
        return matches;
    }

    @Override
    public List<Match> fixtures(FixtureQuery query) {
        Instant now = Instant.now();

        List<JsonNode> envelopes;
        if (!query.team().isBlank() || !query.league().isBlank()) {
            envelopes = leagueMatches(query.league());
        } else {
            envelopes = dateMatches(query.date());
        }

        List<Match> matches = new ArrayList<>();
        for (JsonNode envelope : envelopes) {
            String homeId = flexId(envelope.path("home").path("id"));
            String awayId = flexId(envelope.path("away").path("id"));
            if (!query.team().isBlank()
                    && !query.team().equals(homeId) && !query.team().equals(awayId)) {
                continue;
            }

            String leagueId = flexId(envelope.path("leagueId"));
            if (!query.league().isBlank() && !leagueId.isBlank()
                    && !leagueId.equals(query.league())) {
                continue;
            }
            if (leagueId.isBlank()) {
                leagueId = query.league();
            }

            matches.add(toMatch(envelope, lookupLeague(leagueId), now));
        }

        matches.sort(Comparator.comparing(Match::kickoff,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return matches;
    }

    @Override
    public List<Team> teams(String search, String league, String season) {
        if (search == null || search.isBlank()) {
            return teamsOfLeague(league);
        }

        Map<String, String> params = HttpSupport.params();
        params.put("search", search.trim());

        List<Team> teams = new ArrayList<>();
        for (JsonNode item : get("/football-teams-search", params).path("suggestions")) {
            String type = item.path("type").asText("");
            if (!type.isBlank() && !"team".equals(type)) {
                continue;
            }
            String id = flexId(item.path("id"));
            String name = item.path("name").asText("");
            teams.add(new Team(id, name, name, teamLogo(id),
                    item.path("leagueName").asText(""), null, flexId(item.path("leagueId"))));
        }
        return teams;
    }

    @Override
    public List<Player> players(String search) {
        Map<String, String> params = HttpSupport.params();
        params.put("search", search == null ? "" : search.trim());

        List<Player> players = new ArrayList<>();
        for (JsonNode item : get("/football-players-search", params).path("suggestions")) {
            String type = item.path("type").asText("");
            if (item.path("isCoach").asBoolean(false)
                    || (!type.isBlank() && !"player".equals(type))) {
                continue;
            }

            String id = flexId(item.path("id"));
            String teamId = flexId(item.path("teamId"));
            PlayerStat stat = new PlayerStat(teamId, item.path("teamName").asText(""),
                    teamLogo(teamId), "", "", "", null, "", 0, 0, null, 0, 0, 0, 0);

            players.add(new Player(id, item.path("name").asText(""), "", "", null, "", "", "",
                    PLAYER_PHOTO_BASE + id + ".png", false, List.of(stat)));
        }
        return players;
    }

    @Override
    public Optional<Player> player(String playerId, String season) {
        Map<String, String> params = HttpSupport.params();
        params.put("playerid", playerId);

        JsonNode detail = get("/football-get-player-detail", params).path("detail");

        String name = "";
        String height = "";
        String nationality = "";
        Integer age = null;

        for (JsonNode entry : detail) {
            String key = entry.path("translationKey").asText("");
            switch (key) {
                case "height_sentencecase" -> height = detailText(entry);
                case "country_sentencecase" -> nationality = detailText(entry);
                case "name" -> name = detailText(entry);
                case "age_sentencecase" -> {
                    JsonNode number = entry.path("value").path("numberValue");
                    if (!number.isMissingNode() && !number.isNull()) {
                        age = number.asInt();
                    }
                }
                default -> {
                }
            }
        }

        return Optional.of(new Player(playerId, name, "", "", age, nationality, height, "",
                PLAYER_PHOTO_BASE + playerId + ".png", false, List.of()));
    }

    private String detailText(JsonNode entry) {
        JsonNode fallback = entry.path("value").path("fallback");
        if (fallback.isMissingNode() || fallback.isNull()) {
            return "";
        }
        return fallback.isTextual() ? fallback.asText() : fallback.asText("");
    }

    private List<Team> teamsOfLeague(String leagueId) {
        League league = lookupLeague(leagueId);
        Set<String> seen = new HashSet<>();
        List<Team> teams = new ArrayList<>();

        for (JsonNode envelope : leagueMatches(leagueId)) {
            for (String sideName : List.of("home", "away")) {
                Team team = toTeam(envelope.path(sideName));
                if (team.id().isBlank() || !seen.add(team.id())) {
                    continue;
                }
                teams.add(team.withLeague(league.name(), league.id()));
            }
        }

        teams.sort(Comparator.comparing(Team::name));
        return teams;
    }

    private List<JsonNode> dateMatches(String date) {
        String compact = date == null ? "" : date.replace("-", "").trim();
        if (compact.isBlank()) {
            compact = OffsetDateTime.now(ZoneOffset.UTC).format(COMPACT_DATE);
        }

        Map<String, String> params = HttpSupport.params();
        params.put("date", compact);
        return list(get("/football-get-matches-by-date", params).path("matches"));
    }

    private List<JsonNode> leagueMatches(String leagueId) {
        if (leagueId == null || leagueId.isBlank()) {
            return List.of();
        }

        Map<String, String> params = HttpSupport.params();
        params.put("leagueid", leagueId);
        return list(get("/football-get-all-matches-by-league", params).path("matches"));
    }

    private List<League> allLeagues() {
        LeagueTable table = leagueTable.get();
        if (table == null) {
            table = loadLeagues();
            leagueTable.set(table);
        }

        List<League> leagues = new ArrayList<>(table.byId().values());
        Set<String> popular = table.popular();
        leagues.sort(Comparator
                .comparing((League league) -> popular.contains(league.id()) ? 0 : 1)
                .thenComparing(League::name));
        return leagues;
    }

    private LeagueTable loadLeagues() {
        Map<String, League> byId = new LinkedHashMap<>();
        Set<String> popular = new HashSet<>();

        JsonNode popularBody = get("/football-popular-leagues", HttpSupport.params());
        collectLeagues(popularBody.path("popular"), byId, popular, true);

        try {
            JsonNode allBody = get("/football-get-all-leagues", HttpSupport.params());
            collectLeagues(allBody.path("leagues"), byId, popular, false);
        } catch (ProviderException partial) {
            // Danh sách giải phổ biến đã đủ cho gần như mọi màn hình.
        }

        return new LeagueTable(byId, popular);
    }

    private void collectLeagues(JsonNode items, Map<String, League> byId,
                                Set<String> popular, boolean isPopular) {
        for (JsonNode item : items) {
            String id = flexId(item.path("id"));
            if (id.isBlank()) {
                continue;
            }

            String name = item.path("localizedName").asText("");
            if (name.isBlank()) {
                name = item.path("name").asText("");
            }
            String icon = item.path("logo").asText("");
            if (icon.isBlank()) {
                icon = leagueLogo(id);
            }

            byId.put(id, new League(id, name, item.path("ccode").asText(""), icon, "", "Active"));
            if (isPopular) {
                popular.add(id);
            }
        }
    }

    private League lookupLeague(String id) {
        String leagueId = id == null ? "" : id;
        try {
            allLeagues();
        } catch (ProviderException unavailable) {
            return new League(leagueId, "", "", leagueLogo(leagueId), "", "");
        }

        LeagueTable table = leagueTable.get();
        League found = table == null ? null : table.byId().get(leagueId);
        return found != null ? found
                : new League(leagueId, "", "", leagueLogo(leagueId), "", "");
    }

    Match toMatch(JsonNode envelope, League league, Instant now) {
        JsonNode status = envelope.path("status");
        Instant kickoff = instantOrNull(status.path("utcTime").asText(""));

        String round = envelope.path("tournamentStage").asText("");
        if (round.isBlank()) {
            round = envelope.path("tournament").path("stage").asText("");
        }
        if (!round.isBlank() && round.chars().allMatch(Character::isDigit)) {
            round = "Vòng " + round;
        }

        Integer homeScore = intOrNull(envelope.path("home").path("score"));
        Integer awayScore = intOrNull(envelope.path("away").path("score"));
        Integer elapsed = null;
        MatchStatus matchStatus;

        if (status.path("finished").asBoolean(false)) {
            matchStatus = MatchStatus.FINISHED;
        } else if (status.path("started").asBoolean(false)
                && !status.path("cancelled").asBoolean(false)) {
            matchStatus = MatchStatus.LIVE;
            if (kickoff != null) {
                long minutes = Duration.between(kickoff, now).toMinutes();
                elapsed = (int) Math.max(0, Math.min(FULL_MATCH_MINUTES, minutes));
            }
        } else {
            matchStatus = MatchStatus.SCHEDULED;
            homeScore = null;
            awayScore = null;
        }

        return new Match(
                flexId(envelope.path("id")),
                league.name(),
                league.id(),
                league.icon(),
                round,
                matchStatus,
                elapsed,
                kickoff,
                toTeam(envelope.path("home")),
                toTeam(envelope.path("away")),
                homeScore,
                awayScore);
    }

    private static Team toTeam(JsonNode side) {
        String id = flexId(side.path("id"));
        String name = side.path("longName").asText("");
        if (name.isBlank()) {
            name = side.path("name").asText("");
        }
        String shortName = side.path("name").asText("");
        if (shortName.isBlank()) {
            shortName = name;
        }
        return Team.of(id, name, shortName, teamLogo(id));
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
                    .header("x-rapidapi-key", apiKey)
                    .header("x-rapidapi-host", HOST)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException failure) {
            recorder.recordCall(path, HttpSupport.encode(params));
            throw new ProviderException("gọi " + path + " hỏng: " + failure.getMessage(), failure);
        }

        recorder.recordCall(path, HttpSupport.encode(params));

        JsonNode envelope;
        try {
            envelope = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException broken) {
            throw new ProviderException("đọc phản hồi " + path + " hỏng", broken);
        }

        String status = envelope.path("status").asText("");
        if (!status.isBlank() && !"success".equals(status)) {
            throw new ProviderException(path + " báo lỗi: " + envelope.path("message").asText(""));
        }
        return envelope.path("response");
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    static String flexId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.asText("");
    }

    static String teamLogo(String id) {
        return id == null || id.isBlank() ? "" : TEAM_LOGO_BASE + id + ".png";
    }

    static String leagueLogo(String id) {
        return id == null || id.isBlank() ? "" : LEAGUE_LOGO_BASE + id + ".png";
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
