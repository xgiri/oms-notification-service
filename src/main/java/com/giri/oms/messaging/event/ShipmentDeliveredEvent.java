package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off shipment-service's oms.shipment.events
 * topic for a ShipmentDelivered event — see ShipmentShippedEvent's Javadoc
 * for the shared reasoning (customerId gap, why not shipment-service's own
 * class). No trackingNumber here — a delivery-confirmation email doesn't
 * need it the way a shipped-notice does.
 */
public record ShipmentDeliveredEvent(
        UUID eventId,
        Long orderId,
        LocalDateTime occurredAt
) {
}
