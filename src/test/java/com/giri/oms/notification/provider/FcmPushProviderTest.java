package com.giri.oms.notification.provider;

import com.giri.oms.notification.entity.NotificationChannel;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link FcmPushProvider} against a mocked {@link PushSender} —
 * same reasoning as {@code TwilioSmsProviderTest} for {@code SmsSender},
 * see that class's own Javadoc for the fuller "why not a WireMock contract
 * test" argument, which applies here identically.
 * <p>
 * One real difference from {@code TwilioSmsProviderTest} worth being
 * explicit about, since it shapes every test below: {@code ApiException}
 * has a public constructor this codebase can call directly (see
 * {@code TwilioSmsProviderTest}'s own note on the 8-arg one). {@link
 * FirebaseMessagingException} does NOT — its only constructors are
 * package-private to {@code com.google.firebase.messaging} (verified
 * against firebase-admin {@code 9.10.0}'s actual source; even the one
 * annotated {@code @VisibleForTesting} is package-private, not public, and
 * it doesn't accept a {@link MessagingErrorCode} anyway — only the
 * package-private {@code withMessagingErrorCode} factory does). So every
 * exception below is {@code Mockito.mock(FirebaseMessagingException.class)}
 * with {@code getMessagingErrorCode()} stubbed, not a real constructed
 * instance — the only way to get a specific error code onto this
 * exception type from outside its own package. This still exercises
 * exactly the same production code path ({@link
 * FcmPushProvider#isPermanentFailure} only ever calls
 * {@code getMessagingErrorCode()}), so it's a faithful test of THIS
 * class's logic; it just can't be a real end-to-end FCM exception the way
 * {@code TwilioSmsProviderTest}'s {@code ApiException} instances are.
 * Mocking a {@code final} class like this needs Mockito's inline mock
 * maker, which has been the default (no extra dependency or opt-in) since
 * Mockito 5.0 — bundled transitively here via Spring Boot 4.1's parent BOM.
 * <p>
 * Real (not mocked) resilience4j {@code Retry}/{@code CircuitBreaker}
 * instances are built here, shaped to mirror {@code application.properties}'
 * {@code fcmPushProvider} instance (max-attempts=2) — same reasoning as
 * {@code TwilioSmsProviderTest}'s own registries.
 * <p>
 * Every {@code mockException(...)} call is assigned to a local variable
 * BEFORE the {@code when(pushSender.send(...))} chain that throws it, never
 * passed inline as {@code .thenThrow(mockException(...))}. That's not
 * stylistic — {@code mockException} itself calls {@code when(...)} twice
 * internally, and nesting a second, unrelated {@code when(...)} call inside
 * the still-open argument position of an outer {@code when(...).thenThrow(...)}
 * corrupts Mockito's stubbing state (manifests as
 * {@code UnfinishedStubbingException}, thrown from a confusing location).
 * Extracting the exception first avoids ever having two stubbing chains
 * open at once.
 */
@ExtendWith(MockitoExtension.class)
class FcmPushProviderTest {

    private static final String PUSH_TOKEN = "fcm-token-abc123";

    @Mock
    private PushSender pushSender;

    private FcmPushProvider provider;

    @BeforeEach
    void setUp() {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1)) // real prod value is 200ms — kept tiny so this test stays fast
                .retryExceptions(FcmSendException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

        provider = new FcmPushProvider(pushSender, circuitBreakerRegistry, retryRegistry);
    }

    private NotificationRequest request() {
        // subject doubles as the push title — see FcmPushProvider#doSend's
        // own Javadoc on why there's no separate push-title field.
        return new NotificationRequest(PUSH_TOKEN, "Your order is confirmed", null, "Order #12345 is on its way.");
    }

    private FirebaseMessagingException mockException(MessagingErrorCode code, String message) {
        FirebaseMessagingException ex = mock(FirebaseMessagingException.class);
        when(ex.getMessagingErrorCode()).thenReturn(code);
        when(ex.getMessage()).thenReturn(message);
        return ex;
    }

    @Test
    void returnsSuccess_withTheProviderMessageId_whenSendSucceeds() throws Exception {
        when(pushSender.send(PUSH_TOKEN, "Your order is confirmed", "Order #12345 is on its way."))
                .thenReturn("projects/oms/messages/abc123");

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("projects/oms/messages/abc123");
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRetry_onUnregistered_theTokenIsNoLongerValid() throws Exception {
        // The single most common real-world permanent case — app
        // uninstalled or token rotated. Retrying sends to a device that
        // will never receive it.
        FirebaseMessagingException ex = mockException(MessagingErrorCode.UNREGISTERED, "Requested entity was not found.");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not found");
        // The core behavior this test exists to prove: exactly ONE call,
        // not maxAttempts — see FcmPushProvider#isPermanentFailure's own
        // Javadoc on why retrying an unregistered token is pure waste.
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRetry_onInvalidArgument_aMalformedTokenOrMessage() throws Exception {
        FirebaseMessagingException ex = mockException(MessagingErrorCode.INVALID_ARGUMENT, "Malformed registration token.");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRetry_onSenderIdMismatch_tokenBelongsToADifferentFirebaseProject() throws Exception {
        FirebaseMessagingException ex = mockException(MessagingErrorCode.SENDER_ID_MISMATCH, "SenderId mismatch");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRetry_onThirdPartyAuthError_anApnsCredentialIssueNotFixableByRetrying() throws Exception {
        FirebaseMessagingException ex = mockException(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR, "APNs certificate invalid");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void retries_onQuotaExceeded_unlikeTheOtherErrorCodes() throws Exception {
        FirebaseMessagingException ex = mockException(MessagingErrorCode.QUOTA_EXCEEDED, "Too many messages");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        // maxAttempts=2 means the underlying call is made twice, not once —
        // this is the assertion that actually distinguishes "retried" from
        // "didn't retry", unlike the permanent-failure tests above.
        verify(pushSender, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void retriesThenSucceeds_whenATransientFailureIsFollowedByASuccess() throws Exception {
        FirebaseMessagingException ex = mockException(MessagingErrorCode.UNAVAILABLE, "Backend unavailable");
        when(pushSender.send(anyString(), anyString(), anyString()))
                .thenThrow(ex)
                .thenReturn("projects/oms/messages/def456");

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("projects/oms/messages/def456");
        verify(pushSender, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void treatsANullErrorCode_asTransient_andRetries() throws Exception {
        // No structured FCM error code at all — e.g. a raw connection
        // failure before FCM ever returned a parsed error response.
        // Distinct code path from the null-status-code case in
        // TwilioSmsProviderTest, but the same "unknown means transient"
        // default — see FcmPushProvider#isPermanentFailure's own Javadoc.
        FirebaseMessagingException ex = mockException(null, "Connection reset");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        ProviderResult result = provider.send(request());

        assertThat(result.success()).isFalse();
        verify(pushSender, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void neverThrows_evenWhenRetriesAreFullyExhausted() throws Exception {
        // The contract NotificationServiceImpl depends on — see
        // NotificationProvider's own Javadoc. A provider that throws here
        // would propagate out of NotificationServiceImpl#sendAndRecord and
        // fail the whole Kafka message, which is exactly the
        // wrong-granularity failure this design avoids.
        FirebaseMessagingException ex = mockException(MessagingErrorCode.UNAVAILABLE, "Backend unavailable");
        when(pushSender.send(anyString(), anyString(), anyString())).thenThrow(ex);

        Assertions.assertDoesNotThrow(() -> provider.send(request()));
    }

    @Test
    void reportsThePushChannel() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.PUSH);
    }
}
