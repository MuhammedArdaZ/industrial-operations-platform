package com.industrialoperations.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.industrialoperations.platform.alert.AlertNotFoundException;
import com.industrialoperations.platform.alert.InvalidAlertTransitionException;

/**
 * Translates domain exceptions thrown from any controller into HTTP responses.
 * Domain classes stay HTTP-agnostic; the status-code mapping lives only here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AlertNotFoundException.class)
    public ProblemDetail handleAlertNotFound(AlertNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidAlertTransitionException.class)
    public ProblemDetail handleInvalidAlertTransition(InvalidAlertTransitionException ex) {
        log.warn("Invalid alert transition: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setProperty("currentStatus", ex.getCurrentStatus());

        return problemDetail;
    }
}
