/**
 * No outbox package here (unlike shipment-service, which has one) —
 * nothing outside this service needs to react to a notification being
 * sent/failed yet. If/when something does (a support dashboard, an
 * analytics service, a "notify ops when delivery failures spike" consumer),
 * add one the same way shipment-service's was added: NotificationSentEvent/
 * NotificationFailedEvent records, an OutboxService.enqueue call inside the
 * same transaction as the Notification row's own status update, a new
 * Kafka topic. Not built speculatively now.
 */
package com.giri.oms.notification.service;
