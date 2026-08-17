package com.giri.oms.messaging.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The shape this service reads off customer-service's oms.customer.events
 * topic for a CustomerCreated event — see OrderConfirmedEvent's Javadoc for
 * why this isn't customer-service's own class.
 * <p>
 * Unlike every order/payment event this service consumes, there is NO
 * customerId gap here — {@code email} rides along on the event itself
 * (customer-service's own CustomerCreatedEvent carries it, since Customer
 * IS the aggregate here, not a referenced one), so CustomerWelcomeConsumer
 * needs no OrderClient-style synchronous lookup just to find out who to
 * notify.
 * <p>
 * That said, {@code email} is deliberately NOT threaded through to
 * NotificationServiceImpl#processEvent — its signature resolves the
 * recipient address itself via CustomerClient for every caller, the same
 * way whether or not the triggering event happens to already carry it. See
 * CustomerWelcomeConsumer's own Javadoc for why duplicating that resolution
 * here wasn't worth a special-cased processEvent overload for one consumer.
 */
public record CustomerCreatedEvent(
        UUID eventId,
        Long customerId,
        String email,
        LocalDateTime occurredAt
) {
}
