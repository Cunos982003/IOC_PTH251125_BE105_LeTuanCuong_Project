package com.ridehailing.wsgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorResponse extends WsMessage {
    private String code;
    private String message;

    public ErrorResponse() {
        super("error");
    }

    public ErrorResponse(String code, String message) {
        super("error");
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
