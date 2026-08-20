package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off shipment-service's oms.shipment.events
 * topic for a ShipmentShipped event — see OrderConfirmedEvent's Javadoc for
 * why this isn't shipment-service's own class. Same customerId gap as
 * every order/payment event this service consumes (orderId-only; resolved
 * via OrderClient in ShipmentNotificationConsumer).
 * <p>
 * {@code trackingNumber} is carried through — shipment-service's own wire
 * event has it, and it's genuinely useful in a "your order shipped" email
 * (see the plan's own §1 example: "Tracking number email"). {@code shippedAt}
 * is deliberately left out — {@code occurredAt} already exists on every
 * event this service consumes and nothing here needs the more precise
 * distinction shipment-service's own entity makes between the two.
 */
public record ShipmentShippedEvent(
        UUID eventId,
        Long orderId,
        String trackingNumber,
        LocalDateTime occurredAt
) {
}
