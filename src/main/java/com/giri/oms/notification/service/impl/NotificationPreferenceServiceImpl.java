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
        // A transactional type ignores an opt-out entirely — see
        // NotificationType's own Javadoc for the classification reasoning
        // and its own caveat that this isn't a substitute for actual legal
        // sign-off. CUSTOMER_WELCOME is the one type that's genuinely
        // non-transactional today, so it's also the one type that ever
        // reaches the stored-preference check below.
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
