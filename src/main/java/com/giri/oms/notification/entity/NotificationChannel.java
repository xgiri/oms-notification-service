package com.giri.oms.notification.entity;

/**
 * Phase 1 has exactly one implementation (see notification.provider —
 * SmtpEmailProvider) but the channel concept exists from day one, same
 * reasoning as ShippingCarrier existing before every carrier had real
 * integration — retrofitting a channel dimension onto the schema/API later
 * would touch far more than adding a provider implementation does.
 */
public enum NotificationChannel {
    EMAIL,
    SMS,
    PUSH
}
