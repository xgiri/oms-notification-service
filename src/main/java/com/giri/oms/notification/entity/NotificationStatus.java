package com.giri.oms.notification.entity;

/**
 * PENDING -> SENT is the happy path. PENDING -> FAILED -> (retried by
 * NotificationRetryScheduler) -> SENT is the recovered-transient-failure
 * path. FAILED -> DEAD_LETTERED is a FAILED notification that exhausted its
 * retry budget — see NotificationRetryScheduler's Javadoc for the exact
 * threshold. DEAD_LETTERED is terminal; nothing auto-retries it further —
 * see the {@code POST /notifications/{id}/resend} endpoint for how a human
 * gets one out of that state.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    DEAD_LETTERED
}
