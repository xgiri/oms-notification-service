package com.giri.oms.orderclient.service.impl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.giri.oms.orderclient.exception.OrderNotFoundException;
import com.giri.oms.orderclient.exception.OrderServiceUnavailableException;
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
 * Same role as {@code CustomerClientResilienceTest} — behavior under
 * failure (retry, timeout, circuit-breaker mechanics) against a real
 * WireMock server, so the failures themselves are real HTTP events, not
 * simulated exceptions. Catches a misconfigured resilience4j property that
 * a purely mocked OrderClient test never could.
 */
class OrderClientResilienceTest {

    private WireMockServer wireMockServer;

    private static final Long ORDER_ID = 42L;
    private static final String ORDER_PATH = "/orders/" + ORDER_ID;

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
    void retriesOnce_thenThrowsOrderServiceUnavailableException_on500() {
        wireMockServer.stubFor(get(urlEqualTo(ORDER_PATH))
                .willReturn(aResponse().withStatus(500)));

        OrderClientImpl orderClient = OrderClientTestSupport.buildClient(baseUrl());

        assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasMessageContaining(ORDER_ID.toString())
                .hasCauseInstanceOf(org.springframework.web.client.HttpServerErrorException.class);

        // max-attempts=2 (matches resilience4j.retry.instances.orderClient in
        // application.properties) — the original call plus exactly one
        // retry, not zero and not unbounded.
        wireMockServer.verify(2, getRequestedFor(urlEqualTo(ORDER_PATH)));
    }

    @Test
    void doesNotRetry_on404_becauseItIsIgnoredByRetryConfig() {
        wireMockServer.stubFor(get(urlEqualTo(ORDER_PATH))
                .willReturn(aResponse().withStatus(404)));

        OrderClientImpl orderClient = OrderClientTestSupport.buildClient(baseUrl());

        assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                .isInstanceOf(OrderNotFoundException.class);

        // Exactly one request — a 404 is a business answer (the order
        // genuinely doesn't exist), not a transient failure, so retrying it
        // would just be re-asking a question that's already been
        // definitively answered.
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(ORDER_PATH)));
    }

    @Test
    void throwsOrderServiceUnavailableException_onATimeout() {
        // Delay comfortably longer than the short read timeout this test
        // configures below (150ms) — long enough to guarantee the client
        // times out, short enough this test doesn't feel slow.
        wireMockServer.stubFor(get(urlEqualTo(ORDER_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400)));

        OrderClientImpl orderClient = OrderClientTestSupport.buildClient(
                baseUrl(), Duration.ofMillis(100), Duration.ofMillis(150));

        assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @Test
    void opensAfterEnoughFailures_thenFailsFastWithoutCallingWireMockAgain() {
        wireMockServer.stubFor(get(urlEqualTo(ORDER_PATH))
                .willReturn(aResponse().withStatus(500)));

        var built = OrderClientTestSupport.buildClientAndRegistries(
                baseUrl(), Duration.ofMillis(300), Duration.ofMillis(800));
        OrderClientImpl orderClient = built.client();
        CircuitBreaker circuitBreaker = built.circuitBreakerRegistry().circuitBreaker("orderClient");

        // slidingWindowSize=10 / minimumNumberOfCalls=10 / failureRateThreshold=50
        // (see OrderClientTestSupport). 10 failing calls both meets
        // minimumNumberOfCalls AND clears failureRateThreshold (100% > 50%) —
        // the smallest loop count that actually opens the breaker. Each
        // getOrder() call here is itself 2 WireMock requests (the retry),
        // but only ONE outcome per call counts toward the circuit breaker's
        // window (Retry wraps the call BEFORE CircuitBreaker sees it — see
        // OrderClientImpl.getOrder).
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                    .isInstanceOf(OrderServiceUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestCountBeforeBreakerOpen = wireMockServer.getAllServeEvents().size();

        // The breaker is open now — this call must fail fast
        // (CallNotPermittedException, wrapped as
        // OrderServiceUnavailableException same as any other failure — see
        // OrderClientImpl's catch-all) without ever reaching WireMock again.
        assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                .isInstanceOf(OrderServiceUnavailableException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        assertThat(wireMockServer.getAllServeEvents()).hasSize(requestCountBeforeBreakerOpen);
    }

    @Test
    void a404_doesNotCountTowardTheCircuitBreakersFailureRate() {
        wireMockServer.stubFor(get(urlEqualTo(ORDER_PATH))
                .willReturn(aResponse().withStatus(404)));

        var built = OrderClientTestSupport.buildClientAndRegistries(
                baseUrl(), Duration.ofMillis(300), Duration.ofMillis(800));
        OrderClientImpl orderClient = built.client();
        CircuitBreaker circuitBreaker = built.circuitBreakerRegistry().circuitBreaker("orderClient");

        // Well past the sliding window size (10) — if OrderNotFoundException
        // were (wrongly) counted as a failure, this alone would open the
        // breaker on its own.
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                    .isInstanceOf(OrderNotFoundException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private String baseUrl() {
        return "http://localhost:" + wireMockServer.port();
    }
}
