package com.giri.oms.notification.consumer;

import com.giri.oms.messaging.event.EventType;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.service.NotificationService;
import com.giri.oms.orderclient.dto.OrderClientResponse;
import com.giri.oms.orderclient.exception.OrderNotFoundException;
import com.giri.oms.orderclient.exception.OrderServiceUnavailableException;
import com.giri.oms.orderclient.service.OrderClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A REAL JsonMapper here, not a mock — same convention as
 * shipment-service's own consumer tests: constructing one is trivial and a
 * mocked deserializer would be pointless (it wouldn't actually exercise the
 * tolerant-deserialization behavior these tests care about). NotificationService
 * and OrderClient stay mocked — this class's own job is the dispatch/wiring
 * logic (ignore unknown event types, resolve customerId, pass the right
 * args through) for BOTH event types this consumer now handles, not either
 * dependency's own behavior.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotificationConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private OrderClient orderClient;

    private OrderNotificationConsumer consumer;

    private static final Long ORDER_ID = 100L;
    private static final Long CUSTOMER_ID = 7L;

    @BeforeEach
    void setUp() {
        consumer = new OrderNotificationConsumer(notificationService, orderClient, JsonMapper.builder().build());
    }

    @Test
    void onOrderConfirmed_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(orderConfirmedJson(eventId, ORDER_ID)), EventType.ORDER_CONFIRMED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.ORDER_CONFIRMED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture());

        assertThat(templateVarsCaptor.getValue()).containsEntry("orderId", ORDER_ID);
    }

    @Test
    void onOrderCancelled_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(orderCancelledJson(eventId, ORDER_ID)), EventType.ORDER_CANCELLED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.ORDER_CANCELLED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture());

        assertThat(templateVarsCaptor.getValue()).containsEntry("orderId", ORDER_ID);
    }

    @Test
    void ignoresEventsOfOtherTypes_onTheSameTopic() {
        consumer.onMessage(record("{}"), EventType.PAYMENT_CONFIRMED);

        verifyNoInteractions(orderClient, notificationService);
    }

    @Test
    void toleratesUnknownFieldsOnTheEventPayload() {
        // See OrderConfirmedEvent's own Javadoc / this class's readEvent —
        // an oms-main producer that's rolled forward with a new additive
        // field (e.g. the recommended customerId fix, before this
        // consumer's own event records are updated to model it) must not
        // break this consumer.
        UUID eventId = UUID.randomUUID();
        String jsonWithExtraField = """
                {"eventId": "%s", "orderId": %d, "occurredAt": "2026-08-14T12:00:00", "customerId": 999}
                """.formatted(eventId, ORDER_ID);
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(jsonWithExtraField), EventType.ORDER_CONFIRMED);

        verify(notificationService).processEvent(eq(eventId), any(), eq(CUSTOMER_ID), eq(ORDER_ID), any());
    }

    @Test
    void propagatesOrderNotFoundException_uncaught() {
        // Deliberately uncaught — see this class's own Javadoc on why a
        // dependency failure's retry/DLT decision belongs to Spring Kafka's
        // error handling (KafkaConfig.kafkaErrorHandler), not this class.
        when(orderClient.getOrder(ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        assertThatThrownBy(() -> consumer.onMessage(record(orderConfirmedJson(UUID.randomUUID(), ORDER_ID)), EventType.ORDER_CONFIRMED))
                .isInstanceOf(OrderNotFoundException.class);

        verify(notificationService, never()).processEvent(any(), any(), any(), any(), any());
    }

    @Test
    void propagatesOrderServiceUnavailableException_uncaught() {
        when(orderClient.getOrder(ORDER_ID))
                .thenThrow(new OrderServiceUnavailableException(ORDER_ID, new RuntimeException("timeout")));

        assertThatThrownBy(() -> consumer.onMessage(record(orderCancelledJson(UUID.randomUUID(), ORDER_ID)), EventType.ORDER_CANCELLED))
                .isInstanceOf(OrderServiceUnavailableException.class);

        verify(notificationService, never()).processEvent(any(), any(), any(), any(), any());
    }

    private String orderConfirmedJson(UUID eventId, Long orderId) {
        return """
                {"eventId": "%s", "orderId": %d, "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId);
    }

    private String orderCancelledJson(UUID eventId, Long orderId) {
        return """
                {"eventId": "%s", "orderId": %d, "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId);
    }

    private ConsumerRecord<String, String> record(String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("oms.order.events", 0, 0L, "key", value);
        record.headers().add(new RecordHeader("eventType", EventType.ORDER_CONFIRMED.getBytes()));
        return record;
    }
}
