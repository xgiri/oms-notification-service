package com.giri.oms.common.exception;

import com.giri.oms.customerclient.exception.CustomerNotFoundException;
import com.giri.oms.customerclient.exception.CustomerServiceUnavailableException;
import com.giri.oms.notification.exception.InvalidUnsubscribeTokenException;
import com.giri.oms.notification.exception.NotificationNotFoundException;
import com.giri.oms.orderclient.exception.OrderNotFoundException;
import com.giri.oms.orderclient.exception.OrderServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * A fresh service, not extracted from oms-main — this handler set is built
 * for what THIS service actually needs, not trimmed from oms-main's own
 * (unlike shipment-service's, which was). CustomerNotFoundException/
 * CustomerServiceUnavailableException/OrderNotFoundException/
 * OrderServiceUnavailableException are this service's OWN customerclient/
 * orderclient packages' exceptions, not the (now-deleted) customer/order
 * modules' — same "these live at the boundary this service actually calls"
 * reasoning as every other extracted/new service's exception handler in
 * this system. No LockAcquisitionException (no distributed locking here)
 * and no login-failure handler (this service never issues tokens, only
 * verifies them — see security.SecurityConfig).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCustomerNotFound(CustomerNotFoundException ex, HttpServletRequest request) {
        log.warn("Customer not found — path: {}, message: {}", request.getRequestURI(), ex.getMessage());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    // See CustomerServiceUnavailableException's Javadoc for why this matters
    // more here than in most services calling out to customer-service: a
    // failed lookup means a notification literally cannot be composed (no
    // recipient address) — deliberately its own handler rather than falling
    // through to the catch-all Exception handler below, same reasoning as
    // oms-main's own ProductServiceUnavailableException/CustomerServiceUnavailableException
    // handlers.
    @ExceptionHandler(CustomerServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleCustomerServiceUnavailable(CustomerServiceUnavailableException ex, HttpServletRequest request) {
        log.error("customer service unavailable — path: {}, message: {}", request.getRequestURI(), ex.getMessage());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotificationNotFound(NotificationNotFoundException ex, HttpServletRequest request) {
        log.warn("Notification not found — path: {}, message: {}", request.getRequestURI(), ex.getMessage());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    // Thrown by OrderClient when oms-main's order endpoint returns 404 for
    // the order id an OrderConfirmed event references (see
    // OrderNotificationConsumer/PaymentNotificationConsumer) — a legitimate business rejection,
    // not a service-health problem.
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        log.warn("Order not found — path: {}, message: {}", request.getRequestURI(), ex.getMessage());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    @ExceptionHandler(OrderServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleOrderServiceUnavailable(OrderServiceUnavailableException ex, HttpServletRequest request) {
        log.error("order service unavailable — path: {}, message: {}", request.getRequestURI(), ex.getMessage());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    // Deliberately logged at WARN, not ERROR — an invalid/expired/tampered
    // unsubscribe token reaching here is an expected, unauthenticated-caller
    // scenario (an old link, a link someone tampered with), not a system
    // health signal.
    @ExceptionHandler(InvalidUnsubscribeTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUnsubscribeToken(InvalidUnsubscribeTokenException ex, HttpServletRequest request) {
        log.warn("Invalid unsubscribe token — path: {}", request.getRequestURI());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidSortFieldException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSortField(InvalidSortFieldException ex, HttpServletRequest request) {
        log.warn("Invalid sort field — path: {}, message: {}", request.getRequestURI(), ex.getMessage());
        return build(codeOf(ex), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_FAILED.formatMessage());
        log.warn("Validation failed — path: {}, message: {}", request.getRequestURI(), message);
        return build(ErrorCode.VALIDATION_FAILED, message, request);
    }

    // See oms-main's GlobalExceptionHandler for why this needs to be its own
    // handler rather than falling through to the catch-all below — same
    // reasoning applies here (@PreAuthorize on any admin-only endpoint in
    // notification.controller throws this from inside the MVC dispatch, not
    // the security filter chain).
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied — path: {}", request.getRequestURI());
        return build(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.formatMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error — path: {}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.formatMessage(), request);
    }

    private ErrorCode codeOf(ErrorCoded ex) {
        return ex.getErrorCode();
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(code.code(), message, request.getRequestURI(), LocalDateTime.now(clock));
        return ResponseEntity.status(code.httpStatus()).body(body);
    }
}
