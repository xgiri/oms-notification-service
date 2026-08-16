package com.giri.oms.notification.service;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.provider.NotificationRequest;

import java.util.Map;

/**
 * Renders a (notification type, channel, locale) triple plus template
 * variables into a channel-agnostic {@link NotificationRequest}. One
 * template set per (type, channel, locale) — see
 * {@code src/main/resources/templates} and NotificationComposerImpl's
 * Javadoc for the naming convention and for which fields of the returned
 * {@link NotificationRequest} are actually populated per channel (EMAIL
 * populates both {@code htmlBody}/{@code textBody}; SMS populates
 * {@code textBody} only — see NotificationRequest's own Javadoc). Locale is
 * a required parameter from Phase 1 even though only "en" templates exist
 * yet — see NotificationType's own reasoning for why retrofitting a
 * dimension later costs more than carrying it unused now. {@code channel}
 * became required in Phase 4 (SMS) — see the class Javadoc on why this
 * wasn't added speculatively back in Phase 1.
 */
public interface NotificationComposer {

    NotificationRequest compose(NotificationType type, NotificationChannel channel, String locale,
                                 String recipientAddress, Map<String, Object> templateVariables);
}
