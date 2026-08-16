package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off oms-main's order-events topic — not a
 * shared class with oms-main's own OrderConfirmedEvent, same "separately
 * deployed services don't share classes" reasoning as every other consumer
 * in this system.
 * <p>
 * <b>Deliberately does NOT model a customerId field — because the real
 * wire event doesn't carry one.</b> This is the one genuine event-schema
 * gap this whole service design ran into: a notification consumer needs to
 * know WHO to notify, but OrderConfirmedEvent (unlike OrderCreatedEvent,
 * which does carry customerId) only has {@code orderId}. Phase 1's
 * workaround is OrderClient — an extra synchronous hop to oms-main's order
 * endpoint on every single notification, purely to resolve customerId.
 * <p>
 * <b>The recommended long-term fix is adding {@code customerId} (and
 * possibly the recipient's denormalized name) to OrderConfirmedEvent
 * itself, additively</b> — squarely inside this system's own documented
 * compatibility policy (docs/event-schema-versioning.md in oms-main:
 * additive fields are safe, no schemaVersion bump needed). That would let
 * this service drop OrderClient entirely for this path: no extra network
 * hop, no extra circuit breaker to misconfigure, one less thing that can be
 * slow or down. Same gap likely exists on ShipmentShippedEvent/
 * ShipmentDeliveredEvent/ShipmentReturnedEvent (all orderId-only, no
 * customerId) for whenever this service's Phase 3 wires those up too — worth
 * fixing once, for all of them, rather than adding a ShipmentClient/
 * repeating this same OrderClient workaround per event type.
 */
public record OrderConfirmedEvent(
        UUID eventId,
        Long orderId,
        LocalDateTime occurredAt
) {
}
