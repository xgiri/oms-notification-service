package com.giri.oms.notification.provider;

import com.google.firebase.messaging.FirebaseMessagingException;

/**
 * The one seam between {@link FcmPushProvider}'s own logic (retry
 * classification, circuit breaker, permanent-vs-transient handling) and
 * firebase-admin's {@code FirebaseMessaging.getInstance().send(...)} call
 * — same role {@link SmsSender} plays for {@link TwilioSmsProvider}, same
 * reasoning for why it exists as its own interface (lets
 * {@code FcmPushProviderTest} exercise the resilience logic against a
 * mocked collaborator, deterministically, without real FCM credentials or
 * a device to send to). See {@code SmsSender}'s own Javadoc for the fuller
 * "why not a WireMock contract test" reasoning, which applies here for the
 * same reason (firebase-admin owns its own transport internally; a real
 * drift risk would be in firebase-admin's own response shape, which
 * firebase-admin itself tests).
 * <p>
 * Deliberately narrow — three primitives in, a provider message id out,
 * {@link FirebaseMessagingException} propagating on failure — same shape
 * as {@code SmsSender}, not a general-purpose push abstraction.
 */
public interface PushSender {

    String send(String token, String title, String body) throws FirebaseMessagingException;
}
