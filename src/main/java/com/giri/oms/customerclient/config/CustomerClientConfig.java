package com.giri.oms.customerclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestClient wiring for calling customer-service. Same shape as oms-main's/
 * shipment-service's own client configs (Boot-provided {@code RestClient.Builder}
 * for trace continuity, {@link JdkClientHttpRequestFactory}-based timeout —
 * see their Javadocs for the full reasoning, not repeated here) with ONE
 * deliberate difference: the interceptor. See {@link InternalServiceAuthInterceptor}'s
 * own (long) Javadoc for why this service can't reuse the
 * AuthHeaderForwardingInterceptor pattern every other client in this system
 * uses.
 */
@Configuration
public class CustomerClientConfig {

    @Bean
    public RestClient customerServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.customerclient.base-url}") String baseUrl,
            @Value("${app.customerclient.connect-timeout-ms:300}") long connectTimeoutMs,
            @Value("${app.customerclient.read-timeout-ms:800}") long readTimeoutMs,
            @Value("${app.customerclient.service-api-key}") String serviceApiKey) {

        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalServiceAuthInterceptor(serviceApiKey))
                .build();
    }
}
