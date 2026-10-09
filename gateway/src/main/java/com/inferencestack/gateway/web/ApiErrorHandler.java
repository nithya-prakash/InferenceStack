package com.inferencestack.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    private final ErrorWriter errors;

    public ApiErrorHandler(ErrorWriter errors) { this.errors = errors; }

    @ExceptionHandler(ApiException.class)
    void handleApi(ApiException e, HttpServletRequest req, HttpServletResponse res) throws IOException {
        errors.write(req, res, e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    void handleUnexpected(Exception e, HttpServletRequest req, HttpServletResponse res) throws IOException {
        log.error("unexpected error", e);  // details stay in the server log, not in the response
        errors.write(req, res, 500, "internal_error", "internal error");
    }
}
