package com.giri.oms.orderclient.service.impl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.giri.oms.orderclient.dto.OrderClientResponse;
import com.giri.oms.orderclient.exception.OrderNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same role as {@code CustomerClientContractTest} (and, further back,
 * shipment-service's own {@code OrderClientContractTest} — a different
 * client of the same name/shape in a different repo, not this class) — a
 * real HTTP round-trip against WireMock, real RestClient serialization/
 * deserialization, not just "was this method called" the way
 * NotificationServiceImplTest's mocked OrderClient is. This is what would
 * catch a drift between what {@link OrderClientResponse} expects and what
 * oms-main's real {@code GET /orders/{id}} actually returns.
 * <p>
 * Retry/circuit-breaker BEHAVIOR under failure is deliberately NOT this
 * class's concern — see OrderClientResilienceTest for that.
 */
class OrderClientContractTest {

    private WireMockServer wireMockServer;
    private OrderClientImpl orderClient;

    private static final Long ORDER_ID = 42L;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        orderClient = OrderClientTestSupport.buildClient("http://localhost:" + wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void getOrder_returnsParsedResponse_onA200() {
        wireMockServer.stubFor(get(urlEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": %d, \"customerId\": 7}".formatted(ORDER_ID))));

        OrderClientResponse result = orderClient.getOrder(ORDER_ID);

        assertThat(result.id()).isEqualTo(ORDER_ID);
        assertThat(result.customerId()).isEqualTo(7L);
        wireMockServer.verify(getRequestedFor(urlEqualTo("/orders/" + ORDER_ID)));
    }

    @Test
    void getOrder_ignoresExtraFieldsOnTheResponse() {
        // oms-main's real OrderResponse has many more fields (status,
        // totalAmount, items, ...) than OrderClientResponse models — see
        // that record's own Javadoc. A real deserializer must not choke on
        // fields it doesn't know about.
        wireMockServer.stubFor(get(urlEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": %d, "customerId": 7, "status": "CONFIRMED", "totalAmount": 100.00, "items": []}
                                """.formatted(ORDER_ID))));

        OrderClientResponse result = orderClient.getOrder(ORDER_ID);

        assertThat(result.id()).isEqualTo(ORDER_ID);
        assertThat(result.customerId()).isEqualTo(7L);
    }

    @Test
    void getOrder_throwsOrderNotFoundException_onA404() {
        wireMockServer.stubFor(get(urlEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> orderClient.getOrder(ORDER_ID))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(ORDER_ID.toString());

        // Exactly one request — see OrderClientResilienceTest for the
        // assertion that a 404 specifically does NOT get retried.
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/orders/" + ORDER_ID)));
    }

    @Test
    void getOrder_sendsTheInternalServiceKeyHeader_whenBuiltViaTheRealConfig() {
        // OrderClientConfig always attaches this package's own
        // InternalServiceAuthInterceptor (see that class's Javadoc — a
        // static, pre-shared key, not forwarded off an inbound request).
        wireMockServer.stubFor(get(urlEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": %d, \"customerId\": 7}".formatted(ORDER_ID))));

        OrderClientImpl clientWithInterceptor = OrderClientTestSupport.buildClientWithInterceptor(
                "http://localhost:" + wireMockServer.port(), "test-service-key");

        clientWithInterceptor.getOrder(ORDER_ID);

        wireMockServer.verify(getRequestedFor(urlEqualTo("/orders/" + ORDER_ID))
                .withHeader("X-Internal-Service-Key", equalTo("test-service-key")));
    }
}
