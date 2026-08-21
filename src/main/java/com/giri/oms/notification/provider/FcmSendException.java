package com.giri.oms.notification.provider;

import com.google.firebase.messaging.FirebaseMessagingException;

/**
 * Unchecked wrapper around a transient {@link FirebaseMessagingException}
 * so it can propagate through {@link FcmPushProvider#send}'s
 * {@code Supplier<ProviderResult>} lambda and be seen by resilience4j
 * Retry/CircuitBreaker (see application.properties'
 * {@code resilience4j.retry.instances.fcmPushProvider.retry-exceptions}).
 * {@code TwilioSmsProvider} doesn't need an equivalent — twilio-java's own
 * {@code ApiException} is already unchecked;
 * {@code FirebaseMessagingException} is checked, and a lambda implementing
 * {@code Supplier.get()} can't let a checked exception escape.
 */
public class FcmSendException extends RuntimeException {

    public FcmSendException(FirebaseMessagingException cause) {
        super(cause.getMessage(), cause);
    }
}
