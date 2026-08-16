package com.giri.oms.notification.provider;

import com.giri.oms.notification.entity.NotificationChannel;

/**
 * One implementation per (channel, concrete provider) pair — see package-info
 * for the swap-without-rewrite reasoning this interface exists to enable.
 * Deliberately does NOT throw on failure — a failed send is an ordinary,
 * expected outcome (bad address, provider outage, rate limit) that
 * NotificationServiceImpl needs to record and potentially retry, not an
 * exceptional one that should unwind a Kafka listener's transaction the way
 * CustomerClient's exceptions do (see NotificationConsumer's Javadoc on why
 * those two failure modes are handled differently).
 */
public interface NotificationProvider {

    NotificationChannel channel();

    ProviderResult send(NotificationRequest request);
}
