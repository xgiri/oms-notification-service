package com.giri.oms.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Set;

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
public class InvalidSortFieldException extends RuntimeException implements ErrorCoded {

    public InvalidSortFieldException(String field, Set<String> allowedFields) {
        super(ErrorCode.INVALID_SORT_FIELD.formatMessage(field) + " — allowed: " + allowedFields);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_SORT_FIELD;
    }
}
