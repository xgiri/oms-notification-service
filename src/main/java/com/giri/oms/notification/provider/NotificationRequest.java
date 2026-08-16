package com.giri.oms.notification.provider;

/**
 * The already-composed, channel-agnostic payload NotificationComposer
 * produces and every NotificationProvider implementation consumes. Not
 * every field is meaningful to every channel (an SMS provider ignores
 * {@code subject}/{@code htmlBody} and uses {@code textBody} only) —
 * deliberately one shared shape rather than a channel-specific request type
 * per provider, since NotificationServiceImpl's orchestration
 * (compose -> send -> record) shouldn't need to know which channel it's
 * looking at to call either step.
 */
public record NotificationRequest(
        String recipientAddress,
        String subject,
        String htmlBody,
        String textBody
) {
}
