package com.giri.oms.orderclient.config;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Own copy of customerclient.config.InternalServiceAuthInterceptor —
 * identical behavior, deliberately not shared, same convention this
 * system's *Client packages already use for AuthHeaderForwardingInterceptor
 * (see oms-main's productclient/customerclient — each has its own copy
 * rather than a shared common one). See that class's Javadoc for the full
 * reasoning on why this service uses a service-identity header instead of
 * forwarding a user's token, and for what's still a placeholder here
 * (a static key, not OAuth2 client-credentials) versus what's genuinely
 * missing on the receiving end (oms-main's own SecurityConfig doesn't
 * accept this header yet either).
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
