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
 * service has no outbox/producer role (see notification.service's
 * package-info). order-events and, as of Phase 3, customer-events are
 * topics this service doesn't own (oms-main and customer-service do,
 * respectively) but reads from as its own independent consumer group(s) —
 * declaring them here too is idempotent/defensive, same reasoning as
 * shipment-service's own copy of this class.
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
    // when no custom destination resolver is configured — pre-declaring
    // both this service's listened-to topics' own .DLT topics up front.
    @Bean
    NewTopic orderEventsDeadLetterTopic(KafkaAppProperties kafkaAppProperties) {
        return TopicBuilder.name(kafkaAppProperties.topics().orderEvents() + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    // customer-service's own KafkaConfig notes oms.customer.events is a
    // brand-new topic with no consumer yet as of that service's Stage 1 —
    // CustomerWelcomeConsumer is the first. Declaring it here (rather than
    // relying solely on customer-service's own declaration) is the same
    // idempotent/defensive posture as orderEventsTopic above.
    @Bean
    NewTopic customerEventsTopic(KafkaAppProperties kafkaAppProperties) {
        return TopicBuilder.name(kafkaAppProperties.topics().customerEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic customerEventsDeadLetterTopic(KafkaAppProperties kafkaAppProperties) {
        return TopicBuilder.name(kafkaAppProperties.topics().customerEvents() + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Applies to every @KafkaListener in this app (OrderNotificationConsumer,
     * PaymentNotificationConsumer, CustomerWelcomeConsumer as of Phase 3) —
     * Spring Boot's Kafka autoconfiguration wires any single
     * CommonErrorHandler bean into the default listener container factory
     * automatically, so every listener shares this one bean without any
     * needing its own — mirrors oms-main's/shipment-service's own
     * kafkaErrorHandler bean exactly. Retries a failure 3 times, 2 seconds
     * apart, to ride out a brief CustomerClient/OrderClient blip without
     * holding up the partition for too long; if still failing after that,
     * republishes the raw record to "<source-topic>.DLT" instead of
     * blocking the partition forever or silently dropping the message.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));
    }
}
