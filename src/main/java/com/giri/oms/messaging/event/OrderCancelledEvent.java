package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off oms-main's order-events topic for an
 * OrderCancelled event — see OrderConfirmedEvent's Javadoc for why this
 * isn't oms-main's own class. Same customerId gap, same OrderClient
 * workaround (see OrderNotificationConsumer).
 * <p>
 * Deliberately carries no reason/cause field — oms-main's own
 * OrderCancelledEvent doesn't distinguish a manual cancel from
 * PaymentFailed's compensating flow on the wire, so this notification's
 * copy ("your order has been cancelled") is necessarily generic too. A
 * reason-specific email (e.g. distinguishing "payment failed" from "you
 * cancelled this") would need that distinction added to oms-main's event
 * first — not a gap this consumer can paper over on its own.
 */
public record OrderCancelledEvent(
        UUID eventId,
        Long orderId,
        LocalDateTime occurredAt
) {
}
