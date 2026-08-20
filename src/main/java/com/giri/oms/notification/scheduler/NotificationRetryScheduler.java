package com.giri.oms.notification.scheduler;

import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.metrics.NotificationMetrics;
import com.giri.oms.notification.repository.NotificationRepository;
import com.giri.oms.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The plan's §6 piece that Phase 1–4 shipped without: a {@code FAILED}
 * notification's in-call resilience4j retry (see {@code SmtpEmailProvider}/
 * {@code TwilioSmsProvider}) only covers a transient failure that resolves
 * within the same request — it says nothing about "the provider was down
 * for an hour". This class is what closes that gap, same shape as
 * {@code OutboxPublisher} (customer-service/oms-main):
 * {@code FOR UPDATE SKIP LOCKED} claims a batch, one transaction covers the
 * whole batch, and it's distinct from Kafka's own retry/DLT mechanism on
 * purpose — see this service's README §6 for why (Kafka's retry is for
 * message-*processing* failures; this is for downstream-*send* failures,
 * and conflating the two would mean an hour of provider downtime redelivers
 * the same Kafka message endlessly, which is a consumer-lag problem for
 * every OTHER event type sharing that partition, not just this one).
 * <p>
 * Guarded by {@code app.process.role}, same convention as every other
 * scheduled/consuming component in this service — see
 * {@code OrderNotificationConsumer}'s own Javadoc.
 * <p>
 * Deliberately reuses {@link NotificationService#resend}'s existing
 * compose-send-record logic rather than duplicating it — this class's only
 * NEW logic is the retry-budget decision ({@link #retryOne}). That also
 * means this inherits {@code resend}'s own known Phase 1 limitation
 * (template variables reconstructed from stored columns only, not the
 * original composition inputs) — see that method's own Javadoc.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.process.role", havingValue = "worker", matchIfMissing = true)
public class NotificationRetryScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final NotificationMetrics notificationMetrics;
    private final int batchSize;
    private final int maxAttempts;

    public NotificationRetryScheduler(NotificationRepository notificationRepository,
                                       NotificationService notificationService,
                                       NotificationMetrics notificationMetrics,
                                       @Value("${app.notification.retry.batch-size}") int batchSize,
                                       @Value("${app.notification.retry.max-attempts}") int maxAttempts) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.notificationMetrics = notificationMetrics;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * {@code @Transactional} here isn't incidental, same reasoning as
     * {@code OutboxPublisher#publishPendingEvents}: it's what makes
     * {@link NotificationRepository#findAndLockFailedBatch} actually
     * protect these rows from a concurrent poller, and it's why
     * {@link NotificationService#resend} (called per-row below) safely
     * joins THIS transaction instead of opening its own — REQUIRED
     * propagation, Spring's default. Same trade-off as the outbox
     * equivalent: a slow batch holds these row locks (and this DB
     * transaction) open for the whole batch's duration. batch-size and
     * poll-interval are the two knobs to tune if that ever matters.
     */
    @Scheduled(fixedDelayString = "${app.notification.retry.poll-interval-ms}")
    @Transactional
    public void retryFailedNotifications() {
        List<Notification> batch = notificationRepository.findAndLockFailedBatch(batchSize);

        if (batch.isEmpty()) {
            return;
        }

        log.debug("Retrying {} failed notification(s)", batch.size());
        for (Notification notification : batch) {
            retryOne(notification);
        }
    }

    /**
     * The one piece of NEW logic this class adds on top of
     * {@code resend}: a notification that's already exhausted
     * {@code maxAttempts} is dead-lettered instead of retried again — see
     * {@link NotificationStatus}'s own Javadoc on why {@code DEAD_LETTERED}
     * is terminal (this scheduler's own query only ever selects
     * {@code FAILED} rows, so a dead-lettered row is never picked up
     * again). Getting a dead-lettered notification back out requires a
     * human hitting {@code POST /notifications/{id}/resend} directly — see
     * that endpoint.
     */
    private void retryOne(Notification notification) {
        if (notification.getRetryCount() >= maxAttempts) {
            notification.setStatus(NotificationStatus.DEAD_LETTERED);
            notificationRepository.save(notification);
            notificationMetrics.recordDeadLettered(notification.getChannel(), notification.getType());
            log.warn("Dead-lettering notification id={} type={} channel={} customerId={} after {} attempt(s): {}",
                    notification.getId(), notification.getType(), notification.getChannel(),
                    notification.getCustomerId(), notification.getRetryCount(), notification.getLastError());
            return;
        }

        notificationService.resend(notification.getId());
    }
}
