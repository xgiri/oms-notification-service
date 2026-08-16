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
 * opted out of the same way a marketing one can. Every type here is
 * transactional today — there are no marketing notification types yet — but
 * the field exists so adding one doesn't mean redesigning
 * NotificationPreference's enforcement logic (see
 * NotificationPreferenceService) to discover this distinction for the first
 * time under time pressure.
 */
public enum NotificationType {
    ORDER_CONFIRMED(true),
    ORDER_CANCELLED(true),
    PAYMENT_CONFIRMED(true),
    PAYMENT_FAILED(true),
    SHIPMENT_SHIPPED(true),
    SHIPMENT_DELIVERED(true),
    SHIPMENT_RETURNED(true),
    CUSTOMER_WELCOME(true);

    private final boolean transactional;

    NotificationType(boolean transactional) {
        this.transactional = transactional;
    }

    public boolean isTransactional() {
        return transactional;
    }
}
