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
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One row per attempted send — not per logical "thing that happened", so a
 * FAILED notification that later succeeds on retry is one row transitioning
 * PENDING -> FAILED -> SENT (see NotificationRetryScheduler), not two rows.
 * customerId/orderId are plain Long columns, not JPA relations — this
 * service has no local Customer/Order table to join against (its own
 * dedicated database, same reasoning as every other service in this
 * system), and both are validated via CustomerClient at composition time,
 * not via a DB-level FK.
 */
@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private NotificationType type;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    // Denormalized at send time, not re-resolved from CustomerClient on every
    // read — this is what let the delivery-history endpoints
    // (notification.controller) show WHO a notification actually went to
    // even if that customer's email later changes, and what lets this
    // service survive customer-service being briefly unavailable when
    // someone's just trying to view history, not send anything new.
    @Column(nullable = false, length = 255)
    private String recipientAddress;

    // Nullable — set once the event that triggered this exists (every Phase 1
    // trigger is order-related); a future non-order-triggered notification
    // type (e.g. a standalone marketing send) would leave this null.
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "provider_name", length = 50)
    private String providerName;

    // The provider's own message id (e.g. SES's MessageId) — not this
    // entity's own id. Kept for cross-referencing a specific send against the
    // provider's own delivery/bounce webhooks or dashboard, once those exist.
    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
