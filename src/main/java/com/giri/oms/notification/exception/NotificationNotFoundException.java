package com.giri.oms.notification.exception;

import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.common.exception.ErrorCoded;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.NOT_FOUND)
public class NotificationNotFoundException extends RuntimeException implements ErrorCoded {

    public NotificationNotFoundException(Long id) {
        super(ErrorCode.NOTIFICATION_NOT_FOUND.formatMessage(id));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.NOTIFICATION_NOT_FOUND;
    }
}
