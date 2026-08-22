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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Same testing conventions as OrderNotificationConsumerTest/
 * PaymentNotificationConsumerTest — a real JsonMapper, mocked
 * NotificationService/OrderClient, this class's own job under test is the
 * dispatch/wiring logic for the THREE event types this consumer handles on
 * its one {@code @KafkaListener} method.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentNotificationConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private OrderClient orderClient;

    private ShipmentNotificationConsumer consumer;

    private static final Long ORDER_ID = 100L;
    private static final Long CUSTOMER_ID = 7L;

    @BeforeEach
    void setUp() {
        consumer = new ShipmentNotificationConsumer(notificationService, orderClient, JsonMapper.builder().build());
    }

    @Test
    void onShipmentShipped_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(shipmentShippedJson(eventId, ORDER_ID, "1Z999AA10123456784")), EventType.SHIPMENT_SHIPPED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.SHIPMENT_SHIPPED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture(), anyLong());

        assertThat(templateVarsCaptor.getValue())
                .containsEntry("orderId", ORDER_ID)
                .containsEntry("trackingNumber", "1Z999AA10123456784");
    }

    @Test
    void onShipmentDelivered_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(shipmentDeliveredJson(eventId, ORDER_ID)), EventType.SHIPMENT_DELIVERED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.SHIPMENT_DELIVERED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture(), anyLong());

        assertThat(templateVarsCaptor.getValue()).containsEntry("orderId", ORDER_ID);
    }

    @Test
    void onShipmentReturned_resolvesCustomerIdViaOrderClient_andProcessesTheNotification() {
        UUID eventId = UUID.randomUUID();
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(shipmentReturnedJson(eventId, ORDER_ID)), EventType.SHIPMENT_RETURNED);

        verify(orderClient).getOrder(ORDER_ID);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.SHIPMENT_RETURNED), eq(CUSTOMER_ID), eq(ORDER_ID), templateVarsCaptor.capture(), anyLong());

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
                {"eventId": "%s", "orderId": %d, "trackingNumber": "1Z999AA10123456784", "shippedAt": "2026-08-14T12:00:00", "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, ORDER_ID);
        when(orderClient.getOrder(ORDER_ID)).thenReturn(new OrderClientResponse(ORDER_ID, CUSTOMER_ID));

        consumer.onMessage(record(jsonWithExtraField), EventType.SHIPMENT_SHIPPED);

        verify(notificationService).processEvent(eq(eventId), any(), eq(CUSTOMER_ID), eq(ORDER_ID), any(), anyLong());
    }

    @Test
    void propagatesOrderNotFoundException_uncaught() {
        when(orderClient.getOrder(ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        assertThatThrownBy(() -> consumer.onMessage(record(shipmentShippedJson(UUID.randomUUID(), ORDER_ID, "TRACK123")), EventType.SHIPMENT_SHIPPED))
                .isInstanceOf(OrderNotFoundException.class);

        verify(notificationService, never()).processEvent(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void propagatesOrderServiceUnavailableException_uncaught() {
        when(orderClient.getOrder(ORDER_ID))
                .thenThrow(new OrderServiceUnavailableException(ORDER_ID, new RuntimeException("timeout")));

        assertThatThrownBy(() -> consumer.onMessage(record(shipmentDeliveredJson(UUID.randomUUID(), ORDER_ID)), EventType.SHIPMENT_DELIVERED))
                .isInstanceOf(OrderServiceUnavailableException.class);

        verify(notificationService, never()).processEvent(any(), any(), any(), any(), any(), anyLong());
    }

    private String shipmentShippedJson(UUID eventId, Long orderId, String trackingNumber) {
        return """
                {"eventId": "%s", "orderId": %d, "trackingNumber": "%s", "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId, trackingNumber);
    }

    private String shipmentDeliveredJson(UUID eventId, Long orderId) {
        return """
                {"eventId": "%s", "orderId": %d, "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId);
    }

    private String shipmentReturnedJson(UUID eventId, Long orderId) {
        return """
                {"eventId": "%s", "orderId": %d, "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, orderId);
    }

    private ConsumerRecord<String, String> record(String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("oms.shipment.events", 0, 0L, "key", value);
        record.headers().add(new RecordHeader("eventType", EventType.SHIPMENT_SHIPPED.getBytes()));
        return record;
    }
}
