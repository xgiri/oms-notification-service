/**
 * Swappable delivery-channel abstraction — same reasoning as
 * shipment-service/oms-main's *Client interfaces: business logic
 * (NotificationServiceImpl, NotificationComposer) never calls a provider
 * SDK directly. {@link com.giri.oms.notification.provider.SmtpEmailProvider}
 * is the one implementation Phase 1 ships (SMTP via JavaMailSender, pointed
 * at a local dev catcher — see application.properties); swapping to a real
 * provider (SES, SendGrid, Twilio, FCM, ...) later means a new
 * implementation of {@link com.giri.oms.notification.provider.NotificationProvider},
 * not a change to anything upstream of it.
 */
package com.giri.oms.notification.provider;
