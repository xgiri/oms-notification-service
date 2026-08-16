package com.giri.oms.notification.dto;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;

import java.time.LocalDateTime;

/**
 * Delivery-history read model — see notification.controller. Deliberately
 * exposes recipientAddress (denormalized on the entity at send time, see
 * Notification's own Javadoc) rather than re-resolving it from
 * CustomerClient on every read.
 */
public record NotificationResponse(
        Long id,
        NotificationType type,
        NotificationChannel channel,
        NotificationStatus status,
        Long customerId,
        String recipientAddress,
        Long orderId,
        String providerName,
        LocalDateTime sentAt,
        int retryCount,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
