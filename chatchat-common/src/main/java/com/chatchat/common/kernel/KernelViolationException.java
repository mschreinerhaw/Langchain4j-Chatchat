package com.chatchat.common.kernel;

/** Deterministic boundary rejection safe to expose as a Kernel error result. */
public class KernelViolationException extends RuntimeException {

    private final String code;

    public KernelViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
