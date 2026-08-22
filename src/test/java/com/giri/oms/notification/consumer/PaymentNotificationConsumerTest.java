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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Same testing conventions as OrderNotificationConsumerTest — a
 * real JsonMapper, mocked NotificationService/OrderClient, this class's own
 * job under test is the dispatch/wiring logic for BOTH event types it
 * handles on its one {@code @KafkaListener} method (see this class's own
 * Javadoc on why both live in one method).
 */
@ExtendWith(MockitoExtension.class)
class PaymentNotificationConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private OrderClient orderClient;

    private PaymentNotificationConsumer consumer;

    private static final Long ORDER_ID = 100L;
    private static final Long CUSTOMER_ID = 7L;

    @BeforeEach
    void setUp() {
        consumer = new PaymentNotificationConsumer(notificationService, orderClient, JsonMapper.builder().build());
    }

    @Test
    void onPaymentConfirmed_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(paymentConfirmedJson(eventId, ORDER_ID, "49.99")), EventType.PAYMENT_CONFIRMED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.PAYMENT_CONFIRMED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture(), anyLong());

        assertThat(templateVarsCaptor.getValue())
                .containsEntry("orderId", ORDER_ID)
                .containsEntry("amount", new BigDecimal("49.99"));
    }

    @Test
    void onPaymentFailed_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(paymentFailedJson(eventId, ORDER_ID)), EventType.PAYMENT_FAILED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.PAYMENT_FAILED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture(), anyLong());

        assertThat(templateVarsCaptor.getValue()).containsEntry("orderId", ORDER_ID);
    }

    @Test
    void ignoresEventsOfOtherTypes_onTheSameTopic() {
        consumer.onMessage(record("{}"), EventType.ORDER_CONFIRMED);

        verifyNoInteractions(orderClient, notificationService);
    }

    @Test
    void toleratesUnknownFieldsOnTheEventPayload() {
        UUID eventId = UUID.randomUUID();
        String jsonWithExtraField = """
                {"eventId": "%s", "orderId": %d, "amount": 49.99, "occurredAt": "2026-08-14T12:00:00", "transactionReference": "txn_abc"}
                """.formatted(eventId, ORDER_ID);
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(jsonWithExtraField), EventType.PAYMENT_CONFIRMED);

        verify(notificationService).processEvent(eq(eventId), any(), eq(CUSTOMER_ID), eq(ORDER_ID), any(), anyLong());
    }

    @Test
    void propagatesOrderNotFoundException_uncaught() {
        when(orderClient.getOrder(ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        assertThatThrownBy(() -> consumer.onMessage(record(paymentConfirmedJson(UUID.randomUUID(), ORDER_ID, "49.99")), EventType.PAYMENT_CONFIRMED))
                .isInstanceOf(OrderNotFoundException.class);

        verify(notificationService, never()).processEvent(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void propagatesOrderServiceUnavailableException_uncaught() {
        when(orderClient.getOrder(ORDER_ID))
                .thenThrow(new OrderServiceUnavailableException(ORDER_ID, new RuntimeException("timeout")));

        assertThatThrownBy(() -> consumer.onMessage(record(paymentFailedJson(UUID.randomUUID(), ORDER_ID)), EventType.PAYMENT_FAILED))
                .isInstanceOf(OrderServiceUnavailableException.class);

        verify(notificationService, never()).processEvent(any(), any(), any(), any(), any(), anyLong());
    }

    private String paymentConfirmedJson(UUID eventId, Long orderId, String amount) {
        return """
                {"eventId": "%s", "orderId": %d, "amount": %s, "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId, amount);
    }

    private String paymentFailedJson(UUID eventId, Long orderId) {
        return """
                {"eventId": "%s", "orderId": %d, "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId);
    }

    private ConsumerRecord<String, String> record(String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("oms.order.events", 0, 0L, "key", value);
        record.headers().add(new RecordHeader("eventType", EventType.PAYMENT_CONFIRMED.getBytes()));
        return record;
    }
}
