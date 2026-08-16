package com.giri.oms.common.exception;

/**
 * Implemented by every custom exception that carries a stable, documented
 * ErrorCode — GlobalExceptionHandler's generic handlers rely on this
 * interface rather than a switch over every concrete exception type.
 */
public interface ErrorCoded {
    ErrorCode getErrorCode();
}
