package com.giri.oms.orderclient.service.impl;

import com.giri.oms.orderclient.dto.OrderClientResponse;
import com.giri.oms.orderclient.exception.OrderNotFoundException;
import com.giri.oms.orderclient.exception.OrderServiceUnavailableException;
import com.giri.oms.orderclient.service.OrderClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Same programmatic resilience4j composition as CustomerClientImpl/
 * shipment-service's own OrderClientImpl — see either's Javadoc for the
 * full reasoning, not repeated a third time here.
 */
@Slf4j
@Component
public class OrderClientImpl implements OrderClient {

    private static final String RESILIENCE_INSTANCE_NAME = "orderClient";

    private final RestClient orderServiceRestClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public OrderClientImpl(RestClient orderServiceRestClient,
                            CircuitBreakerRegistry circuitBreakerRegistry,
                            RetryRegistry retryRegistry) {
        this.orderServiceRestClient = orderServiceRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
    }

    @Override
    public OrderClientResponse getOrder(Long orderId) {
        Supplier<OrderClientResponse> call = () -> doGetOrder(orderId);
        Supplier<OrderClientResponse> resilient =
                CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));

        try {
            return resilient.get();
        } catch (OrderNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("order service call failed for order id {}: {}", orderId, ex.getMessage());
            throw new OrderServiceUnavailableException(orderId, ex);
        }
    }

    private OrderClientResponse doGetOrder(Long orderId) {
        try {
            return orderServiceRestClient.get()
                    .uri("/orders/{id}", orderId)
                    .retrieve()
                    .body(OrderClientResponse.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new OrderNotFoundException(orderId);
        }
    }
}
