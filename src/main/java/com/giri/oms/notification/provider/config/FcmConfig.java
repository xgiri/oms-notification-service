package com.giri.oms.notification.provider.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Initializes firebase-admin's global {@code FirebaseApp} singleton once at
 * startup — same role {@link TwilioConfig} plays for Twilio: a
 * process-wide singleton the SDK's {@code FirebaseMessaging.getInstance()}
 * call reads implicitly (see {@code FcmPushSender}), so this has to run
 * before {@code FcmPushProvider}'s first send, not per-call.
 * <p>
 * <b>Gated behind {@code app.notification.push.enabled} (default
 * {@code false})</b> — see {@code FcmPushProvider}'s own class Javadoc for
 * the full reasoning. This is the class that reasoning is actually about:
 * without this gate, EVERY environment running this service — not just
 * production — would need real FCM service-account credentials configured
 * just to start the application, for a channel that can't do anything
 * useful yet regardless of whether it's enabled (customer-service doesn't
 * expose a push token to send to). {@code TwilioConfig} has no such gate
 * because SMS is fully live, not a stub — that's the actual difference
 * between these two classes, not a style choice.
 * <p>
 * {@code service-account-json-base64} is the full FCM service account key
 * (the JSON file downloadable from Firebase console → Project Settings →
 * Service Accounts), Base64-encoded — the standard way to pass a
 * multi-line credentials file through a single environment variable
 * without a mounted file, same idea as how this service already handles
 * other secrets. No safe default, same reasoning as
 * {@code app.notification.sms.account-sid}/{@code auth-token} — but
 * because this whole class is conditional, an unconfigured deployment with
 * the feature disabled never evaluates this {@code @Value} at all, unlike
 * Twilio's credentials which always would.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class FcmConfig {

    public FcmConfig(@Value("${app.notification.push.service-account-json-base64}") String serviceAccountJsonBase64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(serviceAccountJsonBase64);
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Failed to initialize FirebaseApp from app.notification.push.service-account-json-base64", ex);
        }
    }
}
