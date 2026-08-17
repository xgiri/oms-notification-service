package com.giri.oms.notification.provider;

import com.twilio.exception.ApiException;

/**
 * The one seam between {@link TwilioSmsProvider}'s own logic (retry
 * classification, circuit breaker, permanent-vs-transient handling — all
 * ours, all worth testing directly) and the Twilio SDK's static
 * {@code Message.creator(...).create()} call, which isn't itself
 * injectable. Introduced specifically so {@code TwilioSmsProviderTest} can
 * exercise the resilience logic against a mocked {@link SmsSender} instead
 * of needing WireMock or real Twilio credentials — see that test class's
 * own Javadoc for why a literal HTTP-level contract test isn't practical
 * here the way {@code OrderClientContractTest} is for a plain RestClient
 * (twilio-java owns its HTTP client internally; there's no base-URL
 * override clean enough to point at WireMock without deeper SDK
 * surgery).
 * <p>
 * Deliberately narrow — three primitives in, a provider message id out,
 * {@link ApiException} propagating on failure — not a general-purpose SMS
 * abstraction. If a second SMS provider is ever added, THIS is not the
 * seam to reuse for it; {@link NotificationProvider} already is that
 * seam.
 */
public interface SmsSender {

    String send(String to, String from, String body) throws ApiException;
}
