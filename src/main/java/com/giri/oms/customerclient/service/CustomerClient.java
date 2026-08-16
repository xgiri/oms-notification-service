package com.giri.oms.customerclient.service;

import com.giri.oms.customerclient.dto.CustomerClientResponse;

/**
 * notification-service's client to customer-service — recipient resolution
 * for every notification this service sends. Called from
 * notification.consumer's Kafka listener threads, never from a servlet
 * request thread — see {@link com.giri.oms.customerclient.config.InternalServiceAuthInterceptor}'s
 * Javadoc for why that distinction drove this client's auth strategy.
 */
public interface CustomerClient {

    /**
     * @throws com.giri.oms.customerclient.exception.CustomerNotFoundException customer-service
     *         returned 404 — the customer genuinely doesn't exist. Never retried,
     *         never counted against the circuit breaker (see CustomerClientImpl).
     * @throws com.giri.oms.customerclient.exception.CustomerServiceUnavailableException
     *         customer-service could not be reached in time — a timeout, a 5xx, or
     *         the circuit breaker is currently open. No fallback/stale-data
     *         return — a notification composed with a wrong or cached email
     *         is worse than one that's simply delayed by a Kafka redelivery.
     */
    CustomerClientResponse getCustomer(Long customerId);
}
