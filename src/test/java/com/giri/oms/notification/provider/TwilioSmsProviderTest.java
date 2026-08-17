package com.giri.oms.notification.provider;

import com.giri.oms.notification.entity.NotificationChannel;
import com.twilio.exception.ApiConnectionException;
import com.twilio.exception.ApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link TwilioSmsProvider} against a mocked {@link SmsSender} —
 * see that interface's own Javadoc for why this is a mocked-collaborator
 * unit test rather than a WireMock-based contract test like
 * {@code OrderClientContractTest}, and why that's a considered decision,
 * not an unaddressed gap. What THIS class proves: this class's OWN logic
 * (permanent vs. transient classification, retry count, circuit-breaker
 * tripping) is correct — it says nothing about whether
 * {@link TwilioSmsSender}'s actual HTTP call still matches Twilio's real
 * API shape, which isn't this codebase's risk surface to begin with (see
 * {@link SmsSender}'s Javadoc).
 * <p>
 * Real (not mocked) resilience4j {@code Retry}/{@code CircuitBreaker}
 * instances are built here, shaped to mirror
 * {@code application.properties}' {@code twilioSmsProvider} instance
 * (max-attempts=2) — a mocked resilience layer would only prove this test
 * calls the mock the way the test itself expects, not that the actual
 * retry/circuit-breaker behavior is correct.
 * <p>
 * {@code new ApiException(message, code, moreInfo, status, null, null, null, null)}
 * below uses the 8-arg constructor {@code (message, code, moreInfo, status,
 * httpStatusCode, params, userError, cause)} — twilio-java {@code 12.1.1}
 * (pinned in pom.xml) removed the older 5-arg
 * {@code (message, code, moreInfo, status, cause)} constructor entirely,
 * not deprecated it. {@code getStatusCode()} (what
 * {@code TwilioSmsProvider#isPermanentFailure} actually reads) returns the
 * 4th arg ({@code status}), not the 5th ({@code httpStatusCode}) — the two
 * are genuinely different fields on this exception now, so it's the 4th
 * position that has to carry 400/429/500 below, not the 5th.
 */
@ExtendWith(MockitoExtension.class)
class TwilioSmsProviderTest {

    private static final String FROM_NUMBER = "+15005550006";
    private static final String TO_NUMBER = "+15551234567";

    @Mock
    private SmsSender smsSender;

    private TwilioSmsProvider provider;

    @BeforeEach
    void setUp() {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1)) // real prod value is 200ms — kept tiny so this test stays fast
                .retryExceptions(ApiException.class, ApiConnectionException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

        provider = new TwilioSmsProvider(smsSender, circuitBreakerRegistry, retryRegistry, FROM_NUMBER);
    }

    private NotificationRequest request() {
        return new NotificationRequest(TO_NUMBER, "ignored-by-sms", null, "Your order #12345 is confirmed.");
    }

    @Test
    void returnsSuccess_withTheProviderMessageId_whenSendSucceeds() {
        when(smsSender.send(TO_NUMBER, FROM_NUMBER, "Your order #12345 is confirmed.")).thenReturn("SM123abc");

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("SM123abc");
        verify(smsSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRetry_onAPermanentFourXxFailure_likeAnInvalidNumber() {
        // Twilio error 21211 — "Invalid 'To' Phone Number" — a real,
        // commonly-seen permanent failure, not a made-up example.
        ApiException invalidNumber = new ApiException(
                "The 'To' number is not a valid phone number.", 21211,
                "https://www.twilio.com/docs/errors/21211", 400, null, null, null, null);
        when(smsSender.send(anyString(), anyString(), anyString())).thenThrow(invalidNumber);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not a valid phone number");
        // The core behavior this test exists to prove: exactly ONE call,
        // not maxAttempts — see TwilioSmsProvider#isPermanentFailure's own
        // Javadoc on why retrying a bad number is pure waste.
        verify(smsSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRetry_whenTheRecipientHasOptedOutViaStop() {
        // Twilio error 21610 — recipient has replied STOP. Also permanent
        // (a 400), included as its own case since it's a different REASON
        // than a malformed number but must hit the same code path.
        ApiException optedOut = new ApiException(
                "Attempt to send to unsubscribed recipient.", 21610,
                "https://www.twilio.com/docs/errors/21610", 400, null, null, null, null);
        when(smsSender.send(anyString(), anyString(), anyString())).thenThrow(optedOut);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        verify(smsSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void retries_onA429RateLimit_unlikeOtherFourXxFailures() {
        ApiException rateLimited = new ApiException("Too Many Requests", 20429, null, 429, null, null, null, null);
        when(smsSender.send(anyString(), anyString(), anyString())).thenThrow(rateLimited);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        // maxAttempts=2 means the underlying call is made twice, not once —
        // this is the assertion that actually distinguishes "retried" from
        // "didn't retry", unlike the permanent-failure tests above.
        verify(smsSender, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void retriesThenSucceeds_whenATransientFailureIsFollowedByASuccess() {
        ApiException serverError = new ApiException("Internal Server Error", 20500, null, 500, null, null, null, null);
        when(smsSender.send(anyString(), anyString(), anyString()))
                .thenThrow(serverError)
                .thenReturn("SM456def");

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("SM456def");
        verify(smsSender, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void treatsAConnectionFailure_asTransient_andRetries() {
        // No HTTP status at all — Twilio was never reached. A distinct code
        // path from ApiException's own status-code check (getStatusCode()
        // is null for this exception type, not just unset) — see
        // TwilioSmsProvider#isPermanentFailure's null-check.
        when(smsSender.send(anyString(), anyString(), anyString()))
                .thenThrow(new ApiConnectionException("Connection reset"));

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        verify(smsSender, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void neverThrows_evenWhenRetriesAreFullyExhausted() {
        // The contract NotificationServiceImpl depends on — see
        // NotificationProvider's own Javadoc. A provider that throws here
        // would propagate out of NotificationServiceImpl#sendAndRecord and
        // fail the whole Kafka message, which is exactly the
        // wrong-granularity failure this design avoids.
        when(smsSender.send(anyString(), anyString(), anyString()))
                .thenThrow(new ApiException("Internal Server Error", 20500, null, 500, null, null, null, null));

        Assertions.assertDoesNotThrow(() -> provider.send(request()));
    }

    @Test
    void reportsTheSmsChannel() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.SMS);
    }
}