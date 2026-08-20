package com.giri.oms.common.exception;

import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.Set;

/**
 * A fresh service, not extracted from oms-main — so unlike shipment-service's
 * own ErrorCode (which carries over codes unchanged from a deleted module for
 * backward-compatibility reasons), this one starts from a clean slate. Still
 * reuses CUSTOMER_NOT_FOUND ("ECU100") / ORDER_NOT_FOUND ("EOR100") unchanged
 * from oms-main's own ErrorCode, deliberately — a 404 for "this customer/
 * order doesn't exist" is the same fact everywhere in this system, observed
 * over HTTP by whichever client happens to be asking, so it gets the same
 * code everywhere rather than a service-local reinvention of it.
 * <p>
 * The stability contract itself (append-only: once published, never
 * reassign or renumber a code) applies here exactly as it does in oms-main —
 * see that repo's ErrorCode for the fuller policy Javadoc, not repeated here.
 */
public enum ErrorCode {

    // ---- Common / platform (CM) ----
    VALIDATION_FAILED("E", "CM", "001", HttpStatus.BAD_REQUEST,
            "One or more fields failed validation"),
    INVALID_SORT_FIELD("E", "CM", "002", HttpStatus.BAD_REQUEST,
            "Invalid sort field: %s"),
    UNAUTHENTICATED("E", "CM", "003", HttpStatus.UNAUTHORIZED,
            "A valid Bearer token is required to access this resource"),
    ACCESS_DENIED("E", "CM", "101", HttpStatus.FORBIDDEN,
            "You do not have permission to perform this action"),
    OPTIMISTIC_LOCK_CONFLICT("E", "CM", "103", HttpStatus.CONFLICT,
            "This record was modified by someone else in the meantime — please refresh and try again."),
    RESOURCE_CONFLICT("E", "CM", "105", HttpStatus.CONFLICT,
            "This request conflicts with an existing record — please check for a duplicate and try again."),
    INTERNAL_ERROR("E", "CM", "500", HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."),

    // ---- Order (OR) — OrderClient only; Order itself lives in oms-main ----
    ORDER_NOT_FOUND("E", "OR", "100", HttpStatus.NOT_FOUND,
            "Order not found with id: %d"),
    ORDER_SERVICE_UNAVAILABLE("E", "OR", "500", HttpStatus.SERVICE_UNAVAILABLE,
            "Order service is currently unavailable (order id: %d) — please try again shortly"),

    // ---- Customer (CU) — CustomerClient only; Customer itself lives in customer-service ----
    CUSTOMER_NOT_FOUND("E", "CU", "100", HttpStatus.NOT_FOUND,
            "Customer not found with id: %d"),
    CUSTOMER_SERVICE_UNAVAILABLE("E", "CU", "500", HttpStatus.SERVICE_UNAVAILABLE,
            "Customer service is currently unavailable (customer id: %d) — please try again shortly"),

    // ---- Notification (NT) — this service's own domain ----
    NOTIFICATION_NOT_FOUND("E", "NT", "100", HttpStatus.NOT_FOUND,
            "Notification not found with id: %d"),
    // Thrown by security.UnsubscribeTokenService for a missing/malformed/
    // tampered/expired token, or one that verifies but wasn't minted for
    // this purpose (see that class's PURPOSE_CLAIM check). Deliberately one
    // generic message for all of those cases — telling an anonymous caller
    // WHICH way their token is invalid (expired vs. tampered vs. wrong
    // purpose) has no legitimate use and only helps someone probing the
    // endpoint.
    INVALID_UNSUBSCRIBE_TOKEN("E", "NT", "101", HttpStatus.BAD_REQUEST,
            "This unsubscribe link is invalid or has expired"),
    // Thrown by NotificationServiceImpl#resend for a notification that
    // isn't FAILED or DEAD_LETTERED — see that method's own Javadoc for why
    // resending a PENDING/SENT row isn't a meaningful operation. Same
    // 409 CONFLICT convention as oms-main's ILLEGAL_ORDER_STATE/
    // ILLEGAL_PAYMENT_STATE for the same kind of situation (an operation
    // that conflicts with the resource's current status, not a validation
    // error on the request itself).
    ILLEGAL_NOTIFICATION_STATE("E", "NT", "102", HttpStatus.CONFLICT,
            "Operation conflicts with the notification's current status");

    private final String prefix;
    private final String componentId;
    private final String errorId;
    private final HttpStatus httpStatus;
    private final String messageTemplate;

    ErrorCode(String prefix, String componentId, String errorId, HttpStatus httpStatus, String messageTemplate) {
        this.prefix = prefix;
        this.componentId = componentId;
        this.errorId = errorId;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }

    public String code() {
        return prefix + componentId + errorId;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String formatMessage(Object... args) {
        return String.format(messageTemplate, args);
    }

    public String sampleMessage() {
        return messageTemplate
                .replace("%d", "123")
                .replace("%s", "example");
    }

    static {
        Set<String> seen = new HashSet<>();
        for (ErrorCode value : values()) {
            if (!seen.add(value.code())) {
                throw new ExceptionInInitializerError(
                        "Duplicate ErrorCode.code() value: " + value.code() + " (on " + value.name() + ")");
            }
        }
    }
}
