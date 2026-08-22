package com.giri.oms.notification.service.impl;

import com.giri.oms.customerclient.dto.CustomerClientResponse;
import com.giri.oms.customerclient.service.CustomerClient;
import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.entity.ProcessedEvent;
import com.giri.oms.notification.exception.IllegalNotificationStateException;
import com.giri.oms.notification.exception.NotificationNotFoundException;
import com.giri.oms.notification.metrics.NotificationMetrics;
import com.giri.oms.notification.provider.NotificationProvider;
import com.giri.oms.notification.provider.NotificationRequest;
import com.giri.oms.notification.provider.ProviderResult;
import com.giri.oms.notification.repository.NotificationRepository;
import com.giri.oms.notification.repository.ProcessedEventRepository;
import com.giri.oms.notification.service.NotificationComposer;
import com.giri.oms.notification.service.NotificationPreferenceService;
import com.giri.oms.notification.service.NotificationService;
import com.giri.oms.security.UnsubscribeTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one path every notification in this system goes through. Ordering
 * matters and is deliberate:
 * <ol>
 *   <li><b>Idempotency check first</b>, before anything else, including
 *   before the preference check — a redelivered event that's already been
 *   fully processed (every opted-in channel sent, everything recorded)
 *   must be a total no-op, not re-evaluate preferences that might have
 *   changed since (a customer opting out between the original delivery and
 *   a redelivery shouldn't retroactively un-send something already
 *   sent).</li>
 *   <li><b>Preference check second, across every registered channel at
 *   once</b>, before the customer lookup — no reason to pay for a
 *   CustomerClient round-trip (and count it toward that client's circuit
 *   breaker) if every registered channel is going to be suppressed anyway.
 *   "Registered" means "has a {@link NotificationProvider} bean" — see
 *   {@link #registeredChannels()} — so this stays correct as channels are
 *   added (SMS in Phase 4) without a code change here.</li>
 *   <li><b>Customer lookup third, once, shared across every opted-in
 *   channel.</b> CustomerServiceUnavailableException propagates OUT of
 *   this method uncaught (see the class-level {@code @Transactional} and
 *   NotificationConsumer's Javadoc) — this is a genuine "try again later"
 *   case, and letting the Kafka listener's own error handling (retry, then
 *   DLT) own that decision is more correct than this service inventing its
 *   own retry loop for a dependency failure. Failing here means NO channel
 *   gets attempted, not just email — the whole event redelivers and every
 *   channel gets a fair second attempt together.</li>
 *   <li><b>Per opted-in channel: resolve a recipient address, then
 *   compose, then send, then record — one Notification row per channel.</b>
 *   A channel with no recipient address on file for this customer (e.g.
 *   opted into SMS but no phone number recorded) is skipped with a log
 *   line, not recorded as a FAILED Notification — there was nothing to
 *   attempt, so "attempted send" (see this service's README §8) doesn't
 *   apply. Every channel's Notification row lands in the SAME transaction
 *   as the single ProcessedEvent row written after the whole loop — a
 *   crash mid-loop (some channels sent, some not yet attempted) is the one
 *   gap this design doesn't close, same as the single-channel version of
 *   this tradeoff (see this service's own README on the transactional
 *   outbox's mirror-image on the consuming side).</li>
 *   <li><b>ProcessedEvent is written once per event, after every channel
 *   has been attempted</b> — not once per channel. It's keyed on
 *   (event_id, notification_type), same granularity as before Phase 4;
 *   a redelivery must not re-attempt ANY channel, not just the ones that
 *   already succeeded.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceService preferenceService;
    private final CustomerClient customerClient;
    private final NotificationComposer composer;
    private final List<NotificationProvider> providers;
    private final UnsubscribeTokenService unsubscribeTokenService;
    private final Clock clock;
    private final NotificationMetrics notificationMetrics;

    @Override
    @Transactional
    public void processEvent(UUID eventId, NotificationType type, Long customerId, Long orderId,
                              Map<String, Object> templateVariables, long eventTimestampMillis) {
        if (processedEventRepository.existsByEventIdAndNotificationType(eventId, type.name())) {
            log.info("Skipping event id={} type={} — already processed (redelivery)", eventId, type);
            return;
        }

        List<NotificationChannel> optedInChannels = registeredChannels().stream()
                .filter(channel -> preferenceService.isOptedIn(customerId, type, channel))
                .toList();

        if (optedInChannels.isEmpty()) {
            log.info("Skipping notification for customer id={} type={} — opted out of every registered channel",
                    customerId, type);
            markProcessed(eventId, type);
            return;
        }

        CustomerClientResponse customer = customerClient.getCustomer(customerId);

        for (NotificationChannel channel : optedInChannels) {
            String recipientAddress = recipientAddressFor(customer, channel);
            if (recipientAddress == null) {
                log.warn("Skipping channel={} for customer id={} type={} — no {} on file for this customer",
                        channel, customerId, type, channel);
                continue;
            }
            sendAndRecord(type, customerId, orderId, channel, recipientAddress, templateVariables, eventTimestampMillis);
        }

        markProcessed(eventId, type);
    }

    /**
     * Every channel with a registered {@link NotificationProvider} bean —
     * derived from {@code providers} rather than a hardcoded list, so
     * adding a provider (TwilioSmsProvider in Phase 4) makes
     * {@link #processEvent} consider that channel with no change here.
     * PUSH is the current example of the other direction this cuts: with
     * {@code app.notification.push.enabled=false} (the default — see
     * {@code FcmPushProvider}'s own Javadoc), no PUSH provider bean exists,
     * so PUSH is simply never a candidate here regardless of any stored
     * preference — a customer "opted into" a channel with no live provider
     * is exactly as inert as one with no address on file.
     */
    private List<NotificationChannel> registeredChannels() {
        return providers.stream().map(NotificationProvider::channel).toList();
    }

    /**
     * {@code null} means "this customer has no address on file for this
     * channel" — a data-availability gap, not a provider failure — see
     * {@link #processEvent}'s own Javadoc on why that's skipped rather than
     * recorded as FAILED. The PUSH case returns {@code pushToken()}, same
     * shape as EMAIL/SMS — but see {@link CustomerClientResponse}'s own
     * Javadoc: customer-service doesn't expose a push token field yet at
     * all, so this will always evaluate to {@code null} today, meaning
     * every PUSH-opted-in customer takes the same "no address on file,
     * skip" path SMS-without-a-phone-number already takes. That's a
     * deliberate, safe default — not a bug to fix here — until
     * customer-service ships the field this genuinely has something to
     * read.
     */
    private String recipientAddressFor(CustomerClientResponse customer, NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> customer.email();
            case SMS -> customer.phone();
            case PUSH -> customer.pushToken();
        };
    }

    /**
     * Compose, send, and record ONE channel's Notification row — the
     * per-channel unit {@link #processEvent}'s loop repeats for every
     * opted-in channel. Split out from {@code processEvent} itself once
     * that method needed to do this more than once per event (Phase 4) —
     * see {@link #processEvent}'s own Javadoc for how the two fit
     * together, including why {@code markProcessed} deliberately stays
     * in the caller, called once after this runs for every channel, not
     * once per call here.
     * <p>
     * {@code eventTimestampMillis} is ONLY used to compute time-to-delivery
     * on a successful send — see {@link NotificationMetrics#recordTimeToDelivery}.
     * {@code resend} does NOT go through this method and has no equivalent
     * timing call — see that method's own Javadoc on why: there's no
     * original event timestamp persisted on the {@code Notification} row to
     * recover it from, and time-to-delivery is meant to measure the FIRST
     * attempt specifically, not a manually-retried one (which the existing
     * retry-queue-depth/dead-lettered metrics already cover).
     */
    private void sendAndRecord(NotificationType type, Long customerId, Long orderId, NotificationChannel channel,
                                String recipientAddress, Map<String, Object> templateVariables,
                                long eventTimestampMillis) {
        NotificationProvider provider = providerFor(channel);
        NotificationRequest request = composer.compose(type, channel, "en", recipientAddress,
                withUnsubscribeLink(templateVariables, customerId, type, channel));

        long startNanos = System.nanoTime();
        ProviderResult result = provider.send(request);
        long durationNanos = System.nanoTime() - startNanos;

        Notification notification = new Notification();
        notification.setType(type);
        notification.setChannel(channel);
        notification.setCustomerId(customerId);
        notification.setRecipientAddress(recipientAddress);
        notification.setOrderId(orderId);
        notification.setProviderName(provider.getClass().getSimpleName());

        if (result.success()) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setProviderMessageId(result.providerMessageId());
            notification.setSentAt(LocalDateTime.now(clock));
            notificationMetrics.recordSent(channel, type, durationNanos);
            notificationMetrics.recordTimeToDelivery(channel, type,
                    clock.millis() - eventTimestampMillis);
        } else {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setLastError(result.errorMessage());
            notificationMetrics.recordFailed(channel, type);
        }

        notificationRepository.save(notification);

        if (!result.success()) {
            // Deliberately NOT thrown as an exception — a failed SEND is
            // recorded and left for NotificationRetryScheduler, not treated
            // as a reason to fail the whole Kafka message (which would
            // trigger Kafka-level redelivery and risk a duplicate attempt
            // on every OTHER channel too, once this event's ProcessedEvent
            // row is written after the full loop — see processEvent's own
            // Javadoc's ordering note).
            log.warn("Notification send failed for customer id={} type={} channel={}: {}",
                    customerId, type, channel, result.errorMessage());
        }
    }

    /**
     * Merges in the one template variable this class itself owns (every
     * other variable in {@code templateVariables} is opaque to this class
     * — see NotificationComposer's Javadoc). {@code unsubscribeUrl} is
     * computed here, not left to the caller, because building it needs
     * exactly the three things this method already has in scope
     * (customerId, type, channel) and nothing a Kafka consumer or the
     * resend path should have to know how to assemble themselves — see
     * UnsubscribeTokenService for what actually goes into the link.
     * {@code templateVariables} itself is never mutated — callers may pass
     * an immutable {@code Map.of(...)} (see OrderNotificationConsumer).
     */
    private Map<String, Object> withUnsubscribeLink(Map<String, Object> templateVariables, Long customerId,
                                                      NotificationType type, NotificationChannel channel) {
        Map<String, Object> merged = new HashMap<>(templateVariables);
        merged.put("unsubscribeUrl", unsubscribeTokenService.buildUnsubscribeLink(customerId, type, channel));
        return merged;
    }

    private void markProcessed(UUID eventId, NotificationType type) {
        ProcessedEvent processedEvent = new ProcessedEvent();
        processedEvent.setId(UUID.randomUUID());
        processedEvent.setEventId(eventId);
        processedEvent.setNotificationType(type.name());
        processedEventRepository.save(processedEvent);
    }

    @Override
    @Transactional
    public void resend(Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        // See this method's own interface Javadoc — only FAILED/DEAD_LETTERED
        // is a meaningful resend target. NotificationRetryScheduler never
        // trips this (it only ever calls resend() on rows it already
        // confirmed are FAILED — see that class's own Javadoc); this exists
        // for the public POST /resend endpoint, which has no such guarantee
        // about what a caller passes in.
        if (notification.getStatus() != NotificationStatus.FAILED
                && notification.getStatus() != NotificationStatus.DEAD_LETTERED) {
            throw new IllegalNotificationStateException(
                    "Notification %d cannot be resent from status %s — only FAILED or DEAD_LETTERED can be resent"
                            .formatted(notificationId, notification.getStatus()));
        }

        // See this method's own Javadoc on the interface — reconstructed,
        // not the original composition inputs.
        Map<String, Object> templateVariables = notification.getOrderId() != null
                ? Map.of("orderId", notification.getOrderId())
                : Map.of();

        NotificationProvider provider = providerFor(notification.getChannel());
        NotificationRequest request = composer.compose(
                notification.getType(), notification.getChannel(), "en", notification.getRecipientAddress(),
                withUnsubscribeLink(templateVariables, notification.getCustomerId(),
                        notification.getType(), notification.getChannel()));

        long startNanos = System.nanoTime();
        ProviderResult result = provider.send(request);
        long durationNanos = System.nanoTime() - startNanos;

        notification.setRetryCount(notification.getRetryCount() + 1);
        if (result.success()) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setProviderMessageId(result.providerMessageId());
            notification.setSentAt(LocalDateTime.now(clock));
            notification.setLastError(null);
            notificationMetrics.recordSent(notification.getChannel(), notification.getType(), durationNanos);
        } else {
            notification.setLastError(result.errorMessage());
            notificationMetrics.recordFailed(notification.getChannel(), notification.getType());
            log.warn("Resend failed for notification id={}: {}", notificationId, result.errorMessage());
        }

        notificationRepository.save(notification);
    }

    private NotificationProvider providerFor(NotificationChannel channel) {
        return providers.stream()
                .filter(p -> p.channel() == channel)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No NotificationProvider registered for channel " + channel));
    }
}
