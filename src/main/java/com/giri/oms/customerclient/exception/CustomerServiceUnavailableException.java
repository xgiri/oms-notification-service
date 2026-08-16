package com.giri.oms.customerclient.exception;

import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.common.exception.ErrorCoded;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by CustomerClientImpl for everything that means "customer-service
 * could not be reached or did not answer in time" — a timeout, a 5xx, or an
 * open circuit breaker. Deliberately distinct from
 * {@link CustomerNotFoundException}, which CustomerClientImpl throws
 * instead for a 404 — a legitimate business rejection, not a service-health
 * problem, and the two must stay distinguishable (a 404 must never count as
 * a failure toward opening the circuit breaker).
 * <p>
 * What NotificationConsumer does with this matters more here than in
 * shipment-service's equivalent: a failed customer lookup means the
 * notification can't be composed at all (no recipient address). See that
 * consumer's Javadoc for why this propagates up to Kafka's own retry/DLT
 * handling rather than being caught and silently dropped.
 */
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
public class CustomerServiceUnavailableException extends RuntimeException implements ErrorCoded {

    public CustomerServiceUnavailableException(Long customerId, Throwable cause) {
        super(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE.formatMessage(customerId), cause);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE;
    }
}
