package com.vnsearch.football.model;

import java.time.Instant;

public record Match(
        String id,
        String competition,
        String competitionId,
        String competitionLogo,
        String round,
        MatchStatus status,
        Integer elapsed,
        Instant kickoff,
        Team homeTeam,
        Team awayTeam,
        Integer homeScore,
        Integer awayScore) {
}
