package com.giri.oms.notification.provider;

import com.giri.oms.customerclient.dto.CustomerClientResponse;
import com.giri.oms.notification.entity.NotificationChannel;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * §5's push channel — built as far as it can be, given this session's
 * scope decision to build the notification-service side of push now
 * without waiting on the customer-service change it depends on (see this
 * service's plan-status doc §5). Structurally a mirror of
 * {@code TwilioSmsProvider}: same programmatic resilience4j composition
 * (own {@link CircuitBreaker}/{@link Retry} pulled from the registry by
 * instance name, decorated around a {@link Supplier}), same never-throw
 * {@link NotificationProvider} contract, same permanent-vs-transient split
 * decided inside {@link #doSend} rather than via resilience4j
 * {@code ignore-exceptions} — see that class's own Javadoc for the shared
 * reasoning, not repeated here.
 * <p>
 * <b>Deliberately gated behind {@code app.notification.push.enabled}
 * (default {@code false})</b> — unlike {@code TwilioSmsProvider}, this
 * can't safely be registered unconditionally yet. The reason isn't "the
 * code doesn't work" — it's that {@code FcmConfig} needs real FCM service
 * account credentials to initialize {@code FirebaseApp} at startup, with
 * no safe default (same posture as {@code TwilioConfig}'s Twilio
 * credentials). Registering this provider unconditionally would mean
 * every environment — every developer's local run, CI, this repo's own
 * tests — would need real FCM credentials configured just to boot the
 * application, for a channel that can't do anything useful yet regardless
 * (see {@link CustomerClientResponse#pushToken()}'s own Javadoc: it's
 * always {@code null} until customer-service ships the field). Flipping
 * {@code app.notification.push.enabled=true} once that upstream change
 * ships, plus real credentials, is the entire activation path — no other
 * code change needed here.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class FcmPushProvider implements NotificationProvider {

    private static final String RESILIENCE_INSTANCE_NAME = "fcmPushProvider";

    private final PushSender pushSender;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public FcmPushProvider(PushSender pushSender,
                            CircuitBreakerRegistry circuitBreakerRegistry,
                            RetryRegistry retryRegistry) {
        this.pushSender = pushSender;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
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
            // NotificationRetryScheduler, same as TwilioSmsProvider's own
            // failure path, not re-thrown.
            log.warn("Push send failed after retries for {}: {}", request.recipientAddress(), ex.getMessage());
            return ProviderResult.failure(ex.getMessage());
        }
    }

    /**
     * {@code request.subject()} doubles as the push notification's title —
     * {@code NotificationComposerImpl#subjectFor} already computes a
     * subject line uniformly for every channel; a push notification's
     * title is exactly that same "one-line summary" concept, so this
     * reuses it rather than adding a third templated field
     * {@link NotificationRequest} would need just for push. See that
     * record's own Javadoc on why not every field is meaningful to every
     * channel — this is the PUSH-specific instance of that same pattern
     * SMS already established for {@code htmlBody}.
     */
    private ProviderResult doSend(NotificationRequest request) {
        try {
            String providerMessageId = pushSender.send(request.recipientAddress(), request.subject(), request.textBody());
            return ProviderResult.success(providerMessageId);
        } catch (FirebaseMessagingException ex) {
            if (isPermanentFailure(ex)) {
                log.warn("Permanent push send failure to {}: {} (fcmErrorCode={})",
                        request.recipientAddress(), ex.getMessage(), ex.getMessagingErrorCode());
                return ProviderResult.failure(ex.getMessage());
            }
            // Transient — wrap as unchecked (see FcmSendException's own
            // Javadoc for why that's required here but wasn't for
            // TwilioSmsProvider's ApiException) so Retry/CircuitBreaker in
            // send() above see it.
            throw new FcmSendException(ex);
        }
    }

    /**
     * A token-specific or message-specific failure is never going to
     * succeed on retry — retrying it just burns the retry budget and
     * delays the circuit breaker from reacting to a REAL outage. Mirrors
     * {@code TwilioSmsProvider#isPermanentFailure}'s reasoning exactly,
     * translated to FCM's own error taxonomy:
     * <ul>
     *   <li>{@code UNREGISTERED} — the token is no longer valid (app
     *   uninstalled, token rotated). Retrying sends to a device that will
     *   never receive it.</li>
     *   <li>{@code INVALID_ARGUMENT} — malformed token or message.</li>
     *   <li>{@code SENDER_ID_MISMATCH} — token belongs to a different
     *   Firebase project than these credentials.</li>
     *   <li>{@code THIRD_PARTY_AUTH_ERROR} — APNs credentials issue on
     *   Apple's side of a cross-platform send, not something a retry
     *   fixes.</li>
     * </ul>
     * Everything else — {@code QUOTA_EXCEEDED}, {@code UNAVAILABLE},
     * {@code INTERNAL}, or {@code null} (no error code at all, e.g. a raw
     * connection failure before FCM ever responded) — is treated as
     * transient, same "unknown means transient" default
     * {@code TwilioSmsProvider} uses for a missing status code.
     */
    private boolean isPermanentFailure(FirebaseMessagingException ex) {
        MessagingErrorCode code = ex.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED
                || code == MessagingErrorCode.INVALID_ARGUMENT
                || code == MessagingErrorCode.SENDER_ID_MISMATCH
                || code == MessagingErrorCode.THIRD_PARTY_AUTH_ERROR;
    }
}
