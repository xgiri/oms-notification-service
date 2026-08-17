package com.giri.oms.customerclient.service.impl;

import com.giri.oms.customerclient.config.CustomerClientConfig;
import com.giri.oms.customerclient.config.InternalServiceAuthInterceptor;
import com.giri.oms.customerclient.exception.CustomerNotFoundException;
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
 * Same role as shipment-service's own {@code OrderClientTestSupport} (see
 * that class's Javadoc for the full reasoning — real RestClient, real
 * resilience4j CircuitBreaker/Retry, pointed at a caller-supplied base URL,
 * no Spring context) — mirrored here rather than reinvented, per this
 * service's own plan. Retry/circuit-breaker THRESHOLDS are copied unchanged
 * from application.properties' {@code customerClient} instance;
 * wait-duration is shortened for test speed only, exactly as
 * OrderClientTestSupport does.
 * <p>
 * One deliberate difference from OrderClientTestSupport:
 * {@link #buildClientWithInterceptor} exists because this client's config
 * always attaches {@link InternalServiceAuthInterceptor} (a static header,
 * not something forwarded off an inbound request — see that class's own
 * Javadoc) — there's no equivalent of OrderClientAuthForwardingTest's
 * "only this one test wires the interceptor" split, since every real call
 * this client makes always carries the header. {@link #buildClient} still
 * exists separately for tests that don't care about the header at all, to
 * keep the WireMock stubs in those tests focused on what they're actually
 * asserting.
 */
final class CustomerClientTestSupport {

    private CustomerClientTestSupport() {
    }

    static CustomerClientImpl buildClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        return buildClientAndRegistries(baseUrl, connectTimeout, readTimeout).client();
    }

    static CustomerClientImpl buildClient(String baseUrl) {
        // 300ms/800ms match application.properties' own defaults — the right
        // choice for any test that isn't specifically exercising a timeout.
        return buildClient(baseUrl, Duration.ofMillis(300), Duration.ofMillis(800));
    }

    /**
     * Goes through CustomerClientConfig's real {@code customerServiceRestClient}
     * bean method — called directly, not through a Spring context — so
     * {@link InternalServiceAuthInterceptor} is attached exactly the way
     * production does it. Every other builder in this class constructs its
     * RestClient by hand and never attaches the interceptor at all.
     */
    static CustomerClientImpl buildClientWithInterceptor(String baseUrl, String serviceApiKey) {
        RestClient restClient = new CustomerClientConfig()
                .customerServiceRestClient(RestClient.builder(), baseUrl, 300, 800, serviceApiKey);
        return new CustomerClientImpl(restClient, buildCircuitBreakerRegistry(), buildRetryRegistry());
    }

    /**
     * Same client {@link #buildClient} returns, plus the registries it was
     * built from — for tests that need to inspect circuit breaker state
     * directly rather than only observing it indirectly through
     * CustomerClient's thrown exceptions.
     */
    record ClientAndRegistries(CustomerClientImpl client, CircuitBreakerRegistry circuitBreakerRegistry,
                                RetryRegistry retryRegistry) {
    }

    static ClientAndRegistries buildClientAndRegistries(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        RestClient restClient = buildRestClient(baseUrl, connectTimeout, readTimeout);
        CircuitBreakerRegistry circuitBreakerRegistry = buildCircuitBreakerRegistry();
        RetryRegistry retryRegistry = buildRetryRegistry();
        CustomerClientImpl client = new CustomerClientImpl(restClient, circuitBreakerRegistry, retryRegistry);
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
        // Matches resilience4j.circuitbreaker.instances.customerClient in
        // application.properties exactly, except wait-duration-in-open-state
        // — see OrderClientTestSupport's own Javadoc for why that value
        // doesn't need to be reproduced here either. minimumNumberOfCalls is
        // set explicitly and equal to slidingWindowSize for the same reason
        // flagged there: resilience4j's own default (100) would silently
        // defeat a slidingWindowSize of 10.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(CustomerNotFoundException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    private static RetryRegistry buildRetryRegistry() {
        // max-attempts/retry-exceptions/ignore-exceptions match
        // resilience4j.retry.instances.customerClient exactly — wait-duration
        // is 50ms here instead of prod's 200ms, purely for test speed.
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(50))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(CustomerNotFoundException.class)
                .build();
        return RetryRegistry.of(config);
    }
}
