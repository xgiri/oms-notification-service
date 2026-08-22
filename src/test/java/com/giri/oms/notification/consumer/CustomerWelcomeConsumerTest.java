package com.giri.oms.notification.consumer;

import com.giri.oms.messaging.event.EventType;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.service.NotificationService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A real JsonMapper, mocked NotificationService — same convention as
 * OrderNotificationConsumerTest/PaymentNotificationConsumerTest. Unlike
 * both of those, there's no OrderClient/CustomerClient mock here to set up
 * — see this consumer's own Javadoc on why CustomerCreatedEvent needs no
 * synchronous lookup to resolve who to notify.
 */
@ExtendWith(MockitoExtension.class)
class CustomerWelcomeConsumerTest {

    @Mock
    private NotificationService notificationService;

    private CustomerWelcomeConsumer consumer;

    private static final Long CUSTOMER_ID = 7L;

    @BeforeEach
    void setUp() {
        consumer = new CustomerWelcomeConsumer(notificationService, JsonMapper.builder().build());
    }

    @Test
    void onCustomerCreated_processesTheNotification_withNoOrderClientLookup() {
        UUID eventId = UUID.randomUUID();

        consumer.onMessage(record(customerCreatedJson(eventId, CUSTOMER_ID)), EventType.CUSTOMER_CREATED);

        ArgumentCaptor<Map<String, Object>> templateVarsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).processEvent(
                eq(eventId), eq(NotificationType.CUSTOMER_WELCOME), eq(CUSTOMER_ID), isNull(), templateVarsCaptor.capture(), anyLong());

        assertThat(templateVarsCaptor.getValue()).isEmpty();
    }

    @Test
    void ignoresEventsOfOtherTypes_onTheSameTopic() {
        consumer.onMessage(record("{}"), EventType.ORDER_CONFIRMED);

        verifyNoInteractions(notificationService);
    }

    @Test
    void toleratesUnknownFieldsOnTheEventPayload() {
        UUID eventId = UUID.randomUUID();
        String jsonWithExtraField = """
                {"eventId": "%s", "customerId": %d, "email": "jane@example.com", "occurredAt": "2026-08-14T12:00:00", "phone": "+15551234567"}
                """.formatted(eventId, CUSTOMER_ID);

        consumer.onMessage(record(jsonWithExtraField), EventType.CUSTOMER_CREATED);

        verify(notificationService).processEvent(eq(eventId), eq(NotificationType.CUSTOMER_WELCOME), eq(CUSTOMER_ID), isNull(), eq(Map.of()), anyLong());
    }

    private String customerCreatedJson(UUID eventId, Long customerId) {
        return """
                {"eventId": "%s", "customerId": %d, "email": "jane@example.com", "occurredAt": "2026-08-14T12:00:00"}
                """.formatted(eventId, customerId);
    }

    private ConsumerRecord<String, String> record(String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("oms.customer.events", 0, 0L, "key", value);
        record.headers().add(new RecordHeader("eventType", EventType.CUSTOMER_CREATED.getBytes()));
        return record;
    }
}
