package com.chatchat.api.exception;

/**
 * Custom exception for chat operations
 */
public class ChatException extends RuntimeException {

    private final int errorCode;

    public ChatException(String message) {
        this(message, 500);
    }

    public ChatException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public ChatException(String message, Throwable cause) {
        this(message, 500, cause);
    }

    public ChatException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Create a ChatException with 400 status
     */
    public static ChatException badRequest(String message) {
        return new ChatException(message, 400);
    }

    /**
     * Create a ChatException with 404 status
     */
    public static ChatException notFound(String message) {
        return new ChatException(message, 404);
    }

    /**
     * Create a ChatException with 500 status
     */
    public static ChatException internalError(String message) {
        return new ChatException(message, 500);
    }
}
