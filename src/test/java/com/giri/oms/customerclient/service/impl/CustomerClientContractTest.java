package com.giri.oms.customerclient.service.impl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.giri.oms.customerclient.dto.CustomerClientResponse;
import com.giri.oms.customerclient.exception.CustomerNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same role as shipment-service's own {@code OrderClientContractTest} — see
 * that class's Javadoc for the full reasoning (a real HTTP round-trip
 * against WireMock, real RestClient serialization/deserialization, not just
 * "was this method called" the way NotificationServiceImplTest's mocked
 * CustomerClient is). This is what would catch a drift between what
 * {@link CustomerClientResponse} expects and what customer-service's real
 * {@code GET /customers/{id}} actually returns.
 * <p>
 * Retry/circuit-breaker BEHAVIOR under failure is deliberately NOT this
 * class's concern — see CustomerClientResilienceTest for that.
 */
class CustomerClientContractTest {

    private WireMockServer wireMockServer;
    private CustomerClientImpl customerClient;

    private static final Long CUSTOMER_ID = 42L;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        customerClient = CustomerClientTestSupport.buildClient("http://localhost:" + wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void getCustomer_returnsParsedResponse_onA200() {
        wireMockServer.stubFor(get(urlEqualTo("/customers/" + CUSTOMER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": %d, "firstName": "Jane", "lastName": "Doe", "email": "jane@example.com", "phone": "+15551234567"}
                                """.formatted(CUSTOMER_ID))));

        CustomerClientResponse result = customerClient.getCustomer(CUSTOMER_ID);

        assertThat(result.id()).isEqualTo(CUSTOMER_ID);
        assertThat(result.email()).isEqualTo("jane@example.com");
        assertThat(result.phone()).isEqualTo("+15551234567");
        wireMockServer.verify(getRequestedFor(urlEqualTo("/customers/" + CUSTOMER_ID)));
    }

    @Test
    void getCustomer_toleratesAMissingPhone_sinceNotEveryCustomerHasOneOnFile() {
        // Exactly the case NotificationServiceImpl.recipientAddressFor's own
        // Javadoc calls out — a customer opted into SMS with no phone number
        // recorded is a real, expected shape, not a malformed response.
        wireMockServer.stubFor(get(urlEqualTo("/customers/" + CUSTOMER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": %d, "firstName": "Jane", "lastName": "Doe", "email": "jane@example.com", "phone": null}
                                """.formatted(CUSTOMER_ID))));

        CustomerClientResponse result = customerClient.getCustomer(CUSTOMER_ID);

        assertThat(result.email()).isEqualTo("jane@example.com");
        assertThat(result.phone()).isNull();
    }

    @Test
    void getCustomer_ignoresExtraFieldsOnTheResponse() {
        // customer-service's real response almost certainly has more fields
        // (address, status, createdAt/updatedAt — see CustomerClientResponse's
        // own Javadoc) than this record models. A real deserializer must not
        // choke on fields it doesn't know about.
        wireMockServer.stubFor(get(urlEqualTo("/customers/" + CUSTOMER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": %d, "firstName": "Jane", "lastName": "Doe", "email": "jane@example.com",
                                 "phone": "+15551234567", "status": "ACTIVE", "address": {"city": "Springfield"}}
                                """.formatted(CUSTOMER_ID))));

        CustomerClientResponse result = customerClient.getCustomer(CUSTOMER_ID);

        assertThat(result.id()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void getCustomer_throwsCustomerNotFoundException_onA404() {
        wireMockServer.stubFor(get(urlEqualTo("/customers/" + CUSTOMER_ID))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> customerClient.getCustomer(CUSTOMER_ID))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining(CUSTOMER_ID.toString());

        // Exactly one request — see CustomerClientResilienceTest for the
        // assertion that a 404 specifically does NOT get retried.
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/customers/" + CUSTOMER_ID)));
    }

    @Test
    void getCustomer_sendsTheInternalServiceKeyHeader_whenBuiltViaTheRealConfig() {
        // CustomerClientConfig always attaches InternalServiceAuthInterceptor
        // (see that class's own Javadoc — a static, pre-shared key, not
        // forwarded off an inbound request the way OrderClient's interceptor
        // is). This is the one piece of this client's contract that's
        // specific to it and not shared with OrderClientContractTest.
        wireMockServer.stubFor(get(urlEqualTo("/customers/" + CUSTOMER_ID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": %d}".formatted(CUSTOMER_ID))));

        CustomerClientImpl clientWithInterceptor = CustomerClientTestSupport.buildClientWithInterceptor(
                "http://localhost:" + wireMockServer.port(), "test-service-key");

        clientWithInterceptor.getCustomer(CUSTOMER_ID);

        wireMockServer.verify(getRequestedFor(urlEqualTo("/customers/" + CUSTOMER_ID))
                .withHeader("X-Internal-Service-Key", com.github.tomakehurst.wiremock.client.WireMock.equalTo("test-service-key")));
    }
}
