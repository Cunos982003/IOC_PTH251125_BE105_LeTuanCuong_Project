package com.ridehailing.wsgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record PushRequest(
        @JsonProperty("userId") String userId,
        @JsonProperty("type") String type,
        @JsonProperty("payload") Map<String, Object> payload
) {
}
