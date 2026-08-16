package com.giri.oms.notification.service.impl;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationPreference;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.repository.NotificationPreferenceRepository;
import com.giri.oms.notification.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;

    @Override
    public boolean isOptedIn(Long customerId, NotificationType type, NotificationChannel channel) {
        // A transactional type ignores an opt-out entirely for now (see
        // NotificationType's own Javadoc) — this is a placeholder legal
        // stance, not a researched one; get real legal sign-off on which
        // transactional types genuinely can't be opted out of vs. which
        // merely default to opted-in, before relying on this in a real
        // deployment. Marketing (non-transactional) types, once any exist,
        // would skip this early return and always defer to the stored
        // preference below.
        if (type.isTransactional()) {
            return true;
        }

        return preferenceRepository.findByCustomerIdAndNotificationTypeAndChannel(customerId, type, channel)
                .map(NotificationPreference::isOptedIn)
                .orElse(true);
    }

    @Override
    @Transactional
    public void optOut(Long customerId, NotificationType type, NotificationChannel channel) {
        NotificationPreference preference = preferenceRepository
                .findByCustomerIdAndNotificationTypeAndChannel(customerId, type, channel)
                .orElseGet(() -> {
                    NotificationPreference newPreference = new NotificationPreference();
                    newPreference.setCustomerId(customerId);
                    newPreference.setNotificationType(type);
                    newPreference.setChannel(channel);
                    return newPreference;
                });
        preference.setOptedIn(false);
        preferenceRepository.save(preference);
    }
}
