package com.giri.oms.customerclient.config;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * <b>Deliberately NOT a copy of shipment-service's/oms-main's
 * AuthHeaderForwardingInterceptor pattern — that pattern does not work
 * here, and using it anyway would be a silent, always-on bug, not a
 * limitation worth living with.</b>
 * <p>
 * AuthHeaderForwardingInterceptor forwards the Authorization header off the
 * CURRENT inbound servlet request (via RequestContextHolder) — it works for
 * shipment-service's OrderClient because that client's one call site
 * (ShipmentServiceImpl.createShipment) is always REST-triggered, so there's
 * always a real inbound request with a real user's token to forward.
 * <p>
 * notification-service's CustomerClient has no equivalent inbound request in
 * its primary (really, only, as of Phase 1) call path — every call
 * originates from a Kafka listener thread reacting to OrderConfirmed et al.
 * (see notification.consumer), never from a user's HTTP request.
 * AuthHeaderForwardingInterceptor's own Javadoc in oms-main/shipment-service
 * already names this exact scenario as its known limitation ("a future
 * async call site... would forward nothing") — copying that class here
 * would mean every single CustomerClient call silently sends NO
 * Authorization header, permanently, not as an edge case.
 * <p>
 * This service legitimately has no user identity to act on behalf of — it's
 * acting on ITS OWN identity ("I am notification-service, let me look up
 * this customer's contact info"), which is exactly what service-to-service
 * (machine-to-machine) authentication is for, as distinct from
 * forwarding/delegating a user's own token.
 * <p>
 * <b>This class sends a static, pre-shared API key as a custom header —
 * this is a placeholder implementation, not a recommendation.</b> A shared
 * static secret is the simplest thing that could possibly authenticate a
 * service, but it doesn't rotate, doesn't scope permissions, and doesn't
 * give customer-service any way to distinguish "notification-service" from
 * any other holder of the same key. The production-grade version of this is
 * an OAuth2 client-credentials grant (this service authenticates to
 * oms-main's auth module with its own client id/secret, gets back a
 * short-lived service-scoped JWT, and THIS interceptor attaches that JWT
 * instead of a static key) — Spring Security's
 * {@code spring-security-oauth2-client} plus a
 * {@code RestClient.Builder} configured with an OAuth2-aware request
 * interceptor is the standard way to get there, deliberately not built out
 * in this first pass to avoid speculatively building an auth flow oms-main's
 * own auth module may not support yet.
 * <p>
 * <b>customer-service's own SecurityConfig does not accept this header
 * today.</b> That service's resource-server config only ever verifies a
 * JWT against oms-main's JWKS endpoint (see that repo's SecurityConfig) — it
 * has no concept of a service API key at all yet. Sending this header
 * currently does nothing useful; customer-service needs a matching change
 * (e.g. a filter that accepts either a valid JWT OR a valid value of this
 * header, scoped to only the endpoints service-to-service callers actually
 * need) before this integration will actually authenticate. This is a
 * cross-repo dependency this scaffold does not resolve on its own.
 */
public class InternalServiceAuthInterceptor implements ClientHttpRequestInterceptor {

    private static final String SERVICE_API_KEY_HEADER = "X-Internal-Service-Key";

    private final String serviceApiKey;

    public InternalServiceAuthInterceptor(String serviceApiKey) {
        this.serviceApiKey = serviceApiKey;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(SERVICE_API_KEY_HEADER, serviceApiKey);
        return execution.execute(request, body);
    }
}
