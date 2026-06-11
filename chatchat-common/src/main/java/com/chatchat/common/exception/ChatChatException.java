package com.chatchat.common.exception;

/**
 * Base exception for ChatChat application
 */
public class ChatChatException extends RuntimeException {

    private final int code;

    /**
     * Creates a new ChatChatException instance.
     *
     * @param message the message value
     */
    public ChatChatException(String message) {
        this(500, message);
    }

    /**
     * Creates a new ChatChatException instance.
     *
     * @param code the code value
     * @param message the message value
     */
    public ChatChatException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Creates a new ChatChatException instance.
     *
     * @param message the message value
     * @param cause the cause value
     */
    public ChatChatException(String message, Throwable cause) {
        this(500, message, cause);
    }

    /**
     * Creates a new ChatChatException instance.
     *
     * @param code the code value
     * @param message the message value
     * @param cause the cause value
     */
    public ChatChatException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Returns the code.
     *
     * @return the code
     */
    public int getCode() {
        return code;
    }
}
