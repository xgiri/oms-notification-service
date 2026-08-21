package com.giri.oms.notification.provider;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The only class in this service that actually calls the firebase-admin
 * SDK — see {@link PushSender}'s own Javadoc for why this exists as a
 * separate class from {@link FcmPushProvider}, same split as
 * {@code TwilioSmsSender}/{@code TwilioSmsProvider}.
 * <p>
 * {@code com.google.firebase.messaging.Notification} (the SDK's own
 * builder for a title/body pair) is deliberately fully-qualified below
 * rather than imported — this class lives under
 * {@code com.giri.oms.notification.provider}, one package below
 * {@code com.giri.oms.notification.entity.Notification}; an unqualified
 * import of the SDK's own same-simple-name {@code Notification} here is
 * exactly the kind of thing that becomes a real collision the moment a
 * future edit in this class needs the entity type too — not worth that
 * risk for a type used exactly once.
 * <p>
 * Gated behind {@code app.notification.push.enabled} — see
 * {@link FcmPushProvider}'s own Javadoc for the full reasoning. Not
 * because THIS class would misbehave if instantiated early (it doesn't
 * touch {@code FirebaseMessaging.getInstance()} until {@link #send} is
 * actually called) — gated for consistency with {@code FcmConfig}, so
 * there's no live-but-pointless bean sitting in the context while the
 * feature is off.
 */
@Component
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class FcmPushSender implements PushSender {

    @Override
    public String send(String token, String title, String body) throws FirebaseMessagingException {
        com.google.firebase.messaging.Message message = com.google.firebase.messaging.Message.builder()
                .setToken(token)
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();
        return FirebaseMessaging.getInstance().send(message);
    }
}
