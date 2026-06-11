package com.chatchat.mcpserver.config;

import com.chatchat.common.response.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class McpServerExceptionHandler {

    /**
     * Handles the illegal argument.
     *
     * @param ex the ex value
     * @return the operation result
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.badRequest(ex.getMessage());
    }

    /**
     * Handles the exception.
     *
     * @param ex the ex value
     * @return the operation result
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception ex) {
        return ApiResponse.internalError(ex.getMessage());
    }
}
