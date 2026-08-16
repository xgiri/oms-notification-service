package com.giri.oms.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer-only — unlike shipment-service (producer AND consumer), this
 * service has no outbox/producer role in Phase 1 (see notification.service's
 * package-info). order-events is a topic this service doesn't own (oms-main
 * does) but reads from as its own independent consumer group — declaring it
 * here too is idempotent/defensive, same reasoning as shipment-service's
 * own copy of this class.
 */
@Configuration
public class KafkaConfig {

    @Bean
    NewTopic orderEventsTopic(KafkaAppProperties kafkaAppProperties) {
        return TopicBuilder.name(kafkaAppProperties.topics().orderEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }

    // DeadLetterPublishingRecoverer below defaults to "<source-topic>.DLT"
    // when no custom destination resolver is configured — this service's
    // one consumer only ever reads oms.order.events, so that's the only DLT
    // topic there is to pre-declare.
    @Bean
    NewTopic orderEventsDeadLetterTopic(KafkaAppProperties kafkaAppProperties) {
        return TopicBuilder.name(kafkaAppProperties.topics().orderEvents() + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Applies to the one @KafkaListener in this app
     * (OrderConfirmedNotificationConsumer) — mirrors oms-main's/
     * shipment-service's own kafkaErrorHandler bean exactly. Retries a
     * failure 3 times, 2 seconds apart, to ride out a brief CustomerClient/
     * OrderClient blip without holding up the partition for too long; if
     * still failing after that, republishes the raw record to
     * "oms.order.events.DLT" instead of blocking the partition forever or
     * silently dropping the message.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));
    }
}
