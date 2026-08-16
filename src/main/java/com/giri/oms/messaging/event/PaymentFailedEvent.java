package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off oms-main's order-events topic for a
 * PaymentFailed event — see PaymentConfirmedEvent's Javadoc for why this
 * isn't oms-main's own class, and why it's orderId-only (same customerId
 * gap as every other event this service consumes; resolved via OrderClient
 * in PaymentNotificationConsumer).
 */
public record PaymentFailedEvent(
        UUID eventId,
        Long orderId,
        LocalDateTime occurredAt
) {
}
