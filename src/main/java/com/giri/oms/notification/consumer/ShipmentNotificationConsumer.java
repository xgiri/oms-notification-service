package com.giri.oms.notification.consumer;

import com.giri.oms.messaging.event.EventType;
import com.giri.oms.messaging.event.ShipmentDeliveredEvent;
import com.giri.oms.messaging.event.ShipmentReturnedEvent;
import com.giri.oms.messaging.event.ShipmentShippedEvent;
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
 * Phase 3's last remaining event family: ShipmentShipped/Delivered/Returned,
 * all handled in this ONE {@code @KafkaListener} method, in this consumer's
 * own dedicated group ({@code app.kafka.consumer.shipment-group-id}) — same
 * "one logical concern, one group, if/else dispatch" shape as
 * OrderNotificationConsumer (which groups OrderConfirmed/OrderCancelled the
 * same way), not PaymentNotificationConsumer's "different concern, needs
 * its own group to avoid partition contention" situation — this doesn't
 * apply here regardless, since shipment-events is its own topic with no
 * other listener on it, but the group is still dedicated rather than
 * reusing the default one, for the same consistency reasoning as
 * CustomerWelcomeConsumer's own group.
 * <p>
 * Same customerId gap, same OrderClient workaround as every other
 * order/payment/shipment event this service consumes — see
 * ShipmentShippedEvent's own Javadoc.
 * <p>
 * Error handling and idempotency follow OrderNotificationConsumer's own
 * Javadoc exactly: OrderClient failures propagate uncaught (owned by
 * KafkaConfig's retry-then-DLT error handler), and a failed SEND is
 * recorded as a FAILED Notification row (picked up later by
 * NotificationRetryScheduler) rather than failing this Kafka message.
 * <p>
 * Guarded by app.process.role, same convention as every other consumer in
 * this system.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.process.role", havingValue = "worker", matchIfMissing = true)
@RequiredArgsConstructor
public class ShipmentNotificationConsumer {

    private final NotificationService notificationService;
    private final OrderClient orderClient;
    private final JsonMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.shipment-events}",
            groupId = "${app.kafka.consumer.shipment-group-id}")
    public void onMessage(ConsumerRecord<String, String> record, @Header("eventType") String eventType) {
        if (EventType.SHIPMENT_SHIPPED.equals(eventType)) {
            handleShipmentShipped(record);
        } else if (EventType.SHIPMENT_DELIVERED.equals(eventType)) {
            handleShipmentDelivered(record);
        } else if (EventType.SHIPMENT_RETURNED.equals(eventType)) {
            handleShipmentReturned(record);
        } else {
            log.debug("Ignoring event of type {} on shipment-events topic (key={})", eventType, record.key());
        }
    }

    private void handleShipmentShipped(ConsumerRecord<String, String> record) {
        ShipmentShippedEvent event = readEvent(record.value(), ShipmentShippedEvent.class);
        log.debug("Received ShipmentShipped event id={} for order id={}", event.eventId(), event.orderId());

        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.SHIPMENT_SHIPPED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId(), "trackingNumber", event.trackingNumber()),
                record.timestamp());
    }

    private void handleShipmentDelivered(ConsumerRecord<String, String> record) {
        ShipmentDeliveredEvent event = readEvent(record.value(), ShipmentDeliveredEvent.class);
        log.debug("Received ShipmentDelivered event id={} for order id={}", event.eventId(), event.orderId());

        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.SHIPMENT_DELIVERED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId()),
                record.timestamp());
    }

    private void handleShipmentReturned(ConsumerRecord<String, String> record) {
        ShipmentReturnedEvent event = readEvent(record.value(), ShipmentReturnedEvent.class);
        log.debug("Received ShipmentReturned event id={} for order id={}", event.eventId(), event.orderId());

        OrderClientResponse order = orderClient.getOrder(event.orderId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.SHIPMENT_RETURNED,
                order.customerId(),
                event.orderId(),
                Map.of("orderId", event.orderId()),
                record.timestamp());
    }

    /**
     * Deserializes one event payload, tolerating unknown JSON properties —
     * same per-read override as every other consumer in this service (see
     * OrderNotificationConsumer's own Javadoc for the fuller reasoning).
     */
    private <T> T readEvent(String json, Class<T> eventClass) {
        return objectMapper.readerFor(eventClass)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);
    }
}
