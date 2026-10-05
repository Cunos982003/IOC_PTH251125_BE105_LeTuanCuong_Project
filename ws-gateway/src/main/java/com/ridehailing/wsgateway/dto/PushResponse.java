package com.ridehailing.wsgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PushResponse(
        @JsonProperty("delivered") boolean delivered
) {
}
