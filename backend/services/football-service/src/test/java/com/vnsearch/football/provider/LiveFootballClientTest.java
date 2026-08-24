package com.vnsearch.football.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnsearch.football.model.League;
import com.vnsearch.football.model.Match;
import com.vnsearch.football.model.MatchStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LiveFootballClientTest {

    private static final League PREMIER_LEAGUE =
            new League("47", "Premier League", "ENG", "icon.png", "", "Active");

    private final ObjectMapper mapper = new ObjectMapper();

    private final LiveFootballClient client = new LiveFootballClient(
            "https://" + LiveFootballClient.HOST, "khoa-thu", Duration.ofSeconds(5), null,
            new ObjectMapper());

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void idIsSometimesANumberAndSometimesAString() throws Exception {
        assertThat(LiveFootballClient.flexId(json("5225838"))).isEqualTo("5225838");
        assertThat(LiveFootballClient.flexId(json("\"4813374\""))).isEqualTo("4813374");
        assertThat(LiveFootballClient.flexId(json("null"))).isEmpty();
    }

    @Test
    void notYetPlayedMatchHasNoScoreRatherThanZeroZero() throws Exception {
        Match match = client.toMatch(json("""
                {
                  "id": 1, "leagueId": 47, "tournamentStage": "3",
                  "home": {"id": 8456, "name": "Man City", "longName": "Manchester City",
                           "score": 0},
                  "away": {"id": 8650, "name": "Liverpool", "longName": "Liverpool FC",
                           "score": 0},
                  "status": {"utcTime": "2026-08-30T14:00:00Z", "started": false,
                             "finished": false, "cancelled": false}
                }
                """), PREMIER_LEAGUE, Instant.parse("2026-08-24T10:00:00Z"));

        assertThat(match.status()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(match.homeScore()).isNull();
        assertThat(match.awayScore()).isNull();
        assertThat(match.round()).isEqualTo("Vòng 3");
        assertThat(match.homeTeam().name()).isEqualTo("Manchester City");
        assertThat(match.homeTeam().shortName()).isEqualTo("Man City");
    }

    @Test
    void matchClockIsCappedAtNinetyMinutes() throws Exception {
        String raw = """
                {
                  "id": 2, "leagueId": 47,
                  "home": {"id": 1, "name": "A", "score": 1},
                  "away": {"id": 2, "name": "B", "score": 0},
                  "status": {"utcTime": "2026-08-24T10:00:00Z", "started": true,
                             "finished": false, "cancelled": false}
                }
                """;

        Match afterOneHour = client.toMatch(json(raw), PREMIER_LEAGUE,
                Instant.parse("2026-08-24T11:00:00Z"));
        Match afterThreeHours = client.toMatch(json(raw), PREMIER_LEAGUE,
                Instant.parse("2026-08-24T13:00:00Z"));

        assertThat(afterOneHour.status()).isEqualTo(MatchStatus.LIVE);
        assertThat(afterOneHour.elapsed()).isEqualTo(60);
        assertThat(afterThreeHours.elapsed()).isEqualTo(90);
    }

    @Test
    void finishedMatchKeepsTheFinalScore() throws Exception {
        Match match = client.toMatch(json("""
                {
                  "id": 3, "leagueId": 47,
                  "home": {"id": 1, "name": "A", "score": 3},
                  "away": {"id": 2, "name": "B", "score": 2},
                  "status": {"utcTime": "2026-08-20T10:00:00Z", "started": true,
                             "finished": true, "cancelled": false}
                }
                """), PREMIER_LEAGUE, Instant.parse("2026-08-24T10:00:00Z"));

        assertThat(match.status()).isEqualTo(MatchStatus.FINISHED);
        assertThat(match.homeScore()).isEqualTo(3);
        assertThat(match.elapsed()).isNull();
    }

    @Test
    void badgeImageIsDerivedFromTheTeamId() {
        assertThat(LiveFootballClient.teamLogo("8456")).endsWith("teamlogo/8456.png");
        assertThat(LiveFootballClient.teamLogo("")).isEmpty();
        assertThat(LiveFootballClient.leagueLogo("47")).endsWith("leaguelogo/47.png");
    }
}
