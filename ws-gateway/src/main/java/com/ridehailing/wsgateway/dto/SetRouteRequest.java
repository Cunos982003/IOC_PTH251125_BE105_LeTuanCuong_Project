package com.ridehailing.wsgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SetRouteRequest(
        @JsonProperty("customerId") String customerId
) {
}
