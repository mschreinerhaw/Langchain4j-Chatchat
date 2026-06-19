package com.chatchat.api.exception;

import com.chatchat.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for REST API
 *
 * Handles all exceptions and returns consistent error responses
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final HttpStatusCode CLIENT_CLOSED_REQUEST = HttpStatusCode.valueOf(499);

    /**
     * Handle validation exceptions
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            WebRequest request) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach((FieldError error) ->
            errors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("Validation error: {}", errors);

        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
            .code(HttpStatus.BAD_REQUEST.value())
            .message("Validation failed")
            .data(errors)
            .timestamp(System.currentTimeMillis())
            .build();

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle IllegalArgumentException
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException ex,
            WebRequest request) {

        log.warn("Illegal argument: {}", ex.getMessage());

        return new ResponseEntity<>(
            ApiResponse.badRequest(ex.getMessage()),
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * Handles the max upload size exceeded exception.
     *
     * @param ex the ex value
     * @param request the request value
     * @return the operation result
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            WebRequest request) {

        log.warn("Upload size exceeded: {}", ex.getMessage());

        return new ResponseEntity<>(
            ApiResponse.badRequest("file size exceeds 5MB limit"),
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * Handles malformed or oversized multipart requests before controller binding.
     *
     * @param ex the ex value
     * @param request the request value
     * @return the operation result
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipartException(
            MultipartException ex,
            WebRequest request) {

        log.warn("Multipart request failed: {}", ex.getMessage());

        return new ResponseEntity<>(
            ApiResponse.badRequest(multipartErrorMessage(ex)),
            HttpStatus.BAD_REQUEST
        );
    }

    /**
     * Handles the no resource found exception.
     *
     * @param ex the ex value
     * @param request the request value
     * @return the operation result
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(
            NoResourceFoundException ex,
            WebRequest request) {

        log.warn("No route or static resource found: {}", ex.getResourcePath());

        return new ResponseEntity<>(
            ApiResponse.notFound("No route or static resource found: " + ex.getResourcePath()),
            HttpStatus.NOT_FOUND
        );
    }

    /**
     * Handle client disconnects while the server is writing a response.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsableException(
            AsyncRequestNotUsableException ex,
            WebRequest request) {

        log.debug("Client disconnected before response completed: {}", ex.getMessage());
        return ResponseEntity.status(CLIENT_CLOSED_REQUEST).build();
    }

    /**
     * Handle ChatException (custom application exception)
     */
    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ApiResponse<Void>> handleChatException(
            ChatException ex,
            WebRequest request) {

        log.error("Chat exception: {}", ex.getMessage(), ex);

        return new ResponseEntity<>(
            ApiResponse.error(ex.getErrorCode(), ex.getMessage()),
            HttpStatus.valueOf(ex.getErrorCode())
        );
    }

    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(
            Exception ex,
            WebRequest request) {

        if (isClientAbort(ex)) {
            log.debug("Client disconnected before response completed: {}", ex.getMessage());
            return new ResponseEntity<>(null, CLIENT_CLOSED_REQUEST);
        }

        log.error("Unexpected error", ex);

        return new ResponseEntity<>(
            ApiResponse.internalError("An unexpected error occurred: " + ex.getMessage()),
            HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (current instanceof AsyncRequestNotUsableException
                || className.equals("org.apache.catalina.connector.ClientAbortException")
                || className.equals("java.io.EOFException")
                || message.contains("clientabortexception")
                || message.contains("broken pipe")
                || message.contains("connection reset")
                || message.contains("software in your host machine aborted")
                || message.contains("中止了一个已建立的连接")
                || message.contains("远程主机强迫关闭")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String multipartErrorMessage(Throwable ex) {
        String message = nestedMessage(ex).toLowerCase();
        if (message.contains("exceed") || message.contains("size") || message.contains("maximum")) {
            return "文件上传请求超过大小限制：单文件不超过 5MB，批量上传会自动分批，请减少单批文件数量后重试";
        }
        return "文件上传请求解析失败，请减少单次上传文件数量或确认单文件不超过 5MB";
    }

    private String nestedMessage(Throwable ex) {
        StringBuilder builder = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    /**
     * Handle resource not found
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            WebRequest request) {

        log.warn("Resource not found: {}", ex.getMessage());

        return new ResponseEntity<>(
            ApiResponse.notFound(ex.getMessage()),
            HttpStatus.NOT_FOUND
        );
    }
}
