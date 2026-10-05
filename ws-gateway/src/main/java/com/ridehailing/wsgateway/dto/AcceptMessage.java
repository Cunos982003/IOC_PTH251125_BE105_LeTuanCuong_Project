package com.ridehailing.wsgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AcceptMessage extends WsMessage {
    private String tripId;

    public AcceptMessage() {
        super("accept");
    }

    public String getTripId() {
        return tripId;
    }

    public void setTripId(String tripId) {
        this.tripId = tripId;
    }
}
