package com.vnsearch.football.model;

public record PlayerStat(
        String teamId,
        String teamName,
        String teamLogo,
        String leagueId,
        String leagueName,
        String leagueCountry,
        Integer season,
        String position,
        int appearances,
        int minutesPlayed,
        Double rating,
        int goals,
        int assists,
        int yellowCards,
        int redCards) {
}
