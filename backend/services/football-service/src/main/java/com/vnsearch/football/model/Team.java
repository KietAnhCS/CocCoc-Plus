package com.vnsearch.football.model;

import com.fasterxml.jackson.annotation.JsonInclude;

public record Team(
        String id,
        String name,
        String shortName,
        String emblem,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String country,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer founded,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String leagueId) {

    public static Team of(String id, String name, String shortName, String emblem) {
        return new Team(id, name, shortName, emblem, null, null, null);
    }

    public Team withLeague(String country, String leagueId) {
        return new Team(id, name, shortName, emblem, country, founded, leagueId);
    }
}
