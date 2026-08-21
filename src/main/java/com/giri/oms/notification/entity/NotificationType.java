package com.giri.oms.notification.entity;

/**
 * The full notification-type catalog, even though Phase 1 (see this
 * service's README) only actually wires ORDER_CONFIRMED end to end — same
 * reasoning as ShipmentStatus's full enum existing in shipment-service
 * before every transition had a caller. Each type maps to one template per
 * (channel, locale) — see {@code src/main/resources/templates}.
 * <p>
 * {@code transactional} exists here, not as a later retrofit, because it's
 * a legal distinction (CAN-SPAM/GDPR), not just a product one: a
 * transactional notification ("your order shipped") generally can't be
 * opted out of the same way a marketing one can.
 * <p>
 * As of this classification pass, every order/payment/shipment type stays
 * {@code true} — each is a status update tied to a specific transaction the
 * customer initiated, matching CAN-SPAM's own carve-out for transactional
 * or relationship messages. {@link #CUSTOMER_WELCOME} is the one exception,
 * now {@code false}: it isn't tied to any specific transaction (it fires
 * once, at signup) and is the conventional place a catalog like this one
 * draws the opt-out line. This still isn't a substitute for actual legal
 * sign-off before relying on it in a real deployment — it's the common
 * industry-standard reading, applied here as a starting position.
 */
public enum NotificationType {
    ORDER_CONFIRMED(true),
    ORDER_CANCELLED(true),
    PAYMENT_CONFIRMED(true),
    PAYMENT_FAILED(true),
    SHIPMENT_SHIPPED(true),
    SHIPMENT_DELIVERED(true),
    SHIPMENT_RETURNED(true),
    CUSTOMER_WELCOME(false);

    private final boolean transactional;

    NotificationType(boolean transactional) {
        this.transactional = transactional;
    }

    public boolean isTransactional() {
        return transactional;
    }
}
