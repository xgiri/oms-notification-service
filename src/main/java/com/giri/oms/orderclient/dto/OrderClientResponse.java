package com.giri.oms.orderclient.dto;

/**
 * Deliberately not oms-main's own OrderResponse — same "only what this
 * caller needs" philosophy as every other *ClientResponse in this system.
 * The one reason this service calls oms-main's order endpoint at all: an
 * OrderConfirmed/OrderCancelled event carries {@code orderId} but not
 * {@code customerId} (see messaging.event.OrderConfirmedEvent's Javadoc for
 * why that's a real gap, not a design choice this client works around
 * happily) — this is the lookup that fills it in.
 */
public record OrderClientResponse(
        Long id,
        Long customerId
) {
}
