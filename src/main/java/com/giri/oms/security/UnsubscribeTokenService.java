package com.giri.oms.security;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.exception.InvalidUnsubscribeTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and verifies the one token type this service both issues and
 * checks itself — the unsubscribe link embedded in a notification email.
 * Deliberately NOT built on oms-main's JwtService/JWKS machinery: that
 * exists for tokens a logged-in human carries and OTHER services must be
 * able to verify independently (hence RS256 and a published public key).
 * This token has exactly one issuer and exactly one verifier — both this
 * class — so a shared HMAC secret (HS256) known only here is simpler and
 * sufficient; there's no third party that ever needs to check it.
 * <p>
 * <b>Stateless, not single-use.</b> Per this service's own design decision
 * (see the README's unsubscribe section), this token is valid for its
 * whole lifetime (see {@link UnsubscribeTokenProperties#expirationMs()}),
 * not invalidated after first use. That's a deliberate simplification, not
 * an oversight: opting out is idempotent (see
 * NotificationPreferenceService#optOut), so a link clicked twice — or
 * forwarded, or crawled by an email client's link-prescanning — just
 * opts the same customer out twice, which is harmless. A single-use design
 * would need a persisted "used tokens" table purely to defend against a
 * scenario that already causes no harm; not built for that reason.
 * <p>
 * The {@code purpose} claim exists so this secret, if it were ever reused
 * for another token type in the future, can't have one type's token
 * accepted as another's — verified on every {@link #parseToken}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnsubscribeTokenService {

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String PURPOSE_UNSUBSCRIBE = "unsubscribe";
    private static final String CUSTOMER_ID_CLAIM = "customerId";
    private static final String NOTIFICATION_TYPE_CLAIM = "notificationType";
    private static final String CHANNEL_CLAIM = "channel";
    private static final String UNSUBSCRIBE_PATH = "/api/v1/notifications/unsubscribe";

    private final UnsubscribeTokenProperties properties;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secret()));
    }

    /**
     * The full clickable URL for a notification email — token generation
     * plus this service's own public origin ({@link UnsubscribeTokenProperties#linkBaseUrl()})
     * plus the fixed controller path, in one place so a caller (see
     * NotificationServiceImpl) never has to know the path or query
     * parameter name itself.
     */
    public String buildUnsubscribeLink(Long customerId, NotificationType type, NotificationChannel channel) {
        String token = generateToken(customerId, type, channel);
        return UriComponentsBuilder.fromUriString(properties.linkBaseUrl())
                .path(UNSUBSCRIBE_PATH)
                .queryParam("token", token)
                .toUriString();
    }

    private String generateToken(Long customerId, NotificationType type, NotificationChannel channel) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.expirationMs());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim(PURPOSE_CLAIM, PURPOSE_UNSUBSCRIBE)
                .claim(CUSTOMER_ID_CLAIM, String.valueOf(customerId))
                .claim(NOTIFICATION_TYPE_CLAIM, type.name())
                .claim(CHANNEL_CLAIM, channel.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @throws InvalidUnsubscribeTokenException for a missing/malformed/
     *         tampered/expired token, or one that verifies but carries a
     *         different {@code purpose} — see that exception's Javadoc for
     *         why all of those collapse to one generic error for the
     *         caller.
     */
    public UnsubscribeTokenClaims parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!PURPOSE_UNSUBSCRIBE.equals(claims.get(PURPOSE_CLAIM, String.class))) {
                throw new InvalidUnsubscribeTokenException();
            }

            Long customerId = Long.valueOf(claims.get(CUSTOMER_ID_CLAIM, String.class));
            NotificationType type = NotificationType.valueOf(claims.get(NOTIFICATION_TYPE_CLAIM, String.class));
            NotificationChannel channel = NotificationChannel.valueOf(claims.get(CHANNEL_CLAIM, String.class));

            return new UnsubscribeTokenClaims(customerId, type, channel);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected invalid unsubscribe token: {}", ex.getMessage());
            throw new InvalidUnsubscribeTokenException();
        }
    }

    public record UnsubscribeTokenClaims(Long customerId, NotificationType type, NotificationChannel channel) {
    }
}
