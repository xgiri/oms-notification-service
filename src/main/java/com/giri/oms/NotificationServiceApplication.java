package com.giri.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A new, standalone notification service for OMS — not extracted from
 * oms-main, built fresh to match the shape every other service in this
 * system already established: its own database, its own Kafka consumer
 * group, JWT verification against oms-main's JWKS endpoint (never issuing
 * one), and resilient clients (CustomerClient, OrderClient) for its two
 * synchronous dependencies.
 * <p>
 * Phase 1 scope (see this service's README for the full staged plan):
 * one channel (email, via SmtpEmailProvider), one event type
 * (OrderConfirmed, via OrderConfirmedNotificationConsumer), idempotent
 * processing, and the opt-out data model in place even though its
 * enforcement API isn't fully built out yet.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
