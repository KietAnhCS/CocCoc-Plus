package com.vnsearch.football.provider;

import com.vnsearch.football.model.FixtureQuery;
import com.vnsearch.football.model.League;
import com.vnsearch.football.model.Match;
import com.vnsearch.football.model.Player;
import com.vnsearch.football.model.Team;

import java.util.List;
import java.util.Optional;

public interface FootballProvider {

    String name();

    List<League> leagues(String country, String search);

    List<Match> fixtures(FixtureQuery query);

    List<Team> teams(String search, String league, String season);

    List<Player> players(String search);

    Optional<Player> player(String playerId, String season);
}
