package com.inferencestack.gateway.web;

/** A failure with a stable machine-readable `code` (also stored as telemetry error_category) and an HTTP status. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }

    public String code() { return code; }
}
