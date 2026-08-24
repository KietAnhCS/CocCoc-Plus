package com.vnsearch.football.model;

import java.util.List;

public record Player(
        String id,
        String name,
        String firstName,
        String lastName,
        Integer age,
        String nationality,
        String height,
        String weight,
        String photo,
        boolean injured,
        List<PlayerStat> statistics) {
}
