package com.vnsearch.football.provider;

@FunctionalInterface
public interface CallRecorder {

    void recordCall(String endpoint, String params);

    CallRecorder NOOP = (endpoint, params) -> {
    };
}
