package com.giri.oms.orderclient.service;

import com.giri.oms.orderclient.dto.OrderClientResponse;

/**
 * The one thing this service needs from oms-main's order endpoint: the
 * customerId an order belongs to, since OrderConfirmedEvent/OrderCancelledEvent
 * don't carry it — see messaging.event.OrderConfirmedEvent's Javadoc. This
 * client (and the network hop it costs on every single notification) is
 * the pragmatic Phase 1 answer to that gap, not the recommended long-term
 * one — see this service's README for the alternative (adding customerId
 * additively to those events) and why it wasn't done as part of this scaffold.
 */
public interface OrderClient {

    OrderClientResponse getOrder(Long orderId);
}
