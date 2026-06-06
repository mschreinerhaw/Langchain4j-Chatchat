package com.chatchat.common.exception;

/**
 * Base exception for ChatChat application
 */
public class ChatChatException extends RuntimeException {

    private final int code;

    public ChatChatException(String message) {
        this(500, message);
    }

    public ChatChatException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ChatChatException(String message, Throwable cause) {
        this(500, message, cause);
    }

    public ChatChatException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
