package com.giri.oms.notification.provider;

/**
 * What a NotificationProvider hands back after a send attempt.
 * {@code providerMessageId} is nullable — not every provider necessarily
 * returns one (SmtpEmailProvider's underlying JavaMailSender doesn't), and a
 * caller shouldn't have to know per-provider whether to expect one.
 */
public record ProviderResult(
        boolean success,
        String providerMessageId,
        String errorMessage
) {

    public static ProviderResult success(String providerMessageId) {
        return new ProviderResult(true, providerMessageId, null);
    }

    public static ProviderResult failure(String errorMessage) {
        return new ProviderResult(false, null, errorMessage);
    }
}
