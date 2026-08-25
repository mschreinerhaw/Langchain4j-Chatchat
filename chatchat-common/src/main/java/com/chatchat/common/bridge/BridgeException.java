package com.chatchat.common.bridge;

/** Typed rejection/failure raised by a bridge implementation. */
public final class BridgeException extends RuntimeException {
    private final BridgeStatus status;
    private final String code;

    public BridgeException(BridgeStatus status, String code, String message) {
        super(message);
        this.status = status == null ? BridgeStatus.FAILURE : status;
        this.code = code == null || code.isBlank() ? "BRIDGE_FAILED" : code;
    }

    public BridgeStatus status() { return status; }
    public String code() { return code; }
}
