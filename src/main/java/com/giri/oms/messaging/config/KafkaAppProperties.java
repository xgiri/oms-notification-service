package com.giri.oms.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Three topics as of Phase 3 (now complete): order-events (Phase 1),
 * customer-events (CustomerWelcome), and shipment-events (the last
 * remaining event family — ShipmentShipped/Delivered/Returned). No
 * producer topic here (unlike shipment-service's own Topics record) since
 * this service has no outbox/producer role — see notification.service's
 * package-info.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record KafkaAppProperties(Topics topics) {

    public record Topics(String orderEvents, String customerEvents, String shipmentEvents) {
    }
}
