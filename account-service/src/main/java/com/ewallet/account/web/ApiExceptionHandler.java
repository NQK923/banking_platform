package com.ewallet.account.web;

import com.ewallet.common.DomainException;
import com.ewallet.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorResponse> domain(DomainException ex, HttpServletRequest request) {
        return ResponseEntity.status(status(ex.code()))
            .body(new ErrorResponse(ex.code(), ex.getMessage(), traceId(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("FORBIDDEN", "Access denied", traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL", ex.getMessage(), traceId(request)));
    }

    private HttpStatus status(String code) {
        return switch (code) {
            case "AUTH_INVALID", "AUTH_EXPIRED" -> HttpStatus.UNAUTHORIZED;
            case "PIN_INVALID", "PIN_LOCKED", "OTP_REQUIRED", "OTP_INVALID", "OTP_EXPIRED", "OTP_LOCKED", "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "RECIPIENT_NOT_FOUND", "ACCOUNT_NOT_FOUND", "TRANSACTION_NOT_FOUND", "RISK_EVALUATION_NOT_FOUND",
                "SUPPORT_SESSION_NOT_FOUND", "SUPPORT_CASE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RECIPIENT_SUSPENDED", "INSUFFICIENT_FUNDS", "CANCEL_NOT_ALLOWED", "RISK_REVIEW_NOT_REQUIRED", "RISK_REJECT_NOT_ALLOWED" -> HttpStatus.CONFLICT;
            case "CURRENCY_MISMATCH", "INVALID_AMOUNT", "SELF_TRANSFER", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_PAYLOAD_MISMATCH",
                "RISK_EVALUATION_REQUIRED", "RISK_EVALUATION_EXPIRED", "RISK_TRANSACTION_NOT_FOUND",
                "SUPPORT_MESSAGE_REQUIRED", "SUPPORT_RESPONSE_POLICY_VIOLATION" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private String traceId(HttpServletRequest request) {
        String header = request.getHeader("X-Trace-Id");
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}
