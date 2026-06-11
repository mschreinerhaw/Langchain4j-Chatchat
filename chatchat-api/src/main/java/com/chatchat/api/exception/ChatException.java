package com.chatchat.api.exception;

/**
 * Custom exception for chat operations
 */
public class ChatException extends RuntimeException {

    private final int errorCode;

    /**
     * Creates a new ChatException instance.
     *
     * @param message the message value
     */
    public ChatException(String message) {
        this(message, 500);
    }

    /**
     * Creates a new ChatException instance.
     *
     * @param message the message value
     * @param errorCode the error code value
     */
    public ChatException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new ChatException instance.
     *
     * @param message the message value
     * @param cause the cause value
     */
    public ChatException(String message, Throwable cause) {
        this(message, 500, cause);
    }

    /**
     * Creates a new ChatException instance.
     *
     * @param message the message value
     * @param errorCode the error code value
     * @param cause the cause value
     */
    public ChatException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the error code.
     *
     * @return the error code
     */
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
