package com.giri.oms.notification.service;

import com.giri.oms.notification.entity.NotificationType;

import java.util.Map;
import java.util.UUID;

/**
 * The orchestration entry point every event consumer (see
 * notification.consumer) calls into: idempotency check, preference check,
 * customer lookup, compose, send, record — see NotificationServiceImpl for
 * the exact ordering and why it's ordered that way.
 */
public interface NotificationService {

    /**
     * @param eventId the triggering Kafka event's own id — the idempotency
     *                key (paired with {@code type}, see ProcessedEvent).
     * @param type the notification type, which also selects the template
     *             (see NotificationComposer) and the preference check.
     * @param customerId whose contact info to resolve via CustomerClient.
     * @param orderId nullable — see Notification.orderId's own Javadoc.
     * @param templateVariables passed straight through to NotificationComposer;
     *                          this method doesn't interpret them.
     */
    void processEvent(UUID eventId, NotificationType type,
                       Long customerId, Long orderId, Map<String, Object> templateVariables);

    /**
     * Support/ops use only — see notification.controller's
     * {@code POST /notifications/{id}/resend}. Only valid from FAILED or
     * DEAD_LETTERED (see NotificationStatus's own Javadoc); resending a
     * PENDING or already-SENT notification isn't a meaningful operation.
     * <p>
     * <b>Known Phase 1 limitation:</b> template variables aren't persisted
     * on the Notification row, so this reconstructs only what it can infer
     * from the stored columns (currently just {@code orderId}) rather than
     * the exact original composition inputs. This is fine for
     * ORDER_CONFIRMED (its only variable IS orderId) but would under-populate
     * a richer future notification type's template. Persisting the original
     * template variables (e.g. as a JSON column) is the real fix, deferred
     * out of Phase 1 scope.
     */
    void resend(Long notificationId);
}