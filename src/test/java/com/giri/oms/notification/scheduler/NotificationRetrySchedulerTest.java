package com.giri.oms.notification.scheduler;

import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.repository.NotificationRepository;
import com.giri.oms.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link NotificationRetryScheduler}'s own new logic — the
 * retry-vs-dead-letter decision — not {@code resend}'s own compose/send
 * behavior, which is already covered by {@code NotificationServiceImplTest}.
 * This class deliberately mocks {@link NotificationService} rather than a
 * real one for exactly that reason: reusing existing, already-tested logic
 * shouldn't need re-testing here.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRetrySchedulerTest {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationService notificationService;

    private NotificationRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NotificationRetryScheduler(notificationRepository, notificationService, BATCH_SIZE, MAX_ATTEMPTS);
    }

    private Notification notificationWithRetryCount(long id, int retryCount) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setType(NotificationType.ORDER_CONFIRMED);
        notification.setChannel(NotificationChannel.EMAIL);
        notification.setStatus(NotificationStatus.FAILED);
        notification.setCustomerId(100L);
        notification.setRecipientAddress("jane@example.com");
        notification.setRetryCount(retryCount);
        return notification;
    }

    @Nested
    class EmptyBatch {

        @Test
        void doesNothing_whenNoFailedNotificationsExist() {
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of());

            scheduler.retryFailedNotifications();

            verify(notificationService, never()).resend(any());
            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    class UnderRetryBudget {

        @Test
        void delegatesToResend_whenRetryCountIsBelowMaxAttempts() {
            Notification notification = notificationWithRetryCount(1L, MAX_ATTEMPTS - 1);
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of(notification));

            scheduler.retryFailedNotifications();

            verify(notificationService).resend(1L);
            // The retry-budget path (this class's own logic) never
            // saves/mutates the row itself — resend owns that entirely, so
            // this scheduler shouldn't also be writing to it.
            verify(notificationRepository, never()).save(notification);
        }

        @Test
        void delegatesToResendForEveryEligibleRow_inTheBatch() {
            Notification first = notificationWithRetryCount(1L, 0);
            Notification second = notificationWithRetryCount(2L, 2);
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of(first, second));

            scheduler.retryFailedNotifications();

            verify(notificationService).resend(1L);
            verify(notificationService).resend(2L);
        }
    }

    @Nested
    class RetryBudgetExhausted {

        @Test
        void deadLettersInstead_whenRetryCountHasReachedMaxAttempts() {
            Notification notification = notificationWithRetryCount(1L, MAX_ATTEMPTS);
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of(notification));

            scheduler.retryFailedNotifications();

            verify(notificationService, never()).resend(any());

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.DEAD_LETTERED);
        }

        @Test
        void deadLettersInstead_whenRetryCountHasExceededMaxAttempts() {
            // >= not == — a defensive boundary in case max-attempts is
            // lowered via config while FAILED rows with a higher retryCount
            // already exist.
            Notification notification = notificationWithRetryCount(1L, MAX_ATTEMPTS + 3);
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of(notification));

            scheduler.retryFailedNotifications();

            verify(notificationService, never()).resend(any());
            verify(notificationRepository).save(notification);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTERED);
        }

        @Test
        void deadLettersAgain_ifHandedAnAlreadyDeadLetteredRow() {
            // Not reachable via the real query (see
            // NotificationRepository#findAndLockFailedBatch's WHERE
            // status = 'FAILED') — this documents the scheduler's OWN
            // behavior if it were ever handed one anyway: idempotent, not a
            // retry, regardless of how the row got into the batch.
            Notification alreadyDeadLettered = notificationWithRetryCount(1L, MAX_ATTEMPTS);
            alreadyDeadLettered.setStatus(NotificationStatus.DEAD_LETTERED);
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of(alreadyDeadLettered));

            scheduler.retryFailedNotifications();

            verify(notificationService, never()).resend(any());
        }
    }

    @Nested
    class MixedBatch {

        @Test
        void handlesEachRowIndependently_someRetriedSomeDeadLettered() {
            Notification retryable = notificationWithRetryCount(1L, 1);
            Notification exhausted = notificationWithRetryCount(2L, MAX_ATTEMPTS);
            when(notificationRepository.findAndLockFailedBatch(BATCH_SIZE)).thenReturn(List.of(retryable, exhausted));

            scheduler.retryFailedNotifications();

            verify(notificationService, times(1)).resend(1L);
            verify(notificationService, never()).resend(2L);
            verify(notificationRepository).save(exhausted);
        }
    }
}
