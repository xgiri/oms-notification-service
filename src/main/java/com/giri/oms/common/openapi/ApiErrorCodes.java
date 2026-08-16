package com.giri.oms.common.openapi;

import com.giri.oms.common.exception.ErrorCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents which ErrorCodes a given endpoint can actually return — same
 * convention as every other service in this system. See
 * ErrorCodeOperationCustomizer for how this gets rendered into the OpenAPI
 * spec, and (in oms-main) ErrorCodeApiDocumentationConsistencyTest for the
 * test that keeps this annotation honest against GlobalExceptionHandler.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ApiErrorCodes {
    ErrorCode[] value();
}
