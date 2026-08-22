package com.giri.oms.notification.consumer;

import com.giri.oms.messaging.event.EventType;
import com.giri.oms.messaging.event.PaymentConfirmedEvent;
import com.giri.oms.messaging.event.PaymentFailedEvent;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.service.NotificationService;
import com.giri.oms.orderclient.dto.OrderClientResponse;
import com.giri.oms.orderclient.service.OrderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Phase 3's payment-lifecycle consumer: reacts to PaymentConfirmed/PaymentFailed
 * with a payment-receipt or payment-failed-alert email. Reads the same
 * {@code oms.order.events} topic as OrderNotificationConsumer, but
 * in its OWN consumer group ({@code app.kafka.consumer.payment-group-id}) —
 * same "one shared topic, independent groups per logical consumer" pattern
 * as oms-main's own OrderSagaEventConsumer/OrderCreatedInventoryConsumer
 * split, and for the same reason: sharing OrderNotificationConsumer's
 * group here would mean the two listeners compete for partitions instead of
 * each getting a full copy of the topic, and a PaymentConfirmed event landing
 * on a partition owned by the *other* listener's container would never reach
 * this handling code at all (see application.properties for the fuller
 * note on this group-id).
 * <p>
 * Both event types are handled in this ONE {@code @KafkaListener} method,
 * not two separate ones — same reasoning as OrderSagaEventConsumer's
 * if/else dispatch: two {@code @KafkaListener} methods here would themselves
 * be two more containers competing for partitions within this class's own
 * group, which is exactly the bug this class's own group-id split exists to
 * avoid one level up.
 * <p>
 * Error handling and idempotency follow OrderNotificationConsumer's
 * own Javadoc exactly: OrderClient/CustomerClient failures propagate
 * uncaught (owned by KafkaConfig's retry-then-DLT error handler), and a
 * failed SEND is recorded as a FAILED Notification row rather than failing
 * this Kafka message (see NotificationServiceImpl).
 * <p>
 * Guarded by app.process.role, same convention as every other consumer in
 * this system.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.process.role", havingValue = "worker", matchIfMissing = true)
@RequiredArgsConstructor
public class PaymentNotificationConsumer {

    private final NotificationService notificationService;
    private final OrderClient orderClient;
    private final JsonMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.order-events}",
            groupId = "${app.kafka.consumer.payment-group-id}")
    public void onMessage(ConsumerRecord<String, String> record, @Header("eventType") String eventType) {
        if (EventType.PAYMENT_CONFIRMED.equals(eventType)) {
            handlePaymentConfirmed(record);
        } else if (EventType.PAYMENT_FAILED.equals(eventType)) {
            handlePaymentFailed(record);
        } else {
            log.debug("Ignoring event of type {} on order-events topic (key={})", eventType, record.key());
        }
    }

    private void handlePaymentConfirmed(ConsumerRecord<String, String> record) {
        PaymentConfirmedEvent event = readEvent(record.value(), PaymentConfirmedEvent.class);
        log.debug("Received PaymentConfirmed event id={} for order id={}", event.eventId(), event.orderId());

        // See PaymentConfirmedEvent's own Javadoc — this call exists ONLY
        // because the event doesn't carry customerId itself.
        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.PAYMENT_CONFIRMED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId(), "amount", event.amount()),
                record.timestamp());
    }

    private void handlePaymentFailed(ConsumerRecord<String, String> record) {
        PaymentFailedEvent event = readEvent(record.value(), PaymentFailedEvent.class);
        log.debug("Received PaymentFailed event id={} for order id={}", event.eventId(), event.orderId());

        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.PAYMENT_FAILED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId()),
                record.timestamp());
    }

    /**
     * Deserializes one event payload, tolerating unknown JSON properties —
     * see oms-main's docs/event-schema-versioning.md. Same per-read
     * override as OrderNotificationConsumer, for the same reason
     * (keeps this service's default JsonMapper, used for REST bodies, on
     * Jackson's normal strict behavior).
     */
    private <T> T readEvent(String json, Class<T> eventClass) {
        return objectMapper.readerFor(eventClass)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);
    }
}
