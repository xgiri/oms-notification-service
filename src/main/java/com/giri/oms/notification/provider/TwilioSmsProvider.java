package com.giri.oms.notification.provider;

import com.giri.oms.notification.entity.NotificationChannel;
import com.twilio.exception.ApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Phase 4's SMS channel. Same programmatic resilience4j composition as
 * customerclient/orderclient's *ClientImpl classes (own CircuitBreaker +
 * Retry pulled from the registry by instance name, decorated around a
 * Supplier) — see either's Javadoc for the full reasoning. One real
 * difference from those: this class's contract (via {@link NotificationProvider})
 * is to never throw, so the outer {@link #send} catches whatever the
 * resilient call still lets through (retries exhausted, or the circuit
 * open) and converts it to {@link ProviderResult#failure} rather than
 * propagating — see NotificationProvider's own Javadoc on why a failed send
 * is this class's ordinary, expected outcome, not an exceptional one.
 * <p>
 * Permanent-vs-transient handling works differently here than
 * *ClientImpl's NotFound-vs-everything-else split, though: rather than
 * configuring resilience4j's {@code ignore-exceptions} for one specific
 * exception type, {@link #doSend} itself decides — bad-number, opted-out,
 * and similar recipient-specific Twilio errors (any 4xx that isn't a 429
 * rate limit) are returned as an ordinary {@code ProviderResult.failure}
 * without throwing at all, so Retry never even considers them (Retry only
 * reacts to exceptions, not return values). Only a genuinely transient
 * failure (5xx, 429, connection errors) is thrown from {@code doSend}, so
 * only those reach Retry/CircuitBreaker.
 * <p>
 * The actual Twilio SDK call is delegated to the injected {@link SmsSender}
 * rather than made directly here — see that interface's own Javadoc for
 * why: it's what lets {@code TwilioSmsProviderTest} exercise every branch
 * above (permanent vs. transient, retry-then-succeed, circuit open) against
 * a mocked collaborator, deterministically and without network access.
 */
@Slf4j
@Component
public class TwilioSmsProvider implements NotificationProvider {

    private static final String RESILIENCE_INSTANCE_NAME = "twilioSmsProvider";

    private final SmsSender smsSender;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String fromNumber;

    public TwilioSmsProvider(SmsSender smsSender,
                              CircuitBreakerRegistry circuitBreakerRegistry,
                              RetryRegistry retryRegistry,
                              @Value("${app.notification.sms.from-number}") String fromNumber) {
        this.smsSender = smsSender;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.fromNumber = fromNumber;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public ProviderResult send(NotificationRequest request) {
        Supplier<ProviderResult> call = () -> doSend(request);
        Supplier<ProviderResult> resilient =
                CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));

        try {
            return resilient.get();
        } catch (Exception ex) {
            // Reached only for a transient failure that survived every
            // retry attempt, or a circuit-breaker-open short-circuit — see
            // class Javadoc. Recorded and left for
            // NotificationRetryScheduler, same as SmtpEmailProvider's own
            // failure path, not re-thrown.
            log.warn("SMS send failed after retries for {}: {}", request.recipientAddress(), ex.getMessage());
            return ProviderResult.failure(ex.getMessage());
        }
    }

    private ProviderResult doSend(NotificationRequest request) {
        try {
            String providerMessageId = smsSender.send(request.recipientAddress(), fromNumber, request.textBody());
            return ProviderResult.success(providerMessageId);
        } catch (ApiException ex) {
            if (isPermanentFailure(ex)) {
                log.warn("Permanent SMS send failure to {}: {} (twilioCode={}, httpStatus={})",
                        request.recipientAddress(), ex.getMessage(), ex.getCode(), ex.getStatusCode());
                return ProviderResult.failure(ex.getMessage());
            }
            // Transient — rethrow so Retry/CircuitBreaker in send() see it.
            throw ex;
        }
    }

    /**
     * A recipient-specific 4xx (bad/unreachable number, recipient opted out
     * via STOP, unverified number on a trial account, ...) is never going
     * to succeed on retry — retrying it just burns the retry budget and
     * delays the circuit breaker from reacting to a REAL outage. 429 (rate
     * limited) is the one 4xx worth retrying. Anything without a status
     * code (a raw connection failure before Twilio ever responded) is
     * treated as transient, same as a 5xx.
     */
    private boolean isPermanentFailure(ApiException ex) {
        Integer statusCode = ex.getStatusCode();
        return statusCode != null && statusCode >= 400 && statusCode < 500 && statusCode != 429;
    }
}
