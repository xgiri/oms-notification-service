package com.giri.oms.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The single most important table in this service — see this service's own
 * README on why idempotency matters more here than for any other consumer
 * in this system (a duplicate inventory reservation is a bug nobody
 * notices; a duplicate "your order shipped" email is the kind customers
 * complain about). One row per (event id, notification type) actually
 * processed, inserted in the SAME transaction as the notification's own
 * PENDING/SENT row — see NotificationServiceImpl.processEvent. A
 * redelivered Kafka message hits this table's unique constraint and is
 * treated as "already handled", not reprocessed.
 * <p>
 * Keyed on (event_id, notification_type) rather than event_id alone,
 * because a single event can legitimately fan out to more than one
 * notification type in a future phase (e.g. OrderConfirmed triggering both
 * an email AND, once SMS ships, an SMS) — event_id alone would incorrectly
 * treat the second as a duplicate of the first.
 */
@Getter
@Setter
@Entity
@Table(name = "processed_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_processed_events_event_type", columnNames = {"event_id", "notification_type"})
})
public class ProcessedEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "notification_type", nullable = false, length = 30)
    private String notificationType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime processedAt;
}
