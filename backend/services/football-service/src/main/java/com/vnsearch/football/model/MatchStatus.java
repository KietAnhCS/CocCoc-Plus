package com.vnsearch.football.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MatchStatus {
    SCHEDULED("scheduled"),
    LIVE("live"),
    FINISHED("finished");

    private final String wire;

    MatchStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static MatchStatus fromWire(String value) {
        for (MatchStatus status : values()) {
            if (status.wire.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return SCHEDULED;
    }
}
