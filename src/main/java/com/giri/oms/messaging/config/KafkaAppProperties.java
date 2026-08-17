package com.giri.oms.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Two topics as of Phase 3: order-events (Phase 1) and customer-events
 * (the new CustomerWelcome consumer). No producer topic here (unlike
 * shipment-service's Topics record) since this service has no outbox/
 * producer role — see notification.service's package-info.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record KafkaAppProperties(Topics topics) {

    public record Topics(String orderEvents, String customerEvents) {
    }
}
