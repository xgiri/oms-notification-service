package com.giri.oms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code secret} is Base64 of a raw HMAC-SHA256 key — see
 * {@link UnsubscribeTokenService} for how it's decoded and used, and
 * application.properties for why it has no safe default (same posture as
 * {@code app.customerclient.service-api-key}: a placeholder default here
 * would mean every unconfigured deployment silently shares the same
 * signing key). {@code expirationMs} is how long a link stays valid after
 * the email is sent — generous by design (30 days in the shipped default),
 * since this token is stateless/replayable-until-expiry rather than
 * single-use (see UnsubscribeTokenService's own Javadoc for why that
 * tradeoff is fine here). {@code linkBaseUrl} is this service's own
 * externally-reachable origin, used to build the full clickable link
 * embedded in an email — deliberately NOT inferred from the incoming
 * request (there is no incoming request; this is built from a Kafka
 * listener thread, see NotificationServiceImpl).
 */
@ConfigurationProperties(prefix = "app.unsubscribe-token")
public record UnsubscribeTokenProperties(String secret, long expirationMs, String linkBaseUrl) {
}
