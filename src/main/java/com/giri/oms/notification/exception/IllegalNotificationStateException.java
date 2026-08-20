package com.giri.oms.notification.exception;

import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.common.exception.ErrorCoded;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown for an operation that conflicts with a notification's current
 * status — currently just {@code resend} on a row that isn't FAILED or
 * DEAD_LETTERED (see {@code NotificationService#resend}'s own Javadoc).
 * Same shape as oms-main's {@code IllegalOrderStateException}/
 * {@code IllegalPaymentStateException} for the same kind of situation.
 */
@ResponseStatus(value = HttpStatus.CONFLICT)
public class IllegalNotificationStateException extends RuntimeException implements ErrorCoded {

    public IllegalNotificationStateException(String message) {
        super(message);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.ILLEGAL_NOTIFICATION_STATE;
    }
}
