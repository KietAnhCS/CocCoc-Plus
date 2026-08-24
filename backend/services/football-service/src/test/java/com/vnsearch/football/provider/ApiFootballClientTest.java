package com.vnsearch.football.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnsearch.football.model.Match;
import com.vnsearch.football.model.MatchStatus;
import com.vnsearch.football.model.Player;
import com.vnsearch.football.model.Team;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiFootballClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void mapsFixtureCodesToThreeStatuses() {
        assertThat(ApiFootballClient.toStatus("FT")).isEqualTo(MatchStatus.FINISHED);
        assertThat(ApiFootballClient.toStatus("1H")).isEqualTo(MatchStatus.LIVE);
        assertThat(ApiFootballClient.toStatus("NS")).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(ApiFootballClient.toStatus("")).isEqualTo(MatchStatus.SCHEDULED);
    }

    @Test
    void finishedMatchCarriesNoElapsedMinutes() throws Exception {
        Match match = ApiFootballClient.toMatch(json("""
                {
                  "fixture": {"id": 1, "date": "2026-08-24T19:00:00+00:00",
                              "status": {"short": "FT", "elapsed": 90}},
                  "league": {"id": 39, "name": "Premier League", "round": "Regular Season - 1"},
                  "teams": {"home": {"id": 33, "name": "Manchester United"},
                            "away": {"id": 40, "name": "Liverpool"}},
                  "goals": {"home": 2, "away": 1}
                }
                """));

        assertThat(match.status()).isEqualTo(MatchStatus.FINISHED);
        assertThat(match.elapsed()).isNull();
        assertThat(match.homeScore()).isEqualTo(2);
        assertThat(match.kickoff()).isNotNull();
    }

    @Test
    void liveMatchKeepsTheElapsedMinutes() throws Exception {
        Match match = ApiFootballClient.toMatch(json("""
                {
                  "fixture": {"id": 2, "date": "", "status": {"short": "2H", "elapsed": 67}},
                  "league": {"id": 39, "name": "Premier League"},
                  "teams": {"home": {"id": 33, "name": "A"}, "away": {"id": 40, "name": "B"}},
                  "goals": {"home": null, "away": null}
                }
                """));

        assertThat(match.status()).isEqualTo(MatchStatus.LIVE);
        assertThat(match.elapsed()).isEqualTo(67);
        assertThat(match.kickoff()).isNull();
        assertThat(match.homeScore()).isNull();
    }

    @Test
    void teamWithoutACodeUsesItsFullName() throws Exception {
        Team withCode = ApiFootballClient.toTeam(json(
                "{\"id\": 33, \"name\": \"Manchester United\", \"code\": \"MUN\"}"));
        Team withoutCode = ApiFootballClient.toTeam(json(
                "{\"id\": 34, \"name\": \"Newcastle\", \"code\": \"\"}"));

        assertThat(withCode.shortName()).isEqualTo("MUN");
        assertThat(withoutCode.shortName()).isEqualTo("Newcastle");
    }

    @Test
    void blankRatingDiffersFromZeroRating() {
        assertThat(ApiFootballClient.parseRating("")).isNull();
        assertThat(ApiFootballClient.parseRating("khong-phai-so")).isNull();
        assertThat(ApiFootballClient.parseRating("7.283333")).isEqualTo(7.283333);
    }

    @Test
    void errorsLiveInTheResponseBodyNotTheStatusCode() throws Exception {
        assertThat(ApiFootballClient.errorMessage(json("[]"))).isEmpty();
        assertThat(ApiFootballClient.errorMessage(json("{}"))).isEmpty();
        assertThat(ApiFootballClient.errorMessage(json(
                "{\"token\": \"khoa khong hop le\"}")))
                .contains("token")
                .contains("khoa khong hop le");
    }

    @Test
    void detectsTheRapidApiHostFromTheBaseUrl() {
        assertThat(ApiFootballClient.rapidApiHost("https://api-football-v1.p.rapidapi.com"))
                .isEqualTo("api-football-v1.p.rapidapi.com");
        assertThat(ApiFootballClient.rapidApiHost("https://v3.football.api-sports.io"))
                .isEmpty();
    }

    @Test
    void playerStatsReadEvenTheMisspelledField() throws Exception {
        Player player = ApiFootballClient.toPlayer(json("""
                {
                  "player": {"id": 276, "name": "Neymar", "firstname": "Neymar",
                             "lastname": "da Silva", "age": 33, "nationality": "Brazil"},
                  "statistics": [{
                    "team": {"id": 85, "name": "PSG"},
                    "league": {"id": 61, "name": "Ligue 1", "country": "France", "season": 2023},
                    "games": {"appearences": 20, "minutes": 1600, "position": "Attacker",
                              "rating": "7.5"},
                    "goals": {"total": 13, "assists": 11},
                    "cards": {"yellow": 3, "red": 0}
                  }]
                }
                """));

        assertThat(player.statistics()).hasSize(1);
        assertThat(player.statistics().get(0).appearances()).isEqualTo(20);
        assertThat(player.statistics().get(0).rating()).isEqualTo(7.5);
        assertThat(player.age()).isEqualTo(33);
    }
}
