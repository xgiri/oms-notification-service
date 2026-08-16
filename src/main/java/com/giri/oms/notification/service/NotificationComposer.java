package com.giri.oms.notification.service;

import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.provider.NotificationRequest;

import java.util.Map;

/**
 * Renders a (notification type, locale) pair plus template variables into a
 * channel-agnostic {@link NotificationRequest}. One template per
 * (type, channel, locale) — see {@code src/main/resources/templates} and
 * NotificationComposerImpl's Javadoc for the naming convention. Locale is a
 * required parameter from Phase 1 even though only "en" templates exist
 * yet — see NotificationType's own reasoning for why retrofitting a
 * dimension later costs more than carrying it unused now.
 */
public interface NotificationComposer {

    NotificationRequest compose(NotificationType type, String locale, String recipientAddress, Map<String, Object> templateVariables);
}
