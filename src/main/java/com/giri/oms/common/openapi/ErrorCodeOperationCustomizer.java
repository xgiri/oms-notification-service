package com.giri.oms.common.openapi;

import com.giri.oms.common.exception.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

@Component
public class ErrorCodeOperationCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiErrorCodes annotation = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
        if (annotation == null) {
            return operation;
        }

        for (ErrorCode code : annotation.value()) {
            String status = String.valueOf(code.httpStatus().value());
            ApiResponse response = new ApiResponse()
                    .description(code.code() + ": " + code.sampleMessage())
                    .content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>())));
            operation.getResponses().addApiResponse(status, response);
        }
        return operation;
    }
}
