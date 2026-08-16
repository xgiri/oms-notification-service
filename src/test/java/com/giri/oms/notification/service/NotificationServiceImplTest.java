package com.giri.oms.notification.service;

import com.giri.oms.customerclient.dto.CustomerClientResponse;
import com.giri.oms.customerclient.service.CustomerClient;
import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.provider.NotificationProvider;
import com.giri.oms.notification.provider.NotificationRequest;
import com.giri.oms.notification.provider.ProviderResult;
import com.giri.oms.notification.repository.NotificationRepository;
import com.giri.oms.notification.repository.ProcessedEventRepository;
import com.giri.oms.notification.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The idempotency behavior is this test class's top priority — see this
 * service's README on why a duplicate notification is the kind of bug a
 * customer actually notices, unlike most other consumers in this system's
 * duplicate-processing bugs. Everything else here is secondary to
 * {@link Idempotency}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPreferenceService preferenceService;

    @Mock
    private CustomerClient customerClient;

    @Mock
    private NotificationComposer composer;

    @Mock
    private NotificationProvider emailProvider;

    private Clock clock;
    private NotificationServiceImpl notificationService;

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final Long CUSTOMER_ID = 7L;
    private static final Long ORDER_ID = 100L;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
        notificationService = new NotificationServiceImpl(
                processedEventRepository, notificationRepository, preferenceService,
                customerClient, composer, List.of(emailProvider), clock);
    }

    @Nested
    class Idempotency {

        @Test
        void skipsEntirely_whenEventAlreadyProcessedForThisType() {
            when(processedEventRepository.existsByEventIdAndNotificationType(EVENT_ID, "ORDER_CONFIRMED"))
                    .thenReturn(true);

            notificationService.processEvent(EVENT_ID, NotificationType.ORDER_CONFIRMED, CUSTOMER_ID, ORDER_ID, Map.of());

            // Nothing downstream of the idempotency check should ever be
            // touched — not the preference check, not CustomerClient, not
            // the provider, not a second ProcessedEvent row.
            verifyNoInteractions(preferenceService, customerClient, composer, emailProvider);
            verify(notificationRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void doesNotSkip_whenSameEventIdButDifferentNotificationType() {
            // Keyed on (event_id, notification_type) together, not event_id
            // alone — see ProcessedEvent's own Javadoc on why a single event
            // fanning out to more than one notification type must not be
            // treated as a duplicate of itself.
            when(processedEventRepository.existsByEventIdAndNotificationType(EVENT_ID, "ORDER_CONFIRMED"))
                    .thenReturn(false);
            when(preferenceService.isOptedIn(CUSTOMER_ID, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL))
                    .thenReturn(true);
            when(customerClient.getCustomer(CUSTOMER_ID))
                    .thenReturn(new CustomerClientResponse(CUSTOMER_ID, "Jane", "Doe", "jane@example.com", null));
            when(emailProvider.channel()).thenReturn(NotificationChannel.EMAIL);
            when(composer.compose(any(), anyString(), anyString(), any()))
                    .thenReturn(new NotificationRequest("jane@example.com", "subj", "html", "text"));
            when(emailProvider.send(any())).thenReturn(ProviderResult.success("msg-1"));

            notificationService.processEvent(EVENT_ID, NotificationType.ORDER_CONFIRMED, CUSTOMER_ID, ORDER_ID, Map.of());

            verify(notificationRepository).save(any());
        }

        @Test
        void redeliveryAfterASuccessfulSend_isATotalNoOp() {
            // Simulates exactly the scenario this table exists for: Kafka
            // redelivers the same message (consumer restart, rebalance,
            // retry) after it was already fully processed.
            when(processedEventRepository.existsByEventIdAndNotificationType(EVENT_ID, "ORDER_CONFIRMED"))
                    .thenReturn(false)
                    .thenReturn(true);
            when(preferenceService.isOptedIn(any(), any(), any())).thenReturn(true);
            when(customerClient.getCustomer(CUSTOMER_ID))
                    .thenReturn(new CustomerClientResponse(CUSTOMER_ID, "Jane", "Doe", "jane@example.com", null));
            when(emailProvider.channel()).thenReturn(NotificationChannel.EMAIL);
            when(composer.compose(any(), anyString(), anyString(), any()))
                    .thenReturn(new NotificationRequest("jane@example.com", "subj", "html", "text"));
            when(emailProvider.send(any())).thenReturn(ProviderResult.success("msg-1"));

            notificationService.processEvent(EVENT_ID, NotificationType.ORDER_CONFIRMED, CUSTOMER_ID, ORDER_ID, Map.of());
            notificationService.processEvent(EVENT_ID, NotificationType.ORDER_CONFIRMED, CUSTOMER_ID, ORDER_ID, Map.of());

            // Exactly one send, one saved Notification, one ProcessedEvent —
            // not two of any of them.
            verify(emailProvider, org.mockito.Mockito.times(1)).send(any());
            verify(notificationRepository, org.mockito.Mockito.times(1)).save(any());
            verify(processedEventRepository, org.mockito.Mockito.times(1)).save(any());
        }
    }

    @Nested
    class PreferenceEnforcement {

        @Test
        void skipsSend_butStillMarksProcessed_whenOptedOut() {
            when(processedEventRepository.existsByEventIdAndNotificationType(EVENT_ID, "ORDER_CONFIRMED"))
                    .thenReturn(false);
            when(preferenceService.isOptedIn(CUSTOMER_ID, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL))
                    .thenReturn(false);

            notificationService.processEvent(EVENT_ID, NotificationType.ORDER_CONFIRMED, CUSTOMER_ID, ORDER_ID, Map.of());

            // Opted-out still marks the event processed — a redelivery of
            // this same event must not re-evaluate (and potentially now
            // send, if the preference flipped) — see NotificationServiceImpl's
            // own Javadoc on why idempotency is checked before, not after,
            // the preference check.
            verify(processedEventRepository).save(any());
            verifyNoInteractions(customerClient, composer, emailProvider);
            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    class FailedSendHandling {

        @Test
        void recordsFailedStatus_andDoesNotThrow_whenProviderSendFails() {
            when(processedEventRepository.existsByEventIdAndNotificationType(EVENT_ID, "ORDER_CONFIRMED"))
                    .thenReturn(false);
            when(preferenceService.isOptedIn(any(), any(), any())).thenReturn(true);
            when(customerClient.getCustomer(CUSTOMER_ID))
                    .thenReturn(new CustomerClientResponse(CUSTOMER_ID, "Jane", "Doe", "jane@example.com", null));
            when(emailProvider.channel()).thenReturn(NotificationChannel.EMAIL);
            when(composer.compose(any(), anyString(), anyString(), any()))
                    .thenReturn(new NotificationRequest("jane@example.com", "subj", "html", "text"));
            when(emailProvider.send(any())).thenReturn(ProviderResult.failure("SMTP connection refused"));

            // Must NOT throw — see NotificationServiceImpl's own Javadoc on
            // why a failed send is recorded, not raised as an exception
            // that would fail the whole Kafka message after the
            // idempotency row has already been written.
            notificationService.processEvent(EVENT_ID, NotificationType.ORDER_CONFIRMED, CUSTOMER_ID, ORDER_ID, Map.of());

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(captor.getValue().getLastError()).isEqualTo("SMTP connection refused");

            // Still marked processed, same reasoning as the opted-out case —
            // a redelivery must not re-send.
            verify(processedEventRepository).save(any());
        }
    }
}
