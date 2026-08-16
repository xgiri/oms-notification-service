package com.giri.oms.messaging.event;

/**
 * Trimmed to the event types this service actually consumes — same
 * reasoning as shipment-service's own EventType. Phase 1 wired
 * ORDER_CONFIRMED; Phase 3 has since added ORDER_CANCELLED (see
 * OrderNotificationConsumer) and PAYMENT_CONFIRMED/PAYMENT_FAILED (see
 * PaymentNotificationConsumer). The remaining constants are listed now so
 * wiring up the SHIPMENT_ constants or CUSTOMER_CREATED later is a new
 * @KafkaListener method, not a rediscovery of which string values oms-main
 * actually publishes.
 */
public final class EventType {

    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String PAYMENT_CONFIRMED = "PaymentConfirmed";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String SHIPMENT_SHIPPED = "ShipmentShipped";
    public static final String SHIPMENT_DELIVERED = "ShipmentDelivered";
    public static final String SHIPMENT_RETURNED = "ShipmentReturned";
    public static final String CUSTOMER_CREATED = "CustomerCreated";

    private EventType() {
    }
}