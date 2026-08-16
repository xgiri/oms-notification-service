package com.giri.oms.common.exception;

import java.time.LocalDateTime;

/**
 * The uniform error body every handler in GlobalExceptionHandler returns —
 * same shape as every other service in this system, so a client parsing one
 * service's error response can parse any of them.
 */
public record ErrorResponse(
        String code,
        String message,
        String path,
        LocalDateTime timestamp
) {
}
