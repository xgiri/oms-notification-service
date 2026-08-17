package com.giri.oms.customerclient.service.impl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.giri.oms.customerclient.exception.CustomerNotFoundException;
import com.giri.oms.customerclient.exception.CustomerServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same role as shipment-service's own {@code OrderClientResilienceTest} —
 * see that class's Javadoc for the full reasoning: behavior under failure
 * (retry, timeout, circuit-breaker mechanics) against a real WireMock
 * server, so the failures themselves are real HTTP events, not simulated
 * exceptions. This is what catches a misconfigured resilience4j property
 * (wrong exception class in retry-exceptions/ignore-exceptions, wrong
 * threshold) that a purely mocked CustomerClient test never could —
 * NotificationServiceImplTest's mocked CustomerClient only ever returns
 * exactly what a test tells it to.
 */
class CustomerClientResilienceTest {

    private WireMockServer wireMockServer;

    private static final Long CUSTOMER_ID = 42L;
    private static final String CUSTOMER_PATH = "/customers/" + CUSTOMER_ID;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void retriesOnce_thenThrowsCustomerServiceUnavailableException_on500() {
        wireMockServer.stubFor(get(urlEqualTo(CUSTOMER_PATH))
                .willReturn(aResponse().withStatus(500)));

        CustomerClientImpl customerClient = CustomerClientTestSupport.buildClient(baseUrl());

        assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                .isInstanceOf(CustomerServiceUnavailableException.class)
                .hasMessageContaining(CUSTOMER_ID.toString())
                // CustomerClientImpl always wraps with the underlying
                // exception as cause — a caller (or a log line) can still
                // see it really was a 5xx, not a timeout or an open circuit
                // breaker.
                .hasCauseInstanceOf(org.springframework.web.client.HttpServerErrorException.class);

        // max-attempts=2 (matches resilience4j.retry.instances.customerClient
        // in application.properties) — the original call plus exactly one
        // retry, not zero and not unbounded.
        wireMockServer.verify(2, getRequestedFor(urlEqualTo(CUSTOMER_PATH)));
    }

    @Test
    void doesNotRetry_on404_becauseItIsIgnoredByRetryConfig() {
        wireMockServer.stubFor(get(urlEqualTo(CUSTOMER_PATH))
                .willReturn(aResponse().withStatus(404)));

        CustomerClientImpl customerClient = CustomerClientTestSupport.buildClient(baseUrl());

        assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                .isInstanceOf(CustomerNotFoundException.class);

        // Exactly one request — a 404 is a business answer (the customer
        // genuinely doesn't exist), not a transient failure, so retrying it
        // would just be re-asking a question that's already been
        // definitively answered.
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(CUSTOMER_PATH)));
    }

    @Test
    void throwsCustomerServiceUnavailableException_onATimeout() {
        // Delay comfortably longer than the short read timeout this test
        // configures below (150ms) — long enough to guarantee the client
        // times out, short enough this test doesn't feel slow.
        wireMockServer.stubFor(get(urlEqualTo(CUSTOMER_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400)));

        CustomerClientImpl customerClient = CustomerClientTestSupport.buildClient(
                baseUrl(), Duration.ofMillis(100), Duration.ofMillis(150));

        assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                .isInstanceOf(CustomerServiceUnavailableException.class)
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @Test
    void opensAfterEnoughFailures_thenFailsFastWithoutCallingWireMockAgain() {
        wireMockServer.stubFor(get(urlEqualTo(CUSTOMER_PATH))
                .willReturn(aResponse().withStatus(500)));

        var built = CustomerClientTestSupport.buildClientAndRegistries(
                baseUrl(), Duration.ofMillis(300), Duration.ofMillis(800));
        CustomerClientImpl customerClient = built.client();
        CircuitBreaker circuitBreaker = built.circuitBreakerRegistry().circuitBreaker("customerClient");

        // slidingWindowSize=10 / minimumNumberOfCalls=10 / failureRateThreshold=50
        // (see CustomerClientTestSupport). 10 failing calls both meets
        // minimumNumberOfCalls AND clears failureRateThreshold (100% > 50%) —
        // the smallest loop count that actually opens the breaker. Each
        // getCustomer() call here is itself 2 WireMock requests (the retry),
        // but only ONE outcome per call counts toward the circuit breaker's
        // window (Retry wraps the call BEFORE CircuitBreaker sees it — see
        // CustomerClientImpl.getCustomer).
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                    .isInstanceOf(CustomerServiceUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestCountBeforeBreakerOpen = wireMockServer.getAllServeEvents().size();

        // The breaker is open now — this call must fail fast
        // (CallNotPermittedException, wrapped as
        // CustomerServiceUnavailableException same as any other failure —
        // see CustomerClientImpl's catch-all) without ever reaching WireMock
        // again.
        assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                .isInstanceOf(CustomerServiceUnavailableException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        assertThat(wireMockServer.getAllServeEvents()).hasSize(requestCountBeforeBreakerOpen);
    }

    @Test
    void a404_doesNotCountTowardTheCircuitBreakersFailureRate() {
        wireMockServer.stubFor(get(urlEqualTo(CUSTOMER_PATH))
                .willReturn(aResponse().withStatus(404)));

        var built = CustomerClientTestSupport.buildClientAndRegistries(
                baseUrl(), Duration.ofMillis(300), Duration.ofMillis(800));
        CustomerClientImpl customerClient = built.client();
        CircuitBreaker circuitBreaker = built.circuitBreakerRegistry().circuitBreaker("customerClient");

        // Well past the sliding window size (10) — if CustomerNotFoundException
        // were (wrongly) counted as a failure, this alone would open the
        // breaker on its own.
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                    .isInstanceOf(CustomerNotFoundException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private String baseUrl() {
        return "http://localhost:" + wireMockServer.port();
    }
}
