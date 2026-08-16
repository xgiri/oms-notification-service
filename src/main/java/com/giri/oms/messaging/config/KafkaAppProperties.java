package com.giri.oms.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Consumer-only — just the one topic this service reads from. No producer
 * topic here (unlike shipment-service's Topics record) since this service
 * has no outbox/producer role in Phase 1 — see
 * notification.service's package-info.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record KafkaAppProperties(Topics topics) {

    public record Topics(String orderEvents) {
    }
}
