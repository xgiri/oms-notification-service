package com.giri.oms.notification.service;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationType;

public interface NotificationPreferenceService {

    /**
     * Absence of a stored preference row means opted-in — see
     * NotificationPreference's own Javadoc for why that's the right default
     * and why it matters that transactional types stay largely
     * un-opt-outable regardless.
     */
    boolean isOptedIn(Long customerId, NotificationType type, NotificationChannel channel);

    /**
     * Backs the unsubscribe endpoint — see security.SecurityConfig's own
     * note on why that endpoint is deliberately NOT JWT-authenticated
     * (unsubscribing must work even if the rest of the system, including
     * whatever issued the recipient's token, is down). Idempotent — opting
     * out twice is a no-op, not an error.
     */
    void optOut(Long customerId, NotificationType type, NotificationChannel channel);
}
