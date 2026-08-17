package com.giri.oms.notification.consumer;

import com.giri.oms.messaging.event.CustomerCreatedEvent;
import com.giri.oms.messaging.event.EventType;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.service.NotificationService;
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
 * Phase 3's last remaining event type: reacts to CustomerCreated with a
 * welcome email. Reads customer-service's {@code oms.customer.events}
 * topic — a topic this service has never consumed before, so this is the
 * first listener on it, in its own dedicated consumer group
 * ({@code app.kafka.consumer.customer-group-id}) — see that property's own
 * comment in application.properties for why it gets one even though
 * nothing else here reads this topic yet.
 * <p>
 * <b>No OrderClient/CustomerClient lookup needed to find out WHO to
 * notify</b> — unlike every order/payment event this service consumes,
 * {@link CustomerCreatedEvent} already carries {@code customerId} directly
 * (it's the event's own aggregate, not a referenced one — see that
 * record's own Javadoc). {@code orderId} is passed as {@code null} to
 * {@link NotificationService#processEvent} for the same reason
 * {@code Notification.orderId} is nullable in the first place: a welcome
 * email isn't about any order.
 * <p>
 * {@link NotificationService#processEvent} still makes its own
 * {@code CustomerClient} call internally to resolve the recipient address
 * — see that method's own Javadoc — even though {@link CustomerCreatedEvent}
 * already carries {@code email}. That's a real, known redundant network
 * hop (the email this consumer already has in hand gets thrown away and
 * re-fetched), traded deliberately for NOT special-casing
 * {@code processEvent}'s signature for one caller — every other consumer
 * in this service resolves the recipient exactly this way, and Phase 4's
 * SMS fan-out already needs a full {@code CustomerClientResponse} (for
 * {@code phone}, which this event doesn't carry at all), so this call
 * would still be needed for the SMS channel regardless. Worth revisiting
 * only if CustomerClient's call volume becomes a measured problem — same
 * "don't build it preemptively" guidance the plan itself gives for §3's
 * option (b).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.process.role", havingValue = "worker", matchIfMissing = true)
@RequiredArgsConstructor
public class CustomerWelcomeConsumer {

    private final NotificationService notificationService;
    private final JsonMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.customer-events}",
            groupId = "${app.kafka.consumer.customer-group-id}")
    public void onMessage(ConsumerRecord<String, String> record, @Header("eventType") String eventType) {
        if (EventType.CUSTOMER_CREATED.equals(eventType)) {
            handleCustomerCreated(record);
        } else {
            log.debug("Ignoring event of type {} on customer-events topic (key={})", eventType, record.key());
        }
    }

    private void handleCustomerCreated(ConsumerRecord<String, String> record) {
        CustomerCreatedEvent event = readEvent(record.value(), CustomerCreatedEvent.class);
        log.debug("Received CustomerCreated event id={} for customer id={}", event.eventId(), event.customerId());

        notificationService.processEvent(
                event.eventId(),
                NotificationType.CUSTOMER_WELCOME,
                event.customerId(),
                null,
                Map.of());
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
