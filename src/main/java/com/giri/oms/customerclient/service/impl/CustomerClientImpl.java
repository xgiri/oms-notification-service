package com.giri.oms.customerclient.service.impl;

import com.giri.oms.customerclient.dto.CustomerClientResponse;
import com.giri.oms.customerclient.exception.CustomerNotFoundException;
import com.giri.oms.customerclient.exception.CustomerServiceUnavailableException;
import com.giri.oms.customerclient.service.CustomerClient;
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
 * Same programmatic resilience4j composition as shipment-service's own
 * OrderClientImpl — see that class's Javadoc for the full reasoning (the
 * 404-vs-everything-else distinction staying explicit in code, no fallback
 * method anywhere). Mirrors oms-main's own CustomerClientImpl too, minus the
 * auth interceptor difference already covered in CustomerClientConfig.
 */
@Slf4j
@Component
public class CustomerClientImpl implements CustomerClient {

    private static final String RESILIENCE_INSTANCE_NAME = "customerClient";

    private final RestClient customerServiceRestClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public CustomerClientImpl(RestClient customerServiceRestClient,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               RetryRegistry retryRegistry) {
        this.customerServiceRestClient = customerServiceRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
    }

    @Override
    public CustomerClientResponse getCustomer(Long customerId) {
        Supplier<CustomerClientResponse> call = () -> doGetCustomer(customerId);
        Supplier<CustomerClientResponse> resilient =
                CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));

        try {
            return resilient.get();
        } catch (CustomerNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("customer-service call failed for customer id {}: {}", customerId, ex.getMessage());
            throw new CustomerServiceUnavailableException(customerId, ex);
        }
    }

    private CustomerClientResponse doGetCustomer(Long customerId) {
        try {
            return customerServiceRestClient.get()
                    .uri("/customers/{id}", customerId)
                    .retrieve()
                    .body(CustomerClientResponse.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new CustomerNotFoundException(customerId);
        }
    }
}
