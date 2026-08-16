package com.giri.oms.orderclient.exception;

import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.common.exception.ErrorCoded;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
public class OrderServiceUnavailableException extends RuntimeException implements ErrorCoded {

    public OrderServiceUnavailableException(Long orderId, Throwable cause) {
        super(ErrorCode.ORDER_SERVICE_UNAVAILABLE.formatMessage(orderId), cause);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.ORDER_SERVICE_UNAVAILABLE;
    }
}
