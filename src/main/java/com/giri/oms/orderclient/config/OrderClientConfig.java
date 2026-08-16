package com.giri.oms.orderclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class OrderClientConfig {

    @Bean
    public RestClient orderServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.orderclient.base-url}") String baseUrl,
            @Value("${app.orderclient.connect-timeout-ms:300}") long connectTimeoutMs,
            @Value("${app.orderclient.read-timeout-ms:800}") long readTimeoutMs,
            @Value("${app.orderclient.service-api-key}") String serviceApiKey) {

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
