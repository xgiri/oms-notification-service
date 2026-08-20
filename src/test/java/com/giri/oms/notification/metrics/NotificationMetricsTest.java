package com.giri.oms.notification.metrics;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A real {@link SimpleMeterRegistry} is used throughout, not a mock — same
 * reasoning as every other test in this repo that exercises real
 * infrastructure where the thing being tested IS the wiring itself (see
 * {@code NotificationComposerImplTest}'s own real-{@code SpringTemplateEngine}
 * choice). A mocked {@code MeterRegistry} would only prove this class calls
 * the mock the way the test expects, not that a real counter/gauge/timer
 * actually gets registered and updated correctly.
 */
@ExtendWith(MockitoExtension.class)
class NotificationMetricsTest {

    @Mock
    private NotificationRepository notificationRepository;

    private SimpleMeterRegistry meterRegistry;
    private NotificationMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new NotificationMetrics(notificationRepository, meterRegistry);
    }

    @Test
    void pendingFailedGauge_reflectsTheRepositoryCountAtReadTime() {
        when(notificationRepository.countByStatus(NotificationStatus.FAILED)).thenReturn(7L);

        double value = meterRegistry.get("notifications.pending.failed").gauge().value();

        assertThat(value).isEqualTo(7.0);
    }

    @Test
    void pendingFailedGauge_isLive_notASnapshotTakenAtConstruction() {
        // The whole point of a gauge over a one-time count: it re-reads the
        // repository on every scrape, not just once at startup.
        when(notificationRepository.countByStatus(NotificationStatus.FAILED)).thenReturn(3L, 9L);

        assertThat(meterRegistry.get("notifications.pending.failed").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get("notifications.pending.failed").gauge().value()).isEqualTo(9.0);
    }

    @Test
    void recordSent_incrementsTheSentCounter_taggedByChannelAndType() {
        metrics.recordSent(NotificationChannel.EMAIL, NotificationType.ORDER_CONFIRMED, TimeUnit.MILLISECONDS.toNanos(150));

        double count = meterRegistry.get("notifications.sent")
                .tag("channel", "EMAIL")
                .tag("type", "ORDER_CONFIRMED")
                .counter()
                .count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordSent_keepsSeparateCounts_perChannelAndType() {
        metrics.recordSent(NotificationChannel.EMAIL, NotificationType.ORDER_CONFIRMED, 1_000_000);
        metrics.recordSent(NotificationChannel.EMAIL, NotificationType.ORDER_CONFIRMED, 1_000_000);
        metrics.recordSent(NotificationChannel.SMS, NotificationType.ORDER_CONFIRMED, 1_000_000);
        metrics.recordSent(NotificationChannel.EMAIL, NotificationType.PAYMENT_CONFIRMED, 1_000_000);

        assertThat(meterRegistry.get("notifications.sent").tag("channel", "EMAIL").tag("type", "ORDER_CONFIRMED")
                .counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("notifications.sent").tag("channel", "SMS").tag("type", "ORDER_CONFIRMED")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("notifications.sent").tag("channel", "EMAIL").tag("type", "PAYMENT_CONFIRMED")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordSent_alsoRecordsTheSendDurationTimer_taggedByChannelOnly() {
        // Channel only, not type — see this class's own Javadoc on
        // "provider latency" meaning the PROVIDER's speed, which varies by
        // channel (SMTP vs Twilio) but has no reason to vary by
        // notification type.
        metrics.recordSent(NotificationChannel.EMAIL, NotificationType.ORDER_CONFIRMED, TimeUnit.MILLISECONDS.toNanos(250));

        Timer timer = meterRegistry.get("notifications.send.duration").tag("channel", "EMAIL").timer();

        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(250.0, Offset.offset(1.0));
    }

    @Test
    void recordFailed_incrementsTheFailedCounter_taggedByChannelAndType_notTheSentCounter() {
        metrics.recordFailed(NotificationChannel.SMS, NotificationType.PAYMENT_FAILED);

        double failedCount = meterRegistry.get("notifications.failed")
                .tag("channel", "SMS").tag("type", "PAYMENT_FAILED").counter().count();
        assertThat(failedCount).isEqualTo(1.0);

        // A failed send must not also register as sent.
        assertThat(meterRegistry.find("notifications.sent")
                .tag("channel", "SMS").tag("type", "PAYMENT_FAILED").counter()).isNull();
    }

    @Test
    void recordDeadLettered_incrementsItsOwnCounter_separateFromFailed() {
        metrics.recordFailed(NotificationChannel.EMAIL, NotificationType.ORDER_CONFIRMED);
        metrics.recordDeadLettered(NotificationChannel.EMAIL, NotificationType.ORDER_CONFIRMED);

        double failedCount = meterRegistry.get("notifications.failed")
                .tag("channel", "EMAIL").tag("type", "ORDER_CONFIRMED").counter().count();
        double deadLetteredCount = meterRegistry.get("notifications.dead_lettered")
                .tag("channel", "EMAIL").tag("type", "ORDER_CONFIRMED").counter().count();

        // The core contract this test exists to pin down: dead-lettering
        // doesn't ALSO bump notifications.failed a second time (retryOne
        // calls recordDeadLettered instead of, not in addition to,
        // recordFailed — see NotificationRetryScheduler's own call site).
        // The failed=1 here comes only from this test's own explicit
        // recordFailed call above, not from recordDeadLettered.
        assertThat(failedCount).isEqualTo(1.0);
        assertThat(deadLetteredCount).isEqualTo(1.0);
    }
}
