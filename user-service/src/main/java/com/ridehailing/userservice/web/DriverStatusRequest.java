package com.ridehailing.userservice.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriverStatusRequest(
        @NotBlank @Pattern(regexp = "ONLINE|OFFLINE") String status
) {
}