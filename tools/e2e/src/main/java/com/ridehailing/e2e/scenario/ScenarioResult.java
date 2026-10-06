package com.ridehailing.e2e.scenario;

public record ScenarioResult(
    String name,
    boolean passed,
    long durationMs,
    String message
) {}
