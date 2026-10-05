package com.ridehailing.dispatchservice.domain;

import java.util.Set;

public enum TripStatus {
    CREATED,
    MATCHING,
    ACCEPTED,
    PICKING_UP,
    IN_TRIP,
    COMPLETED,
    CANCELLED,
    NO_DRIVER_FOUND;

    private static final Set<TripStatus> TERMINAL_STATES = Set.of(COMPLETED, CANCELLED, NO_DRIVER_FOUND);

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    public boolean canTransitionTo(TripStatus next) {
        return switch (this) {
            case CREATED -> next == MATCHING || next == CANCELLED;
            case MATCHING -> next == ACCEPTED || next == NO_DRIVER_FOUND || next == CANCELLED;
            case ACCEPTED -> next == PICKING_UP || next == CANCELLED;
            case PICKING_UP -> next == IN_TRIP || next == CANCELLED;
            case IN_TRIP -> next == COMPLETED;
            case COMPLETED, CANCELLED, NO_DRIVER_FOUND -> false;
        };
    }
}
