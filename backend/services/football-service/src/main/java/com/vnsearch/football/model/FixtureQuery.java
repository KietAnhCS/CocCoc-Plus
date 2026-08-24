package com.vnsearch.football.model;

public record FixtureQuery(String date, String league, String season, String team, boolean live) {

    public static FixtureQuery byDate(String date, String league, String season) {
        return new FixtureQuery(date, league, season, "", false);
    }

    public static FixtureQuery byTeam(String team, String season, String league) {
        return new FixtureQuery("", league, season, team, false);
    }

    public static FixtureQuery byLeague(String league, String season) {
        return new FixtureQuery("", league, season, "", false);
    }
}
