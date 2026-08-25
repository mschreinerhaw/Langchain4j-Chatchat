package com.chatchat.api.exception;

import com.chatchat.api.config.JsonRequestSizeFilter;
import com.chatchat.api.config.RequestCorrelationFilter;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.conversation.ConversationInProgressException;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CancellationException;
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
     * Handle malformed JSON and other unreadable request bodies as client errors.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex,
            WebRequest request) {

        Throwable rootCause = rootCause(ex);
        if (rootCause instanceof JsonRequestSizeFilter.RequestBodyTooLargeException) {
            log.warn("JSON request rejected while streaming: {}", rootCause.getMessage());
            return new ResponseEntity<>(
                ApiResponse.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), rootCause.getMessage()),
                HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
        log.warn("Request body is not valid JSON: {}", rootCauseMessage(ex));

        return new ResponseEntity<>(
            ApiResponse.badRequest(
                "Request body is not valid JSON. Use double-quoted field names and do not escape the outer object."
            ),
            HttpStatus.BAD_REQUEST
        );
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

    @ExceptionHandler(ConversationInProgressException.class)
    public ResponseEntity<ApiResponse<Void>> handleConversationInProgressException(
            ConversationInProgressException ex,
            WebRequest request) {

        log.warn("Conversation deletion rejected: {}", ex.getMessage());
        return new ResponseEntity<>(
            ApiResponse.error(HttpStatus.CONFLICT.value(), ex.getMessage()),
            HttpStatus.CONFLICT
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
            ApiResponse.badRequest("file size exceeds 55MB limit"),
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

        logClientAbort(ex, request);
        return ResponseEntity.status(CLIENT_CLOSED_REQUEST).build();
    }

    @ExceptionHandler(CancellationException.class)
    public ResponseEntity<Void> handleCancellationException(
            CancellationException ex,
            WebRequest request) {

        log.debug("Request cancelled by user: {}", ex.getMessage());
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
            logClientAbort(ex, request);
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

    private void logClientAbort(Throwable ex, WebRequest webRequest) {
        ClientAbortDetails details = clientAbortDetails(ex, webRequest);
        log.debug("Client disconnected before response completed method={} uri={} queryKeys={} requestId={} traceId={} tenantId={} taskId={} contentLength={} durationMs={} responseStatus={} responseCommitted={} responseContentLength={} exceptionType={} rootCauseType={} rootCause={}",
            details.method(), details.uri(), details.queryKeys(), details.requestId(), details.traceId(),
            details.tenantId(), details.taskId(), details.contentLength(), details.durationMs(),
            details.responseStatus(), details.responseCommitted(), details.responseContentLength(),
            details.exceptionType(), details.rootCauseType(), details.rootCauseMessage());
    }

    ClientAbortDetails clientAbortDetails(Throwable ex, WebRequest webRequest) {
        HttpServletRequest request = webRequest instanceof ServletWebRequest servlet ? servlet.getRequest() : null;
        HttpServletResponse response = webRequest instanceof ServletWebRequest servlet ? servlet.getResponse() : null;
        Throwable root = rootCause(ex);
        String uri = request == null ? null : safeLogValue(request.getRequestURI(), 512);
        return new ClientAbortDetails(
            request == null ? null : safeLogValue(request.getMethod(), 16),
            uri,
            request == null ? null : queryKeys(request.getQueryString()),
            requestValue(request, RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE, "X-Request-ID", "requestId"),
            requestValue(request, RequestCorrelationFilter.TRACE_ID_ATTRIBUTE, RequestCorrelationFilter.TRACE_ID_HEADER, "traceId"),
            firstNonBlank(attributeValue(request, ApiAuthenticationFilter.CURRENT_TENANT_ID), parameterValue(request, "tenantId")),
            firstNonBlank(parameterValue(request, "taskId"), taskIdFromUri(uri)),
            request == null ? -1L : request.getContentLengthLong(),
            requestDurationMs(request),
            response == null ? -1 : response.getStatus(),
            response != null && response.isCommitted(),
            response == null ? null : safeLogValue(response.getHeader("Content-Length"), 32),
            ex == null ? null : ex.getClass().getName(),
            root == null ? null : root.getClass().getName(),
            root == null ? null : safeLogValue(root.getMessage(), 512)
        );
    }

    private String requestValue(HttpServletRequest request, String attribute, String header, String parameter) {
        return firstNonBlank(attributeValue(request, attribute),
            request == null ? null : safeLogValue(request.getHeader(header), 128), parameterValue(request, parameter));
    }

    private String attributeValue(HttpServletRequest request, String name) {
        if (request == null || request.getAttribute(name) == null) return null;
        return safeLogValue(String.valueOf(request.getAttribute(name)), 128);
    }

    private String parameterValue(HttpServletRequest request, String name) {
        if (request == null || request.getQueryString() == null) return null;
        String prefix = name + "=";
        return Arrays.stream(request.getQueryString().split("&"))
            .filter(part -> part.startsWith(prefix))
            .map(part -> safeLogValue(part.substring(prefix.length()), 128))
            .findFirst()
            .orElse(null);
    }

    private long requestDurationMs(HttpServletRequest request) {
        if (request == null) return -1L;
        Object started = request.getAttribute(RequestCorrelationFilter.STARTED_NANOS_ATTRIBUTE);
        if (!(started instanceof Number number)) return -1L;
        return Math.max(0L, (System.nanoTime() - number.longValue()) / 1_000_000L);
    }

    private String queryKeys(String query) {
        if (query == null || query.isBlank()) return "[]";
        return Arrays.stream(query.split("&"))
            .map(part -> part.contains("=") ? part.substring(0, part.indexOf('=')) : part)
            .map(key -> safeLogValue(key, 64))
            .filter(key -> key != null && !key.isBlank())
            .distinct()
            .sorted()
            .toList()
            .toString();
    }

    private String taskIdFromUri(String uri) {
        if (uri == null) return null;
        String marker = "/agent/tasks/";
        int start = uri.indexOf(marker);
        if (start < 0) return null;
        String remainder = uri.substring(start + marker.length());
        int slash = remainder.indexOf('/');
        String candidate = slash < 0 ? remainder : remainder.substring(0, slash);
        if (candidate.isBlank() || "runtime".equals(candidate)) return null;
        return safeLogValue(candidate, 128);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private String safeLogValue(String value, int maxLength) {
        if (value == null) return null;
        String safe = value.replace('\r', '_').replace('\n', '_');
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength) + "...";
    }

    record ClientAbortDetails(String method,
                              String uri,
                              String queryKeys,
                              String requestId,
                              String traceId,
                              String tenantId,
                              String taskId,
                              long contentLength,
                              long durationMs,
                              int responseStatus,
                              boolean responseCommitted,
                              String responseContentLength,
                              String exceptionType,
                              String rootCauseType,
                              String rootCauseMessage) {
    }

    private String multipartErrorMessage(Throwable ex) {
        String message = nestedMessage(ex).toLowerCase();
        if (message.contains("exceed") || message.contains("size") || message.contains("maximum")) {
            return "文件上传请求超过大小限制：单文件最大 55MB；超过 5MB 的文档请单独上传";
        }
        return "文件上传请求解析失败，请减少单次上传文件数量或确认单文件不超过 55MB";
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

    private String rootCauseMessage(Throwable ex) {
        Throwable current = rootCause(ex);
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current;
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
