package com.giri.oms.notification.exception;

import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.common.exception.ErrorCoded;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link com.giri.oms.security.UnsubscribeTokenService#parseToken}
 * for any reason the token can't be trusted — bad signature, expired,
 * malformed, or valid-but-wrong-purpose. See {@link ErrorCode#INVALID_UNSUBSCRIBE_TOKEN}
 * for why the message is deliberately generic across all of those cases.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidUnsubscribeTokenException extends RuntimeException implements ErrorCoded {

    public InvalidUnsubscribeTokenException() {
        super(ErrorCode.INVALID_UNSUBSCRIBE_TOKEN.formatMessage());
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_UNSUBSCRIBE_TOKEN;
    }
}
