package com.giri.oms.notification.repository;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationPreference;
import com.giri.oms.notification.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    Optional<NotificationPreference> findByCustomerIdAndNotificationTypeAndChannel(
            Long customerId, NotificationType notificationType, NotificationChannel channel);

    List<NotificationPreference> findByCustomerId(Long customerId);
}
