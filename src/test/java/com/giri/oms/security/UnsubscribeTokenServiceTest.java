package com.giri.oms.security;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.exception.InvalidUnsubscribeTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips the token through the real generate/parse path rather than
 * mocking jjwt — a signature or claim-shape bug is exactly what this class
 * exists to catch, and mocking the JWT library would hide precisely that.
 */
class UnsubscribeTokenServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes());

    private UnsubscribeTokenService tokenService;

    @BeforeEach
    void setUp() {
        UnsubscribeTokenProperties properties = new UnsubscribeTokenProperties(
                SECRET, 60_000L, "https://notify.example.com");
        tokenService = new UnsubscribeTokenService(properties);
        tokenService.init();
    }

    @Test
    void aTokenMintedForACustomer_parsesBackToTheSameCustomerTypeAndChannel() {
        String link = tokenService.buildUnsubscribeLink(42L, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL);
        String token = extractTokenParam(link);

        UnsubscribeTokenService.UnsubscribeTokenClaims claims = tokenService.parseToken(token);

        assertThat(claims.customerId()).isEqualTo(42L);
        assertThat(claims.type()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(claims.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void theBuiltLink_pointsAtThisServicesOwnUnsubscribeEndpoint() {
        String link = tokenService.buildUnsubscribeLink(42L, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL);

        assertThat(link).startsWith("https://notify.example.com/api/v1/notifications/unsubscribe?token=");
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        UnsubscribeTokenProperties otherProperties = new UnsubscribeTokenProperties(
                Base64.getEncoder().encodeToString("99999999999999999999999999999999".getBytes()),
                60_000L, "https://notify.example.com");
        UnsubscribeTokenService otherService = new UnsubscribeTokenService(otherProperties);
        otherService.init();
        String tokenFromOtherService = extractTokenParam(
                otherService.buildUnsubscribeLink(42L, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL));

        assertThatThrownBy(() -> tokenService.parseToken(tokenFromOtherService))
                .isInstanceOf(InvalidUnsubscribeTokenException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        UnsubscribeTokenProperties alreadyExpiredProperties = new UnsubscribeTokenProperties(SECRET, -1000L, "https://notify.example.com");
        UnsubscribeTokenService alreadyExpiredService = new UnsubscribeTokenService(alreadyExpiredProperties);
        alreadyExpiredService.init();
        String expiredToken = extractTokenParam(
                alreadyExpiredService.buildUnsubscribeLink(42L, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL));

        assertThatThrownBy(() -> tokenService.parseToken(expiredToken))
                .isInstanceOf(InvalidUnsubscribeTokenException.class);
    }

    @Test
    void rejectsAWellSignedTokenThatWasNeverMintedForTheUnsubscribePurpose() {
        // Same secret, same claim shapes, but no "purpose" claim at all —
        // simulates the secret ever being reused for a different token type
        // in the future (see this class's own Javadoc on why the purpose
        // claim exists).
        byte[] keyBytes = Base64.getDecoder().decode(SECRET);
        String tokenWithoutPurposeClaim = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim("customerId", "42")
                .claim("notificationType", "ORDER_CONFIRMED")
                .claim("channel", "EMAIL")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(keyBytes), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> tokenService.parseToken(tokenWithoutPurposeClaim))
                .isInstanceOf(InvalidUnsubscribeTokenException.class);
    }

    @Test
    void rejectsGarbageInput() {
        assertThatThrownBy(() -> tokenService.parseToken("not-a-real-token"))
                .isInstanceOf(InvalidUnsubscribeTokenException.class);
    }

    private String extractTokenParam(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }
}
