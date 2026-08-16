package com.giri.oms.notification.entity;

import com.giri.oms.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-(customer, notification type, channel) opt-in state. Absence of a row
 * means opted-in — see NotificationPreferenceService.isOptedIn — so a brand
 * new customer who's never touched their preferences still gets
 * transactional notifications by default, which is both the reasonable
 * default and, for {@code transactional} types (see NotificationType),
 * often not something a customer even gets to opt out of at all (CAN-SPAM/
 * GDPR — see that enum's Javadoc).
 */
@Getter
@Setter
@Entity
@Table(name = "notification_preferences", uniqueConstraints = {
        @UniqueConstraint(name = "uq_notification_preferences_customer_type_channel",
                columnNames = {"customer_id", "notification_type", "channel"})
})
public class NotificationPreference extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "notification_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private NotificationType notificationType;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    @Column(name = "opted_in", nullable = false)
    private boolean optedIn = true;
}
