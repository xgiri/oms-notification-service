package com.giri.oms.notification.consumer;

import com.giri.oms.messaging.event.EventType;
import com.giri.oms.messaging.event.OrderCancelledEvent;
import com.giri.oms.messaging.event.OrderConfirmedEvent;
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
 * The order-lifecycle notification consumer: OrderConfirmed (Phase 1) and,
 * as of Phase 3, OrderCancelled — both handled in this ONE
 * {@code @KafkaListener} method, in this service's default consumer group
 * ({@code oms-notification-service}), same reasoning as oms-main's own
 * OrderSagaEventConsumer grouping every order-lifecycle event it cares
 * about into one listener/group rather than one per event type: these are
 * the same logical concern (order-status emails), so they share a group
 * rather than each getting their own the way the unrelated
 * PaymentNotificationConsumer concern does (see that class's own Javadoc
 * for when a NEW group is actually warranted — a genuinely different
 * consumer concern, not just another event type on the same topic).
 * <p>
 * <b>Renamed from {@code OrderConfirmedNotificationConsumer}</b> — same
 * class, same consumer group (unchanged, so no group-id migration/rebalance
 * concern), just renamed to reflect that it now owns order-lifecycle
 * notifications generally rather than only OrderConfirmed.
 * <p>
 * Two synchronous calls happen per event here, and their failure is handled
 * DIFFERENTLY on purpose:
 * <ul>
 *   <li>{@link OrderClient#getOrder} / {@link com.giri.oms.customerclient.service.CustomerClient#getCustomer}
 *   (the latter called inside NotificationServiceImpl) can throw
 *   ...ServiceUnavailableException — deliberately left UNCAUGHT here, so it
 *   propagates out of this listener method. Spring Kafka's error handling
 *   (KafkaConfig.kafkaErrorHandler — 3 retries, 2s apart, then DLT) owns
 *   the retry decision for "a dependency was briefly down", not this
 *   class.</li>
 *   <li>A failed SEND (the provider itself failing) is NOT an exception at
 *   all by the time it reaches this method — see NotificationServiceImpl's
 *   own Javadoc for why that's recorded as a FAILED Notification row
 *   instead, left for NotificationRetryScheduler, rather than failing this
 *   Kafka message.</li>
 * </ul>
 * <p>
 * Guarded by app.process.role, same convention as every other consumer in
 * this system — see application.properties.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.process.role", havingValue = "worker", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderNotificationConsumer {

    private final NotificationService notificationService;
    private final OrderClient orderClient;
    private final JsonMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.order-events}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, String> record, @Header("eventType") String eventType) {
        if (EventType.ORDER_CONFIRMED.equals(eventType)) {
            handleOrderConfirmed(record);
        } else if (EventType.ORDER_CANCELLED.equals(eventType)) {
            handleOrderCancelled(record);
        } else {
            log.debug("Ignoring event of type {} on order-events topic (key={})", eventType, record.key());
        }
    }

    private void handleOrderConfirmed(ConsumerRecord<String, String> record) {
        OrderConfirmedEvent event = readEvent(record.value(), OrderConfirmedEvent.class);
        log.debug("Received OrderConfirmed event id={} for order id={}", event.eventId(), event.orderId());

        // See OrderConfirmedEvent's own Javadoc — this call exists ONLY
        // because the event doesn't carry customerId itself.
        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.ORDER_CONFIRMED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId()),
                record.timestamp());
    }

    private void handleOrderCancelled(ConsumerRecord<String, String> record) {
        OrderCancelledEvent event = readEvent(record.value(), OrderCancelledEvent.class);
        log.debug("Received OrderCancelled event id={} for order id={}", event.eventId(), event.orderId());

        // Same customerId gap as OrderConfirmedEvent — see that class's
        // Javadoc. OrderCancelled can be reached either via a manual
        // cancel or via PaymentFailed's compensating flow (see oms-main's
        // OrderCancelledEvent Javadoc); either way this consumer doesn't
        // care which path got it here, only that the order is cancelled.
        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.ORDER_CANCELLED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId()),
                record.timestamp());
    }

    /**
     * Deserializes one event payload, tolerating unknown JSON properties —
     * see oms-main's docs/event-schema-versioning.md. Deliberately
     * overridden per-read via {@code ObjectReader.without(...)} rather than
     * on a separate globally injected JsonMapper bean — see
     * shipment-service's equivalent consumers for the full reasoning (keeps
     * this service's default JsonMapper, used for REST bodies, on Jackson's
     * normal strict behavior).
     */
    private <T> T readEvent(String json, Class<T> eventClass) {
        return objectMapper.readerFor(eventClass)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);
    }
}
