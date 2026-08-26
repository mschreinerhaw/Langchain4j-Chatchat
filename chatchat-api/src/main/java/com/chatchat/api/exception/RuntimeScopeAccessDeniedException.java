package com.chatchat.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when a transport request attempts to cross its authenticated Kernel data scope. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RuntimeScopeAccessDeniedException extends RuntimeException {
    public RuntimeScopeAccessDeniedException(String message) { super(message); }
}
