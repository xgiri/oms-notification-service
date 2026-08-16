package com.giri.oms.messaging.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off oms-main's order-events topic for a
 * PaymentConfirmed event — not a shared class with oms-main's own
 * PaymentConfirmedEvent, same "separately deployed services don't share
 * classes" reasoning as every other consumer in this system (see
 * OrderConfirmedEvent's own Javadoc).
 * <p>
 * {@code amount} is carried through (oms-main's wire event has it) so the
 * confirmation email can state what was charged — {@code transactionReference}
 * and {@code paymentId} are deliberately left out, same "only what this
 * caller needs" philosophy as OrderClientResponse, since no template
 * variable or dispatch decision here needs them today.
 * <p>
 * Same customerId gap as OrderConfirmedEvent (see that class's Javadoc) —
 * this event is orderId-only, so PaymentNotificationConsumer resolves the
 * recipient via OrderClient exactly the way OrderNotificationConsumer
 * does.
 */
public record PaymentConfirmedEvent(
        UUID eventId,
        Long orderId,
        BigDecimal amount,
        LocalDateTime occurredAt
) {
}
