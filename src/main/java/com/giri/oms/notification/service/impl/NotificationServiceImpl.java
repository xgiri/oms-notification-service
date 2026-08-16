package com.giri.oms.notification.service.impl;

import com.giri.oms.customerclient.dto.CustomerClientResponse;
import com.giri.oms.customerclient.service.CustomerClient;
import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationStatus;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.entity.ProcessedEvent;
import com.giri.oms.notification.exception.NotificationNotFoundException;
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
 *   fully processed (sent, recorded, everything) must be a total no-op,
 *   not re-evaluate preferences that might have changed since (a customer
 *   opting out between the original delivery and a redelivery shouldn't
 *   retroactively un-send something already sent).</li>
 *   <li><b>Preference check second</b>, before the customer lookup — no
 *   reason to pay for a CustomerClient round-trip (and count it toward that
 *   client's circuit breaker) for a notification that's going to be
 *   suppressed anyway.</li>
 *   <li><b>Customer lookup third.</b> CustomerServiceUnavailableException
 *   propagates OUT of this method uncaught (see the class-level
 *   {@code @Transactional} and NotificationConsumer's Javadoc) — this is a
 *   genuine "try again later" case, and letting the Kafka listener's own
 *   error handling (retry, then DLT) own that decision is more correct than
 *   this service inventing its own retry loop for a dependency failure.</li>
 *   <li><b>Compose, then send, then record — in that order, one
 *   transaction.</b> The Notification row (PENDING -> SENT/FAILED) and the
 *   ProcessedEvent row are written in the SAME transaction, so a crash
 *   between "the email was sent" and "the row says SENT" is the one gap
 *   this design doesn't close — see this service's own README on why (the
 *   same at-least-once-delivery tradeoff transactional outbox exists to
 *   avoid on the PRODUCING side; this is the consuming side's mirror-image
 *   version of that same fundamental limit).</li>
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

    @Override
    @Transactional
    public void processEvent(UUID eventId, NotificationType type, Long customerId, Long orderId,
                              Map<String, Object> templateVariables) {
        if (processedEventRepository.existsByEventIdAndNotificationType(eventId, type.name())) {
            log.info("Skipping event id={} type={} — already processed (redelivery)", eventId, type);
            return;
        }

        // Phase 1 ships EMAIL only — see NotificationChannel's own Javadoc.
        // A future multi-channel phase would loop over every opted-in
        // channel here instead of hardcoding one.
        NotificationChannel channel = NotificationChannel.EMAIL;

        if (!preferenceService.isOptedIn(customerId, type, channel)) {
            log.info("Skipping notification for customer id={} type={} — opted out", customerId, type);
            markProcessed(eventId, type);
            return;
        }

        CustomerClientResponse customer = customerClient.getCustomer(customerId);

        NotificationProvider provider = providerFor(channel);
        NotificationRequest request = composer.compose(type, "en", customer.email(),
                withUnsubscribeLink(templateVariables, customerId, type, channel));
        ProviderResult result = provider.send(request);

        Notification notification = new Notification();
        notification.setType(type);
        notification.setChannel(channel);
        notification.setCustomerId(customerId);
        notification.setRecipientAddress(customer.email());
        notification.setOrderId(orderId);
        notification.setProviderName(provider.getClass().getSimpleName());

        if (result.success()) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setProviderMessageId(result.providerMessageId());
            notification.setSentAt(LocalDateTime.now(clock));
        } else {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setLastError(result.errorMessage());
        }

        notificationRepository.save(notification);
        markProcessed(eventId, type);

        if (!result.success()) {
            // Deliberately NOT thrown as an exception — a failed SEND is
            // recorded and left for NotificationRetryScheduler, not treated
            // as a reason to fail the whole Kafka message (which would
            // trigger Kafka-level redelivery and risk a duplicate attempt
            // once the idempotency row above has already been written for
            // this event — see the class Javadoc's ordering note).
            log.warn("Notification send failed for customer id={} type={}: {}", customerId, type, result.errorMessage());
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

        // See this method's own Javadoc on the interface — reconstructed,
        // not the original composition inputs.
        Map<String, Object> templateVariables = notification.getOrderId() != null
                ? Map.of("orderId", notification.getOrderId())
                : Map.of();

        NotificationProvider provider = providerFor(notification.getChannel());
        NotificationRequest request = composer.compose(
                notification.getType(), "en", notification.getRecipientAddress(),
                withUnsubscribeLink(templateVariables, notification.getCustomerId(),
                        notification.getType(), notification.getChannel()));
        ProviderResult result = provider.send(request);

        notification.setRetryCount(notification.getRetryCount() + 1);
        if (result.success()) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setProviderMessageId(result.providerMessageId());
            notification.setSentAt(LocalDateTime.now(clock));
            notification.setLastError(null);
        } else {
            notification.setLastError(result.errorMessage());
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
