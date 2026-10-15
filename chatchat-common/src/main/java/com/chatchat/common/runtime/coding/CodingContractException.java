package com.chatchat.common.runtime.coding;

/** Admission/transport error before an accepted task can supply an authoritative outcome. */
public final class CodingContractException extends IllegalArgumentException {
    private final CodingAgentSPI.Error error;

    public CodingContractException(CodingErrorCode code, String message) {
        this(new CodingAgentSPI.Error(code, false, null, null, null, null, message));
    }

    public CodingContractException(CodingAgentSPI.Error error) {
        super(java.util.Objects.requireNonNull(error, "error").message());
        this.error = error;
    }

    public CodingAgentSPI.Error error() { return error; }
}
