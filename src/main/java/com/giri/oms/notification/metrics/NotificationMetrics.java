package com.giri.oms.notification.metrics;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Custom Micrometer instrumentation for this service's own domain — the
 * JVM/HTTP/Kafka-client metrics elsewhere come for free from Spring Boot's
 * actuator + Kafka autoconfiguration once a {@link MeterRegistry} bean
 * exists, but send outcomes and retry-queue depth are domain-specific and
 * need registering explicitly. Same role in this service that
 * {@code OutboxMetrics} plays in customer-service/oms-main — see that
 * class's own Javadoc, which this one mirrors closely.
 * <p>
 * This is what closes the gap
 * {@code k8s/09-grafana-dashboard.yaml}'s own top comment flagged: before
 * this class existed, "notifications sent/failed by channel and type" and
 * "provider latency" (both explicitly asked for in this service's original
 * plan §10) had no Prometheus series to query at all.
 * <p>
 * {@code notifications.pending.failed} is the in-app, Prometheus-queryable
 * twin of {@code k8s/06-scaledobject-worker.yaml}'s own
 * {@code SELECT COUNT(*) ... WHERE status = 'FAILED'} query — same number,
 * different consumer. KEDA needs it outside the app (to decide replica
 * count before any replica exists to scrape); this is for
 * dashboards/alerts once instances are up.
 * <p>
 * Deliberately NOT gated by {@code app.process.role} — a plain count
 * against the shared {@code notifications} table is just as meaningful
 * scraped from a {@code web} instance as a {@code worker} one, same
 * reasoning as {@code OutboxMetrics}' own gauge. Only {@link #recordSent}/
 * {@link #recordFailed}/{@link #recordDeadLettered} are worker-only in
 * practice, simply because nothing else calls them today — see
 * {@code NotificationServiceImpl#sendAndRecord}/{@code #resend} and
 * {@code NotificationRetryScheduler#retryOne}.
 * <p>
 * {@code channel}/{@code type} tags are bounded (2 channels × up to 8
 * types today = at most 16 series per counter) — a genuinely different
 * situation from {@code OutboxMetrics#recordFailed}'s own choice not to
 * tag by failure reason (unbounded exception text would be a real
 * cardinality problem; a closed enum pair is not).
 * <p>
 * Does NOT include a time-to-delivery metric (event-received →
 * notification-sent), even though this service's own plan called it out
 * by name as worth having — that needs the event's own receipt time
 * threaded through {@code NotificationService#processEvent} from each
 * {@code @KafkaListener}'s {@code ConsumerRecord#timestamp()}, which is a
 * public-interface signature change touching all 3 consumers and their
 * tests, not just this class. Left as a deliberate follow-up rather than
 * folded in here silently.
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry meterRegistry;

    public NotificationMetrics(NotificationRepository notificationRepository, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        meterRegistry.gauge(
                "notifications.pending.failed",
                Tags.empty(),
                notificationRepository,
                repo -> repo.countByStatus(NotificationStatus.FAILED));
    }

    /**
     * Call with the elapsed duration of the {@link com.giri.oms.notification.provider.NotificationProvider#send}
     * call specifically — not the whole {@code sendAndRecord}/{@code resend}
     * method, which also does composition and a DB write. This is "provider
     * latency" as the plan meant it: how long the provider itself (SMTP,
     * Twilio, including whatever resilience4j retry/circuit-breaker time
     * that provider spent internally) took to answer.
     */
    public void recordSent(NotificationChannel channel, NotificationType type, long durationNanos) {
        meterRegistry.counter("notifications.sent", "channel", channel.name(), "type", type.name()).increment();

        Timer.builder("notifications.send.duration")
                .description("Time spent inside NotificationProvider#send — includes that provider's own "
                        + "resilience4j retry/circuit-breaker time, not just one network round trip")
                .tag("channel", channel.name())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Call for any failed send — {@code ProviderResult.failure(...)}, not
     * an exception (see {@code NotificationProvider}'s own Javadoc on why
     * providers never throw). Not tagged by failure reason for the same
     * cardinality reasoning as {@code OutboxMetrics#recordFailed} — the
     * {@code last_error} column on the {@code Notification} row itself
     * already carries that detail for anyone debugging a specific one.
     */
    public void recordFailed(NotificationChannel channel, NotificationType type) {
        meterRegistry.counter("notifications.failed", "channel", channel.name(), "type", type.name()).increment();
    }

    /**
     * Call ONLY from {@code NotificationRetryScheduler#retryOne}'s own
     * retry-budget-exhausted branch — this is a distinct, rarer event from
     * an ordinary {@link #recordFailed} (which fires on every failed send
     * attempt, including ones that'll be retried and likely succeed). A
     * dead letter means a human now has to intervene
     * ({@code POST /notifications/{id}/resend}), which is worth its own
     * counter and its own alert threshold rather than being buried in the
     * much noisier {@code notifications.failed} series.
     */
    public void recordDeadLettered(NotificationChannel channel, NotificationType type) {
        meterRegistry.counter("notifications.dead_lettered", "channel", channel.name(), "type", type.name()).increment();
    }
}
