package com.giri.oms.orderclient.service.impl;

import com.giri.oms.orderclient.config.InternalServiceAuthInterceptor;
import com.giri.oms.orderclient.config.OrderClientConfig;
import com.giri.oms.orderclient.exception.OrderNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Same role as this service's own {@code CustomerClientTestSupport} (see
 * that class's Javadoc, and shipment-service's {@code OrderClientTestSupport}
 * it was itself mirrored from) — real RestClient, real resilience4j
 * CircuitBreaker/Retry, pointed at a caller-supplied base URL, no Spring
 * context. Retry/circuit-breaker THRESHOLDS are copied unchanged from
 * application.properties' {@code orderClient} instance (identical to
 * {@code customerClient}'s — see that file); wait-duration is shortened for
 * test speed only.
 * <p>
 * As with CustomerClientTestSupport, {@link #buildClientWithInterceptor}
 * exists because this client's own copy of {@code InternalServiceAuthInterceptor}
 * (a separate class from customerclient's — see its own Javadoc on why this
 * system doesn't share one copy across *client packages) always attaches a
 * static header, unlike shipment-service's AuthHeaderForwardingInterceptor
 * which only some tests need to wire up.
 */
final class OrderClientTestSupport {

    private OrderClientTestSupport() {
    }

    static OrderClientImpl buildClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        return buildClientAndRegistries(baseUrl, connectTimeout, readTimeout).client();
    }

    static OrderClientImpl buildClient(String baseUrl) {
        // 300ms/800ms match application.properties' own defaults — the right
        // choice for any test that isn't specifically exercising a timeout.
        return buildClient(baseUrl, Duration.ofMillis(300), Duration.ofMillis(800));
    }

    /**
     * Goes through OrderClientConfig's real {@code orderServiceRestClient}
     * bean method — called directly, not through a Spring context — so
     * {@link InternalServiceAuthInterceptor} is attached exactly the way
     * production does it.
     */
    static OrderClientImpl buildClientWithInterceptor(String baseUrl, String serviceApiKey) {
        RestClient restClient = new OrderClientConfig()
                .orderServiceRestClient(RestClient.builder(), baseUrl, 300, 800, serviceApiKey);
        return new OrderClientImpl(restClient, buildCircuitBreakerRegistry(), buildRetryRegistry());
    }

    /**
     * Same client {@link #buildClient} returns, plus the registries it was
     * built from — for tests that need to inspect circuit breaker state
     * directly rather than only observing it indirectly through
     * OrderClient's thrown exceptions.
     */
    record ClientAndRegistries(OrderClientImpl client, CircuitBreakerRegistry circuitBreakerRegistry,
                                RetryRegistry retryRegistry) {
    }

    static ClientAndRegistries buildClientAndRegistries(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        RestClient restClient = buildRestClient(baseUrl, connectTimeout, readTimeout);
        CircuitBreakerRegistry circuitBreakerRegistry = buildCircuitBreakerRegistry();
        RetryRegistry retryRegistry = buildRetryRegistry();
        OrderClientImpl client = new OrderClientImpl(restClient, circuitBreakerRegistry, retryRegistry);
        return new ClientAndRegistries(client, circuitBreakerRegistry, retryRegistry);
    }

    private static RestClient buildRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private static CircuitBreakerRegistry buildCircuitBreakerRegistry() {
        // Matches resilience4j.circuitbreaker.instances.orderClient in
        // application.properties exactly, except wait-duration-in-open-state
        // — see CustomerClientTestSupport's own Javadoc for why that value
        // doesn't need to be reproduced here either. minimumNumberOfCalls is
        // set explicitly and equal to slidingWindowSize for the same reason
        // flagged there.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(OrderNotFoundException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    private static RetryRegistry buildRetryRegistry() {
        // max-attempts/retry-exceptions/ignore-exceptions match
        // resilience4j.retry.instances.orderClient exactly — wait-duration is
        // 50ms here instead of prod's 200ms, purely for test speed.
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(50))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(OrderNotFoundException.class)
                .build();
        return RetryRegistry.of(config);
    }
}
