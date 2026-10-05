package com.ridehailing.userservice.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VehicleRequest(
        @NotBlank @Size(max = 50) String plate,
        @NotBlank @Size(max = 50) String type,
        @Size(max = 100) String model
) {
}