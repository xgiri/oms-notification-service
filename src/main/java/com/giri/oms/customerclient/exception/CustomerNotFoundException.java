package com.giri.oms.customerclient.exception;

import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.common.exception.ErrorCoded;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by CustomerClientImpl when customer-service returns 404 for a given
 * customer id. Uses {@link ErrorCode#CUSTOMER_NOT_FOUND} — same code
 * ({@code ECU100}) oms-main's own customerclient package and customer-service
 * itself use for the same fact, so this doesn't invent a fourth meaning for
 * a wire code that already means one specific thing across this system.
 */
@ResponseStatus(value = HttpStatus.NOT_FOUND)
public class CustomerNotFoundException extends RuntimeException implements ErrorCoded {

    public CustomerNotFoundException(Long id) {
        super(ErrorCode.CUSTOMER_NOT_FOUND.formatMessage(id));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CUSTOMER_NOT_FOUND;
    }
}
