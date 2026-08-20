package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off shipment-service's oms.shipment.events
 * topic for a ShipmentReturned event — see ShipmentShippedEvent's Javadoc
 * for the shared reasoning.
 */
public record ShipmentReturnedEvent(
        UUID eventId,
        Long orderId,
        LocalDateTime occurredAt
) {
}
